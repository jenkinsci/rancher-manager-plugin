package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.ProxyConfiguration;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rancher Manager HTTP client (2.x). Auth: {@code Authorization: Bearer}.
 * One {@link HttpClient} per instance. {@link #close()} is a no-op: Java 17 {@link HttpClient}
 * has no {@code close()} (added in 21).
 */
final class RancherClient implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(RancherClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HTTP_STATUS_PREFIX = "HTTP ";
    private static final String K8S_CLUSTER_PREFIX = "/k8s/clusters/";
    private static final String APPLY_RESOURCE = "/v1/management.cattle.io.clusters/local?action=apply";
    static final String RESOURCE_QUOTA_NAME = "namespace-quota";
    static final String LIMIT_RANGE_NAME = "namespace-limits";
    static final String PROJECT_ID_FIELD = "field.cattle.io/projectId";
    static final int MAX_ERROR_DETAIL_CHARS = 4000;

    private final int readTimeoutMs;
    private final RancherBuildLogger buildLog;
    private final HttpClient http;

    RancherClient(int connectTimeoutMs, int readTimeoutMs) {
        this(connectTimeoutMs, readTimeoutMs, null);
    }

    RancherClient(int connectTimeoutMs, int readTimeoutMs, RancherBuildLogger buildLog) {
        this.readTimeoutMs = readTimeoutMs;
        this.buildLog = buildLog;
        this.http = ProxyConfiguration.newHttpClientBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void close() {
        // Java 17 HttpClient is not AutoCloseable.
    }

    /**
     * Connectivity + token probe: {@code GET /v3/users?me=true}.
     */
    ProbeDetails probeAccess(String baseUrl, String apiToken) throws IOException {
        String base = RancherUrl.normalizeBaseUrl(baseUrl);
        JsonNode me = httpJson("GET", base + "/v3/users?me=true", apiToken, null, "probe");
        String username = firstNonBlank(text(me, "username"), text(me, "name"), text(me, "id"));
        if (username.isBlank()) {
            return new ProbeDetails("Rancher reachable");
        }
        return new ProbeDetails("Rancher user=" + username);
    }

    /**
     * {@code GET /v3/clusters/{clusterId}} — verifies the cluster exists and is readable.
     */
    JsonNode getCluster(String baseUrl, String apiToken, String clusterId) throws IOException {
        String base = RancherUrl.normalizeBaseUrl(baseUrl);
        String encoded = URLEncoder.encode(clusterId, StandardCharsets.UTF_8);
        try {
            return httpJson("GET", base + "/v3/clusters/" + encoded, apiToken, null, "get cluster");
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("404")) {
                throw new IOException(
                        "Rancher cluster '" + clusterId + "' was not found or is not available"
                                + detailSuffix(msg),
                        e);
            }
            throw e;
        }
    }

    /**
     * Apply multi-doc YAML via Steve {@code action=apply}.
     * Rancher expects JSON {@code {"yaml":"…"}} ({@code importClusterYamlInput}), not raw YAML.
     */
    void applyYaml(String baseUrl, String apiToken, String clusterId, String yamlContent)
            throws IOException {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new IOException("Manifest YAML is empty.");
        }
        String url = clusterK8sPath(baseUrl, clusterId) + APPLY_RESOURCE;
        ObjectNode body = buildApplyYamlBody(yamlContent);
        httpJson("POST", url, apiToken, body, "apply yaml");
    }

    static ObjectNode buildApplyYamlBody(String yamlContent) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("yaml", yamlContent);
        return body;
    }

    /**
     * After Steve apply: poll each workload from the YAML until ready or timeout.
     * Empty {@code workloads} is a no-op (ConfigMap-only manifests).
     * GET pods once on abort when any workload is not ready.
     */
    void waitUntilManifestWorkloadsReady(
            String baseUrl,
            String apiToken,
            String clusterId,
            java.util.List<ManifestWorkloads.Workload> workloads,
            long timeoutMs,
            long intervalMs)
            throws IOException, InterruptedException {
        if (workloads == null || workloads.isEmpty()) {
            return;
        }
        long timeout = Math.max(1L, timeoutMs);
        long interval = Math.max(1L, intervalMs);
        long deadlineNs = System.nanoTime() + timeout * 1_000_000L;
        java.util.Map<String, String> lastStates = new java.util.LinkedHashMap<>();
        while (true) {
            java.util.List<String> notReady = new java.util.ArrayList<>();
            for (ManifestWorkloads.Workload workload : workloads) {
                String stevePath = workload.kind().steveType()
                        + "/"
                        + encodePathSegment(workload.namespace())
                        + "/"
                        + encodePathSegment(workload.name());
                JsonNode resource = getSteveNamespacedOrNull(
                        baseUrl, apiToken, clusterId, stevePath, "get " + workload.display());
                ManifestWorkloadStates.Progress progress =
                        ManifestWorkloadStates.classify(workload.kind(), resource);
                String state = ManifestWorkloadStates.displayState(workload.kind(), resource);
                String key = workload.display();
                String previous = lastStates.put(key, state);
                if (previous == null || !previous.equals(state)) {
                    waitInfo(key + " state=" + state);
                }
                if (progress != ManifestWorkloadStates.Progress.READY) {
                    notReady.add(key + " (" + state + ")");
                }
            }
            if (notReady.isEmpty()) {
                return;
            }
            long leftNs = deadlineNs - System.nanoTime();
            if (leftNs <= 0) {
                String extras = manifestPodsHint(
                        baseUrl, apiToken, clusterId, ManifestWorkloads.namespaces(workloads));
                throw new IOException(
                        withDetail(
                                "Manifest workloads did not become ready within "
                                        + Math.max(1L, (timeout + 999L) / 1000L)
                                        + "s: "
                                        + String.join("; ", notReady),
                                extras));
            }
            sleepWait(interval, leftNs);
        }
    }

    private String manifestPodsHint(
            String baseUrl, String apiToken, String clusterId, java.util.Set<String> namespaces)
            throws IOException {
        if (namespaces == null || namespaces.isEmpty()) {
            return "";
        }
        java.util.List<String> problems = new java.util.ArrayList<>();
        for (String ns : namespaces) {
            JsonNode list = listSteveCollection(baseUrl, apiToken, clusterId, "pods", ns, "list pods");
            for (JsonNode pod : HelmPods.collectionItems(list)) {
                String line = ManifestPods.problemLine(pod);
                if (!line.isBlank()) {
                    problems.add(line);
                }
            }
        }
        return HelmPods.joined(problems);
    }

    private JsonNode getSteveNamespacedOrNull(
            String baseUrl, String apiToken, String clusterId, String stevePath, String debugNote)
            throws IOException {
        String url = clusterK8sPath(baseUrl, clusterId) + "/v1/" + stevePath;
        try {
            return httpJson("GET", url, apiToken, null, debugNote);
        } catch (IOException e) {
            if (isHttpStatus(e, 404)) {
                return null;
            }
            if (isHttpStatus(e, 401) || isHttpStatus(e, 403)) {
                throw new IOException(
                        "Cannot get "
                                + stevePath
                                + ": token cannot read this resource",
                        e);
            }
            throw e;
        }
    }

    record ResolvedProject(String clusterId, String projectId, String name) {
        String catalogId() {
            return clusterId + ":" + projectId;
        }
    }

    /**
     * Resolve a Helm project field via {@code GET /v3/clusters/{clusterId}/projects}.
     */
    ResolvedProject resolveProject(
            String baseUrl, String apiToken, String clusterId, String configured)
            throws IOException {
        String want = configured == null ? "" : configured.trim();
        String err = RancherProjects.validate(want);
        if (err != null) {
            throw new IOException(err);
        }
        String cluster = requireClusterId(clusterId);
        JsonNode list = listProjects(baseUrl, apiToken, cluster);
        JsonNode items = list.path("data");
        if (!items.isArray()) {
            items = list.path("items");
        }
        if (!items.isArray()) {
            throw new IOException(
                    "Rancher did not return a project list — cannot resolve project '" + want + "'.");
        }
        boolean byId = RancherProjects.looksLikeProjectId(want);
        String wantId = byId ? want.toLowerCase(Locale.ROOT) : want;
        List<ResolvedProject> matches = new ArrayList<>();
        for (JsonNode item : items) {
            ResolvedProject candidate = projectFromItem(cluster, item);
            if (candidate == null) {
                continue;
            }
            if (byId) {
                if (candidate.projectId().equalsIgnoreCase(wantId)) {
                    matches.add(candidate);
                }
            } else if (want.equals(candidate.name())) {
                matches.add(candidate);
            }
        }
        if (matches.isEmpty()) {
            throw new IOException(
                    "Rancher project '" + want + "' was not found in cluster '" + cluster + "'.");
        }
        if (matches.size() > 1) {
            throw new IOException(
                    "Rancher project name '" + want + "' is not unique in cluster '" + cluster + "'.");
        }
        return matches.get(0);
    }

    /**
     * GET namespace; create in the project when missing and {@code createIfMissing}.
     * Existing namespace must already belong to the project — no Move.
     *
     * @return {@code existed}, {@code created}, or {@code already-exists}
     */
    String prepareNamespaceInProject(
            String baseUrl,
            String apiToken,
            String clusterId,
            String namespace,
            ResolvedProject project,
            boolean createIfMissing)
            throws IOException {
        if (namespace == null || namespace.isBlank()) {
            throw new IOException("Namespace is required.");
        }
        if (project == null) {
            throw new IOException("Project is required.");
        }
        String ns = namespace.trim();
        String cluster = requireClusterId(clusterId);
        JsonNode existing = getNamespaceOrNull(baseUrl, apiToken, cluster, ns);
        if (existing != null) {
            assertNamespaceInProject(existing, ns, project);
            return "existed";
        }
        if (!createIfMissing) {
            throw new IOException(
                    "Namespace \""
                            + ns
                            + "\" does not exist in cluster \""
                            + cluster
                            + "\". Enable Ensure namespace to create it in project \""
                            + project.name()
                            + "\".");
        }
        ObjectNode body = buildNamespaceCreateBody(ns, project);
        String createUrl = clusterK8sPath(baseUrl, cluster) + "/v1/namespaces";
        try {
            httpJson("POST", createUrl, apiToken, body, "create namespace");
            applyNamespaceResourceLimits(baseUrl, apiToken, cluster, ns);
            return "created";
        } catch (IOException createEx) {
            if (!isHttpStatus(createEx, 409)) {
                throw mapEnsureNamespaceError(createEx, ns);
            }
            JsonNode raced = getNamespaceOrNull(baseUrl, apiToken, cluster, ns);
            if (raced == null) {
                throw mapEnsureNamespaceError(createEx, ns);
            }
            assertNamespaceInProject(raced, ns, project);
            return "already-exists";
        }
    }

    static ObjectNode buildNamespaceCreateBody(String namespace, ResolvedProject project) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("apiVersion", "v1");
        body.put("kind", "Namespace");
        ObjectNode metadata = body.putObject("metadata");
        metadata.put("name", namespace);
        metadata.putObject("annotations").put(PROJECT_ID_FIELD, project.catalogId());
        metadata.putObject("labels").put(PROJECT_ID_FIELD, project.projectId());
        return body;
    }

    private void applyNamespaceResourceLimits(
            String baseUrl, String apiToken, String clusterId, String namespace) throws IOException {
        String encoded = encodePathSegment(namespace);
        String nsBase = clusterK8sPath(baseUrl, clusterId) + "/v1/namespaces/" + encoded;
        createNamespacedResourceIfAbsent(
                nsBase + "/resourcequotas",
                apiToken,
                buildResourceQuotaBody(namespace),
                namespace,
                "create resource quota");
        createNamespacedResourceIfAbsent(
                nsBase + "/limitranges",
                apiToken,
                buildLimitRangeBody(namespace),
                namespace,
                "create limit range");
    }

    private void createNamespacedResourceIfAbsent(
            String url, String apiToken, ObjectNode body, String namespace, String debugNote)
            throws IOException {
        try {
            httpJson("POST", url, apiToken, body, debugNote);
        } catch (IOException e) {
            if (isHttpStatus(e, 409)) {
                return;
            }
            throw mapEnsureNamespaceError(e, namespace);
        }
    }

    static ObjectNode buildResourceQuotaBody(String namespace) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("apiVersion", "v1");
        body.put("kind", "ResourceQuota");
        ObjectNode metadata = body.putObject("metadata");
        metadata.put("name", RESOURCE_QUOTA_NAME);
        metadata.put("namespace", namespace);
        ObjectNode hard = body.putObject("spec").putObject("hard");
        hard.put("pods", "10");
        hard.put("requests.cpu", "2");
        hard.put("requests.memory", "4Gi");
        hard.put("limits.cpu", "4");
        hard.put("limits.memory", "8Gi");
        return body;
    }

    static ObjectNode buildLimitRangeBody(String namespace) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("apiVersion", "v1");
        body.put("kind", "LimitRange");
        ObjectNode metadata = body.putObject("metadata");
        metadata.put("name", LIMIT_RANGE_NAME);
        metadata.put("namespace", namespace);
        ObjectNode container = body.putObject("spec").putArray("limits").addObject();
        container.put("type", "Container");
        ObjectNode defaults = container.putObject("default");
        defaults.put("cpu", "500m");
        defaults.put("memory", "512Mi");
        ObjectNode defaultRequest = container.putObject("defaultRequest");
        defaultRequest.put("cpu", "100m");
        defaultRequest.put("memory", "128Mi");
        return body;
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode listProjects(String baseUrl, String apiToken, String clusterId) throws IOException {
        String base = RancherUrl.normalizeBaseUrl(baseUrl);
        String encoded = encodePathSegment(clusterId);
        String url = base + "/v3/clusters/" + encoded + "/projects";
        try {
            return httpJson("GET", url, apiToken, null, "list cluster projects");
        } catch (IOException e) {
            throw mapListProjectsError(e, clusterId);
        }
    }

    private static IOException mapListProjectsError(IOException e, String clusterId) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.startsWith("Cannot list projects for cluster \"")) {
            return e;
        }
        String cluster = clusterId == null ? "" : clusterId.trim();
        if (isHttpStatus(e, 401) || isHttpStatus(e, 403)) {
            return new IOException(
                    "Cannot list projects for cluster \""
                            + cluster
                            + "\": token cannot read this cluster's projects",
                    e);
        }
        return e;
    }

    private JsonNode getNamespaceOrNull(
            String baseUrl, String apiToken, String clusterId, String namespace) throws IOException {
        String getUrl = clusterK8sPath(baseUrl, clusterId) + "/v1/namespaces/" + encodePathSegment(namespace);
        try {
            return httpJson("GET", getUrl, apiToken, null, "get namespace");
        } catch (IOException getEx) {
            if (isHttpStatus(getEx, 404)) {
                return null;
            }
            throw mapEnsureNamespaceError(getEx, namespace);
        }
    }

    private static void assertNamespaceInProject(JsonNode namespaceJson, String namespace, ResolvedProject project)
            throws IOException {
        String expected = project.catalogId();
        String found = namespaceProjectAnnotation(namespaceJson);
        if (expected.equals(found)) {
            return;
        }
        String shown = found == null || found.isBlank() ? "none" : found;
        throw new IOException(
                "Namespace \""
                        + namespace
                        + "\" is not in project \""
                        + project.name()
                        + "\" (expected "
                        + expected
                        + ", found "
                        + shown
                        + "). Move the namespace in Rancher or pick a namespace that already belongs to that project.");
    }

    static String namespaceProjectAnnotation(JsonNode namespaceJson) {
        if (namespaceJson == null) {
            return "";
        }
        JsonNode annotations = namespaceJson.path("metadata").path("annotations");
        if (annotations.isMissingNode() || annotations.isNull() || !annotations.isObject()) {
            annotations = namespaceJson.path("annotations");
        }
        return text(annotations, PROJECT_ID_FIELD);
    }

    private static ResolvedProject projectFromItem(String clusterId, JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        String itemCluster = firstNonBlank(text(item, "clusterId"), clusterFromProjectId(text(item, "id")));
        if (!itemCluster.isBlank() && !itemCluster.equals(clusterId)) {
            return null;
        }
        String id = firstNonBlank(text(item, "id"), text(item.path("metadata"), "name"));
        String shortId = shortProjectId(id);
        if (shortId.isBlank() || !RancherProjects.looksLikeProjectId(shortId)) {
            shortId = shortProjectId(text(item.path("metadata"), "name"));
        }
        if (shortId.isBlank() || !RancherProjects.looksLikeProjectId(shortId)) {
            return null;
        }
        String name = firstNonBlank(text(item, "name"), text(item.path("metadata"), "name"), shortId);
        return new ResolvedProject(clusterId, shortId.toLowerCase(Locale.ROOT), name);
    }

    static String shortProjectId(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String value = id.trim();
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && colon < value.length() - 1) {
            return value.substring(colon + 1);
        }
        return value;
    }

    private static String clusterFromProjectId(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        int colon = id.indexOf(':');
        if (colon <= 0) {
            return "";
        }
        return id.substring(0, colon);
    }

    private static String requireClusterId(String clusterId) throws IOException {
        if (clusterId == null || clusterId.isBlank()) {
            throw new IOException("Cluster ID is required.");
        }
        return clusterId.trim();
    }

    /**
     * {@code GET …/catalog.cattle.io.clusterrepos}.
     */
    JsonNode listClusterRepos(String baseUrl, String apiToken, String clusterId) throws IOException {
        String url = clusterK8sPath(baseUrl, clusterId) + "/v1/catalog.cattle.io.clusterrepos";
        return httpJson("GET", url, apiToken, null, "list clusterrepos");
    }

    /**
     * Resolve a chart repository URL to the Rancher ClusterRepo resource name.
     */
    String resolveClusterRepoName(String baseUrl, String apiToken, String clusterId, String repoUrl)
            throws IOException {
        try {
            ChartRepositoryUrls.require(repoUrl);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        String want = ChartRepositoryUrls.normalize(repoUrl);
        JsonNode list = listClusterRepos(baseUrl, apiToken, clusterId);
        JsonNode items = list.path("data");
        if (!items.isArray()) {
            items = list.path("items");
        }
        if (!items.isArray()) {
            throw new IOException(
                    "Rancher did not return ClusterRepo list — cannot map chart repository URL.");
        }
        String hostPath = ChartRepositoryUrls.hostPath(want);
        for (JsonNode item : items) {
            String name = firstNonBlank(
                    text(item.path("metadata"), "name"),
                    text(item, "id"),
                    text(item, "name"));
            String specUrl = firstNonBlank(text(item.path("spec"), "url"), text(item, "url"));
            if (name.isBlank() || specUrl.isBlank()) {
                continue;
            }
            if (want.equals(ChartRepositoryUrls.normalize(specUrl))) {
                return name;
            }
            if (!hostPath.isBlank() && hostPath.equals(ChartRepositoryUrls.hostPath(specUrl))) {
                return name;
            }
        }
        throw new IOException(
                "No Rancher ClusterRepo matches chart repository URL '"
                        + want
                        + "'. In Rancher go to Apps → Repositories and add an HTTP or OCI chart"
                        + " repository, or set repo to an existing ClusterRepo spec.url.");
    }

    /**
     * Refresh ClusterRepo index (same as Dashboard: {@code spec.forceUpdate} + PUT) and wait until settled.
     * PUT 2xx is not enough; the index download is asynchronous.
     */
    void refreshAndWaitForClusterRepo(
            String baseUrl,
            String apiToken,
            String clusterId,
            String repoName,
            long timeoutMs,
            long intervalMs)
            throws IOException, InterruptedException {
        if (repoName == null || repoName.isBlank()) {
            throw new IOException("ClusterRepo name is required.");
        }
        String name = repoName.trim();
        JsonNode before = getClusterRepo(baseUrl, apiToken, clusterId, name);
        putClusterRepoForceUpdate(baseUrl, apiToken, clusterId, name, before);
        long timeout = Math.max(1L, timeoutMs);
        long interval = Math.max(1L, intervalMs);
        long deadlineNs = System.nanoTime() + timeout * 1_000_000L;
        while (true) {
            JsonNode repo = getClusterRepo(baseUrl, apiToken, clusterId, name);
            ClusterRepoStates.Progress progress = ClusterRepoStates.classify(repo, before);
            if (progress == ClusterRepoStates.Progress.READY) {
                return;
            }
            if (progress == ClusterRepoStates.Progress.FAILED) {
                String detail = sanitizeErrorDetail(ClusterRepoStates.failureDetail(repo));
                String suffix = detail.isBlank() ? "" : ": " + detail;
                throw new IOException("ClusterRepo \"" + name + "\" refresh failed" + suffix);
            }
            long leftNs = deadlineNs - System.nanoTime();
            if (leftNs <= 0) {
                throw new IOException(
                        "ClusterRepo \""
                                + name
                                + "\" did not refresh within "
                                + Math.max(1L, (timeout + 999L) / 1000L)
                                + "s.");
            }
            Thread.sleep(Math.min(interval, Math.max(1L, leftNs / 1_000_000L)));
        }
    }

    private void putClusterRepoForceUpdate(
            String baseUrl, String apiToken, String clusterId, String repoName, JsonNode current)
            throws IOException {
        if (!(current instanceof ObjectNode source)) {
            throw new IOException("ClusterRepo \"" + repoName + "\" GET did not return an object.");
        }
        ObjectNode body = source.deepCopy();
        JsonNode specNode = body.get("spec");
        ObjectNode spec = specNode instanceof ObjectNode existing ? existing : body.putObject("spec");
        spec.put("forceUpdate", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        String url = clusterRepoUrl(baseUrl, clusterId, repoName);
        try {
            httpJson("PUT", url, apiToken, body, "update clusterrepo");
        } catch (IOException e) {
            if (isHttpStatus(e, 401) || isHttpStatus(e, 403)) {
                throw new IOException(
                        "Cannot update ClusterRepo \""
                                + repoName
                                + "\": token cannot update clusterrepos",
                        e);
            }
            throw e;
        }
    }

    private JsonNode getClusterRepo(
            String baseUrl, String apiToken, String clusterId, String repoName) throws IOException {
        String url = clusterRepoUrl(baseUrl, clusterId, repoName);
        try {
            return httpJson("GET", url, apiToken, null, "get clusterrepo");
        } catch (IOException e) {
            if (isHttpStatus(e, 401) || isHttpStatus(e, 403)) {
                throw new IOException(
                        "Cannot get ClusterRepo \""
                                + repoName
                                + "\": token cannot read clusterrepos",
                        e);
            }
            throw e;
        }
    }

    private String clusterRepoUrl(String baseUrl, String clusterId, String repoName) {
        return clusterK8sPath(baseUrl, clusterId)
                + "/v1/catalog.cattle.io.clusterrepos/"
                + encodePathSegment(repoName);
    }

    /**
     * After catalog {@code chartActionOutput}: poll {@code metadata.state} on the named Operation,
     * then poll the Helm app (relationships). {@code before} is the app GET from before catalog POST.
     * GET pods only on app abort when a workload is not active.
     * GET operation {@code /logs} once on operation abort (not on wait ticks).
     */
    void waitUntilHelmReleaseSettled(
            String baseUrl,
            String apiToken,
            String clusterId,
            String namespace,
            String releaseName,
            JsonNode chartActionOutput,
            JsonNode before,
            long timeoutMs,
            long intervalMs)
            throws IOException, InterruptedException {
        if (releaseName == null || releaseName.isBlank()) {
            throw new IOException("Helm release name is required.");
        }
        HelmOperations.ChartAction action = HelmOperations.parse(chartActionOutput);
        String ns = namespace == null || namespace.isBlank() ? "default" : namespace.trim();
        String rel = releaseName.trim();
        long timeout = Math.max(1L, timeoutMs);
        long interval = Math.max(1L, intervalMs);
        long deadlineNs = System.nanoTime() + timeout * 1_000_000L;
        waitUntilHelmOperationActive(
                baseUrl, apiToken, clusterId, action, timeout, interval, deadlineNs);
        waitUntilHelmAppSettled(
                baseUrl, apiToken, clusterId, ns, rel, before, timeout, interval, deadlineNs);
    }

    private void waitUntilHelmOperationActive(
            String baseUrl,
            String apiToken,
            String clusterId,
            HelmOperations.ChartAction action,
            long timeoutMs,
            long intervalMs,
            long deadlineNs)
            throws IOException, InterruptedException {
        String lastState = null;
        while (true) {
            JsonNode operation = getHelmOperation(
                    baseUrl, apiToken, clusterId, action.operationNamespace(), action.operationName());
            String state = HelmOperations.displayState(operation);
            if (!state.equals(lastState)) {
                String message = HelmOperations.failureMessage(operation);
                if (message.isBlank()) {
                    waitInfo("Helm operation state=" + state);
                } else {
                    waitInfo("Helm operation state=" + state + " message=" + sanitizeErrorDetail(message));
                }
                lastState = state;
            }
            HelmOperations.Progress progress = HelmOperations.classify(operation);
            long leftNs = deadlineNs - System.nanoTime();
            if (progress == HelmOperations.Progress.FAILED) {
                throw helmOperationFailed(baseUrl, apiToken, clusterId, action, operation);
            }
            if (progress == HelmOperations.Progress.ACTIVE) {
                return;
            }
            if (leftNs <= 0) {
                throw helmOperationTimeout(
                        baseUrl,
                        apiToken,
                        clusterId,
                        action,
                        timeoutMs,
                        state,
                        HelmOperations.failureMessage(operation));
            }
            sleepWait(intervalMs, leftNs);
        }
    }

    private void waitUntilHelmAppSettled(
            String baseUrl,
            String apiToken,
            String clusterId,
            String ns,
            String rel,
            JsonNode before,
            long timeoutMs,
            long intervalMs,
            long deadlineNs)
            throws IOException, InterruptedException {
        String lastState = null;
        List<String> lastBoard = List.of();
        while (true) {
            JsonNode app = getHelmAppOrNull(baseUrl, apiToken, clusterId, ns, rel);
            lastState = logWaitBoard(app, before, lastState, lastBoard);
            lastBoard = nextBoard(app, before, lastBoard);
            HelmAppStates.Progress progress = HelmAppStates.classify(app, before);
            HelmRelationships.Gate gate = HelmRelationships.gate(app);
            long leftNs = deadlineNs - System.nanoTime();
            if (progress == HelmAppStates.Progress.WAITING) {
                if (leftNs <= 0) {
                    throw helmWaitTimeout(
                            rel,
                            ns,
                            timeoutMs,
                            HelmAppStates.displayState(app),
                            abortExtras(app, baseUrl, apiToken, clusterId, ns, rel));
                }
                sleepWait(intervalMs, leftNs);
                continue;
            }
            if (gate == HelmRelationships.Gate.PASSED) {
                if (progress == HelmAppStates.Progress.READY) {
                    return;
                }
                throw helmAppFailed(
                        rel, app, true, abortExtras(app, baseUrl, apiToken, clusterId, ns, rel));
            }
            if (leftNs <= 0) {
                String extras = abortExtras(app, baseUrl, apiToken, clusterId, ns, rel);
                if (progress == HelmAppStates.Progress.READY) {
                    throw helmWorkloadTimeout(rel, ns, timeoutMs, extras);
                }
                throw helmAppFailed(rel, app, false, extras);
            }
            sleepWait(intervalMs, leftNs);
        }
    }

    private void waitInfo(String message) {
        if (buildLog != null) {
            buildLog.info(message);
        }
    }

    private String logWaitBoard(JsonNode app, JsonNode before, String lastState, List<String> lastBoard) {
        if (!HelmAppStates.thisOperation(app, before)) {
            return lastState;
        }
        String state = HelmAppStates.displayState(app);
        if (!state.equals(lastState)) {
            waitInfo("Helm app state=" + state);
        }
        for (String line : HelmRelationships.boardLines(app)) {
            if (!lastBoard.contains(line)) {
                waitInfo(line);
            }
        }
        return state;
    }

    private static List<String> nextBoard(JsonNode app, JsonNode before, List<String> lastBoard) {
        if (!HelmAppStates.thisOperation(app, before)) {
            return lastBoard;
        }
        return HelmRelationships.boardLines(app);
    }

    private String abortExtras(
            JsonNode app,
            String baseUrl,
            String apiToken,
            String clusterId,
            String namespace,
            String releaseName)
            throws IOException {
        String inactive = HelmRelationships.inactiveWorkloads(app);
        if (!HelmRelationships.hasInactiveWorkload(app)) {
            return inactive;
        }
        JsonNode list = listSteveCollection(
                baseUrl, apiToken, clusterId, "pods", namespace, "list pods");
        return withDetail(inactive, HelmPods.joined(HelmPods.problems(list, releaseName)));
    }

    private static void sleepWait(long intervalMs, long leftNs) throws InterruptedException {
        Thread.sleep(Math.min(intervalMs, Math.max(1L, leftNs / 1_000_000L)));
    }

    private JsonNode listSteveCollection(
            String baseUrl,
            String apiToken,
            String clusterId,
            String type,
            String namespace,
            String debugNote)
            throws IOException {
        String url = clusterK8sPath(baseUrl, clusterId)
                + "/v1/"
                + type
                + "/"
                + encodePathSegment(namespace);
        try {
            return httpJson("GET", url, apiToken, null, debugNote);
        } catch (IOException e) {
            if (isHttpStatus(e, 404)) {
                ObjectNode empty = MAPPER.createObjectNode();
                empty.putArray("data");
                return empty;
            }
            if (isHttpStatus(e, 401) || isHttpStatus(e, 403)) {
                throw new IOException(
                        "Cannot list "
                                + type
                                + " in namespace \""
                                + namespace
                                + "\": token cannot list "
                                + type,
                        e);
            }
            throw e;
        }
    }

    private static IOException helmWaitTimeout(
            String rel, String ns, long timeoutMs, String state, String extras) {
        return new IOException(
                withDetail(
                        "Helm app \""
                                + rel
                                + "\" in namespace \""
                                + ns
                                + "\" did not become ready within "
                                + Math.max(1L, (timeoutMs + 999L) / 1000L)
                                + "s (state="
                                + (state == null || state.isBlank() ? "unknown" : state)
                                + ")",
                        extras));
    }

    private static IOException helmWorkloadTimeout(String rel, String ns, long timeoutMs, String extras) {
        return new IOException(
                withDetail(
                        "Helm app \""
                                + rel
                                + "\" in namespace \""
                                + ns
                                + "\" workloads not ready within "
                                + Math.max(1L, (timeoutMs + 999L) / 1000L)
                                + "s",
                        extras));
    }

    private static IOException helmAppFailed(
            String rel, JsonNode last, boolean clusterServing, String extras) {
        String detail = sanitizeErrorDetail(HelmAppStates.failureDetail(last));
        String suffix = detail.isBlank() ? "" : ": " + detail;
        String cluster = clusterServing ? "cluster serving" : "cluster not serving";
        return new IOException(withDetail("Helm app \"" + rel + "\" failed" + suffix + " (" + cluster + ")", extras));
    }

    private IOException helmOperationFailed(
            String baseUrl,
            String apiToken,
            String clusterId,
            HelmOperations.ChartAction action,
            JsonNode operation) {
        String detail = sanitizeErrorDetail(HelmOperations.failureMessage(operation));
        String suffix = detail.isBlank() ? "" : ": " + detail;
        return new IOException(
                withDetail(
                        "Helm operation \""
                                + action.operationName()
                                + "\" in namespace \""
                                + action.operationNamespace()
                                + "\" failed"
                                + suffix,
                        helmOperationLogExtras(baseUrl, apiToken, clusterId, action)));
    }

    private IOException helmOperationTimeout(
            String baseUrl,
            String apiToken,
            String clusterId,
            HelmOperations.ChartAction action,
            long timeoutMs,
            String state,
            String message) {
        String st = state == null || state.isBlank() ? "unknown" : state;
        String detail = sanitizeErrorDetail(message);
        String suffix = detail.isBlank() ? "" : ": " + detail;
        return new IOException(
                withDetail(
                        "Helm operation \""
                                + action.operationName()
                                + "\" in namespace \""
                                + action.operationNamespace()
                                + "\" did not become ready within "
                                + Math.max(1L, (timeoutMs + 999L) / 1000L)
                                + "s (state="
                                + st
                                + ")"
                                + suffix,
                        helmOperationLogExtras(baseUrl, apiToken, clusterId, action)));
    }

    private String helmOperationLogExtras(
            String baseUrl,
            String apiToken,
            String clusterId,
            HelmOperations.ChartAction action) {
        try {
            String raw = getHelmOperationLogs(
                    baseUrl, apiToken, clusterId, action.operationNamespace(), action.operationName());
            if (raw == null || raw.isBlank()) {
                return "helm job log was empty";
            }
            return formatHelmOperationLog(raw);
        } catch (IOException e) {
            if (isHttpStatus(e, 404)) {
                return "";
            }
            if (isHttpStatus(e, 401) || isHttpStatus(e, 403)) {
                return "token cannot read operation logs";
            }
            return cannotReadOperationLogs(e);
        }
    }

    private static String cannotReadOperationLogs(IOException e) {
        int code = httpStatusCode(e);
        if (code > 0) {
            return "cannot read operation logs (HTTP " + code + ")";
        }
        return "cannot read operation logs";
    }

    private String getHelmOperationLogs(
            String baseUrl,
            String apiToken,
            String clusterId,
            String operationNamespace,
            String operationName)
            throws IOException {
        String url = clusterK8sPath(baseUrl, clusterId)
                + "/v1/catalog.cattle.io.operations/"
                + encodePathSegment(operationNamespace.trim())
                + "/"
                + encodePathSegment(operationName.trim())
                + "/logs";
        byte[] bytes = httpRaw("GET", url, apiToken, null, null, null, "get helm operation logs");
        if (bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String formatHelmOperationLog(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return tailDetail(sanitizeErrorDetail(raw.replaceAll("\\s+", " ").trim()));
    }

    static String tailDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }
        if (detail.length() <= MAX_ERROR_DETAIL_CHARS) {
            return detail;
        }
        return "…" + detail.substring(detail.length() - MAX_ERROR_DETAIL_CHARS);
    }

    private static String withDetail(String message, String extras) {
        if (extras == null || extras.isBlank()) {
            return message;
        }
        return message + ": " + extras;
    }

    JsonNode getHelmAppOrNull(
            String baseUrl, String apiToken, String clusterId, String namespace, String releaseName)
            throws IOException {
        if (releaseName == null || releaseName.isBlank()) {
            return null;
        }
        String ns = namespace == null || namespace.isBlank() ? "default" : namespace.trim();
        String rel = releaseName.trim();
        String url = clusterK8sPath(baseUrl, clusterId)
                + "/v1/catalog.cattle.io.apps/"
                + encodePathSegment(ns)
                + "/"
                + encodePathSegment(rel);
        try {
            return httpJson("GET", url, apiToken, null, "get helm app");
        } catch (IOException e) {
            if (isHttpStatus(e, 404)) {
                return null;
            }
            if (isHttpStatus(e, 401) || isHttpStatus(e, 403)) {
                throw new IOException(
                        "Cannot get Helm app \""
                                + rel
                                + "\" in namespace \""
                                + ns
                                + "\": token cannot read catalog apps",
                        e);
            }
            throw e;
        }
    }

    private JsonNode getHelmOperation(
            String baseUrl,
            String apiToken,
            String clusterId,
            String operationNamespace,
            String operationName)
            throws IOException {
        String ns = operationNamespace.trim();
        String name = operationName.trim();
        String url = clusterK8sPath(baseUrl, clusterId)
                + "/v1/catalog.cattle.io.operations/"
                + encodePathSegment(ns)
                + "/"
                + encodePathSegment(name);
        try {
            return httpJson("GET", url, apiToken, null, "get helm operation");
        } catch (IOException e) {
            if (isHttpStatus(e, 404)) {
                return null;
            }
            if (isHttpStatus(e, 401) || isHttpStatus(e, 403)) {
                throw new IOException(
                        "Cannot get Helm operation \""
                                + name
                                + "\" in namespace \""
                                + ns
                                + "\": token cannot read operations",
                        e);
            }
            throw e;
        }
    }

    JsonNode installHelm(String baseUrl, String apiToken, String clusterId, HelmChartRequest request)
            throws IOException {
        return postHelmAction(baseUrl, apiToken, clusterId, request.repoName, "install", request, false);
    }

    JsonNode upgradeHelm(String baseUrl, String apiToken, String clusterId, HelmChartRequest request)
            throws IOException {
        return postHelmAction(baseUrl, apiToken, clusterId, request.repoName, "upgrade", request, true);
    }

    /**
     * Uninstall a Helm release. Ignores 404 (already absent).
     */
    void uninstallHelm(
            String baseUrl,
            String apiToken,
            String clusterId,
            String namespace,
            String releaseName) throws IOException {
        if (releaseName == null || releaseName.isBlank()) {
            throw new IOException("Helm release name is required for uninstall.");
        }
        String ns = namespace == null || namespace.isBlank() ? "default" : namespace.trim();
        String encodedNs = URLEncoder.encode(ns, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedRel = URLEncoder.encode(releaseName.trim(), StandardCharsets.UTF_8).replace("+", "%20");
        String url = clusterK8sPath(baseUrl, clusterId)
                + "/v1/catalog.cattle.io.apps/"
                + encodedNs
                + "/"
                + encodedRel
                + "?action=uninstall";
        ObjectNode body = MAPPER.createObjectNode();
        try {
            httpJson("POST", url, apiToken, body, "uninstall helm");
        } catch (IOException e) {
            if (isHttpStatus(e, 404)) {
                return;
            }
            throw e;
        }
    }

    record ProbeDetails(String primaryLabel) {}

    record HelmChartRequest(
            String repoName,
            String releaseName,
            String chartName,
            String namespace,
            String projectId,
            String version,
            JsonNode values,
            boolean atomic) {}

    private JsonNode postHelmAction(
            String baseUrl,
            String apiToken,
            String clusterId,
            String repoName,
            String action,
            HelmChartRequest request,
            boolean upgrade) throws IOException {
        if (repoName == null || repoName.isBlank()) {
            throw new IOException("ClusterRepo name is required for Helm " + action + ".");
        }
        String encodedRepo = URLEncoder.encode(repoName.trim(), StandardCharsets.UTF_8).replace("+", "%20");
        String url = clusterK8sPath(baseUrl, clusterId)
                + "/v1/catalog.cattle.io.clusterrepos/"
                + encodedRepo
                + "?action="
                + action;
        ObjectNode body;
        try {
            body = buildHelmActionBody(request, upgrade);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        return httpJson("POST", url, apiToken, body, "helm " + action);
    }

    static boolean isMissingChartVersion(IOException e) {
        if (e == null) {
            return false;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return msg.toLowerCase(Locale.ROOT).contains("no chart version found");
    }

    static IOException mapMissingChartVersion(
            IOException e, String repoName, HelmChartRequest request) {
        if (!isMissingChartVersion(e)) {
            return e;
        }
        String chart = request.chartName == null ? "" : request.chartName.trim();
        String ver = request.version == null || request.version.isBlank()
                ? ""
                : " version " + request.version.trim();
        return new IOException(
                "ClusterRepo \""
                        + repoName
                        + "\" has no chart \""
                        + chart
                        + "\""
                        + ver
                        + " in its index after refresh",
                e);
    }

    private static ObjectNode buildHelmActionBody(HelmChartRequest request, boolean upgrade) {
        ObjectNode body = MAPPER.createObjectNode();
        if (request.namespace != null && !request.namespace.isBlank()) {
            body.put("namespace", request.namespace.trim());
        }
        if (request.projectId == null || request.projectId.isBlank()) {
            throw new IllegalArgumentException("Project is required for Helm catalog install.");
        }
        body.put("projectId", request.projectId.trim());
        if (request.atomic) {
            body.put("wait", true);
            body.put("cleanupOnFail", true);
        }
        if (upgrade) {
            body.put("install", true);
        }
        ArrayNode charts = body.putArray("charts");
        ObjectNode chart = charts.addObject();
        chart.put("chartName", request.chartName);
        chart.put("releaseName", request.releaseName);
        if (request.version != null && !request.version.isBlank()) {
            chart.put("version", request.version.trim());
        }
        if (request.values != null && !request.values.isNull() && !request.values.isEmpty()) {
            chart.set("values", request.values);
        }
        return body;
    }

    private static String clusterK8sPath(String baseUrl, String clusterId) {
        String base = RancherUrl.normalizeBaseUrl(baseUrl);
        String encoded = URLEncoder.encode(clusterId, StandardCharsets.UTF_8);
        return base + K8S_CLUSTER_PREFIX + encoded;
    }

    private JsonNode httpJson(
            String method, String apiUrl, String apiToken, JsonNode jsonBody, String debugNote)
            throws IOException {
        byte[] bodyBytes = null;
        if (jsonBody != null) {
            bodyBytes = MAPPER.writeValueAsBytes(jsonBody);
        }
        byte[] responseBytes = httpRaw(
                method,
                apiUrl,
                apiToken,
                bodyBytes,
                jsonBody == null ? null : "application/json",
                "application/json",
                debugNote);
        if (responseBytes.length == 0) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(responseBytes);
    }

    private byte[] httpRaw(
            String method,
            String apiUrl,
            String apiToken,
            byte[] body,
            String contentType,
            String accept,
            String debugNote)
            throws IOException {
        requireApiToken(apiToken);
        URI uri = createApiUri(apiUrl);
        assertRequestHostsAllowed(apiUrl, uri);

        String m = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(1, readTimeoutMs)))
                .header("Authorization", "Bearer " + apiToken);
        if (accept != null && !accept.isBlank()) {
            builder.header("Accept", accept);
        }
        if (body != null && body.length > 0) {
            if (contentType == null || contentType.isBlank()) {
                contentType = "application/octet-stream";
            }
            builder.header("Content-Type", contentType);
            builder.method(m, HttpRequest.BodyPublishers.ofByteArray(body));
        } else if ("GET".equals(m)) {
            builder.GET();
        } else {
            builder.method(m, HttpRequest.BodyPublishers.noBody());
        }

        long startedNs = System.nanoTime();
        HttpResponse<byte[]> response = sendHttp(builder.build(), uri);
        long durationMs = (System.nanoTime() - startedNs) / 1_000_000L;
        String path = RancherBuildLogger.safeRequestPath(uri);

        assertResponseHostAllowed(response);
        int code = response.statusCode();
        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        if (code < 200 || code >= 300) {
            if (buildLog != null) {
                buildLog.http(m, path, durationMs, debugNote);
            }
            throw httpError(code, bytes, uri);
        }
        if (buildLog != null) {
            buildLog.http(m, path, durationMs, debugNote);
        } else {
            LOGGER.log(Level.FINE, "{0} {1} ({2}ms)", new Object[] {m, path, durationMs});
        }
        return bytes;
    }

    private static void requireApiToken(String apiToken) throws IOException {
        if (apiToken == null || apiToken.isBlank()) {
            throw new IOException("Rancher API token is required.");
        }
    }

    private static URI createApiUri(String apiUrl) throws IOException {
        try {
            return URI.create(apiUrl);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid Rancher API URL", e);
        }
    }

    private static void assertRequestHostsAllowed(String apiUrl, URI uri) throws IOException {
        try {
            ConnectionTester.assertHostAllowed(apiUrl, ConnectionTester.DnsPolicy.REQUIRE_RESOLVED);
            ConnectionTester.assertUriHostAllowed(uri);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private HttpResponse<byte[]> sendHttp(HttpRequest request, URI uri) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Rancher HTTP request interrupted", e);
        } catch (IOException e) {
            throw mapTransportError(uri, e);
        }
    }

    private static void assertResponseHostAllowed(HttpResponse<?> response) throws IOException {
        try {
            ConnectionTester.assertUriHostAllowed(response.uri());
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    static boolean isHttpStatus(IOException e, int code) {
        if (e == null) {
            return false;
        }
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String needle = HTTP_STATUS_PREFIX + code;
        return msg.startsWith(needle) || msg.contains(needle + " ") || msg.contains(needle + " -");
    }

    /** {@code HTTP NNN} from a mapped Rancher client error, or {@code -1}. */
    static int httpStatusCode(IOException e) {
        if (e == null) {
            return -1;
        }
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return -1;
        }
        int at = msg.indexOf(HTTP_STATUS_PREFIX);
        if (at < 0) {
            return -1;
        }
        int start = at + HTTP_STATUS_PREFIX.length();
        int end = start;
        while (end < msg.length() && Character.isDigit(msg.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        try {
            return Integer.parseInt(msg.substring(start, end));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static IOException mapEnsureNamespaceError(IOException e, String namespace) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.startsWith("Cannot ensure namespace \"")) {
            return e;
        }
        String ns = namespace == null ? "" : namespace.trim();
        StringBuilder sb = new StringBuilder("Cannot ensure namespace \"").append(ns).append('"');
        String lower = msg.toLowerCase(Locale.ROOT);
        if (isHttpStatus(e, 403) || lower.contains("forbidden") || lower.contains("permission")) {
            sb.append(": token lacks permission");
        }
        String detail = trailingApiDetail(msg);
        if (!detail.isEmpty()) {
            sb.append(" - ").append(detail);
        }
        return new IOException(sb.toString(), e);
    }

    static IOException mapTransportError(URI uri, IOException e) {
        if (e instanceof HttpTimeoutException) {
            return new IOException(timeoutMessage(uri), e);
        }
        Throwable t = e;
        while (t != null) {
            if (t instanceof UnknownHostException) {
                return new IOException(unknownHostMessage(uri), e);
            }
            if (t instanceof ConnectException || isConnectivityMessage(t.getMessage())) {
                return new IOException(connectivityMessage(uri, t.getMessage()), e);
            }
            t = t.getCause();
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (isConnectivityMessage(msg)) {
            return new IOException(connectivityMessage(uri, msg), e);
        }
        return e;
    }

    private static String timeoutMessage(URI uri) {
        String host = uri == null ? null : uri.getHost();
        if (host == null || host.isBlank()) {
            return "Rancher request timed out.";
        }
        return "Rancher request timed out: " + host + ".";
    }

    private static String unknownHostMessage(URI uri) {
        String host = uri == null ? null : uri.getHost();
        if (host == null || host.isBlank()) {
            return "Rancher host could not be resolved.";
        }
        return "Rancher host could not be resolved: " + host + ".";
    }

    private static String connectivityMessage(URI uri, String detail) {
        String host = uri == null ? null : uri.getHost();
        String suffix = detail == null || detail.isBlank() ? "" : " (" + sanitizeErrorDetail(detail) + ")";
        if (host == null || host.isBlank()) {
            return "Cannot connect to Rancher host/port" + suffix;
        }
        return "Cannot connect to Rancher host/port: " + host + suffix;
    }

    static IOException httpError(int code, byte[] bodyBytes, URI uri) {
        if (looksLikeHtml(bodyBytes)) {
            return new IOException(
                    HTTP_STATUS_PREFIX
                            + code
                            + " - response looks like HTML (Rancher UI page?), not the API."
                            + " Use the Rancher Manager base URL (https://rancher.example),"
                            + " without a UI-only path.");
        }
        String detail = extractErrorDetail(bodyBytes);
        String suffix = detail.isBlank() ? "" : " - " + detail;
        if (code == 401) {
            return new IOException(
                    "HTTP 401 - invalid or missing Rancher API token"
                            + suffix
                            + ". Use the Bearer Token from Rancher Account API Keys"
                            + " (token-…:…, not Access Key alone, without the word Bearer)."
                            + " Cluster-scoped keys only work for that cluster.");
        }
        if (code == 403) {
            return new IOException("HTTP 403 - Rancher API token lacks permission" + suffix);
        }
        if (code == 404) {
            return new IOException(
                    "HTTP 404 - Rancher API path or resource not found" + suffix);
        }
        return new IOException(HTTP_STATUS_PREFIX + code + suffix);
    }

    static String extractErrorDetail(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return "";
        }
        String raw = new String(bodyBytes, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            String message = firstNonBlank(text(node, "message"), text(node, "Message"), text(node, "type"));
            if (!message.isBlank()) {
                return truncateDetail(sanitizeErrorDetail(message));
            }
        } catch (IOException ignored) {
            // fall through to raw body
        }
        return truncateDetail(sanitizeErrorDetail(raw.replaceAll("\\s+", " ")));
    }

    static String sanitizeErrorDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }
        String s = detail.replaceAll("(?i)Bearer\\s+\\S+", "Bearer [redacted]");
        s = s.replaceAll(
                "(?i)(authorization|token|api[_-]?key|password)\\s*[:=]\\s*\\S+",
                "$1=[redacted]");
        return s;
    }

    static boolean looksLikeHtml(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return false;
        }
        String start = new String(bodyBytes, 0, Math.min(bodyBytes.length, 64), StandardCharsets.UTF_8)
                .trim()
                .toLowerCase(Locale.ROOT);
        return start.startsWith("<!doctype") || start.startsWith("<html");
    }

    static boolean isConnectivityMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String d = message.toLowerCase(Locale.ROOT);
        return d.contains("connection refused")
                || d.contains("connect timed out")
                || d.contains("connection timed out")
                || d.contains("no route to host")
                || d.contains("network is unreachable");
    }

    private static String detailSuffix(String msg) {
        String detail = trailingApiDetail(msg);
        return detail.isEmpty() ? "" : " - " + detail;
    }

    /**
     * Body after a canned {@code HTTP NNN - reason} prefix. Empty when there is no extra API detail.
     */
    static String trailingApiDetail(String httpMessage) {
        if (httpMessage == null || httpMessage.isBlank()) {
            return "";
        }
        String msg = httpMessage.trim();
        if (!msg.startsWith(HTTP_STATUS_PREFIX)) {
            return "";
        }
        int firstDash = msg.indexOf(" - ");
        if (firstDash < 0) {
            return "";
        }
        String afterStatus = msg.substring(firstDash + 3);
        int secondDash = afterStatus.indexOf(" - ");
        if (secondDash < 0) {
            return "";
        }
        return truncateDetail(sanitizeErrorDetail(afterStatus.substring(secondDash + 3).trim()));
    }

    static String truncateDetail(String detail) {
        if (detail == null) {
            return "";
        }
        if (detail.length() <= MAX_ERROR_DETAIL_CHARS) {
            return detail;
        }
        return detail.substring(0, MAX_ERROR_DETAIL_CHARS) + "…";
    }

    static String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return "";
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return "";
        }
        String s = v.asText("");
        return s == null ? "" : s.trim();
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }
}
