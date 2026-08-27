package io.jenkins.plugins.ranchermanager;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import com.fasterxml.jackson.databind.JsonNode;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Freestyle / Pipeline build step: Rancher Manager Helm deploy
 * ({@code @Symbol("rancherHelm")}).
 * <p>
 * Resolves connection, validates chart fields, and installs/upgrades via Rancher catalog API.
 */
public class RancherHelmBuilder extends Builder implements SimpleBuildStep {

    private static final Logger LOGGER = Logger.getLogger(RancherHelmBuilder.class.getName());

    public static final String MODE_INHERIT = ConnectionMode.INHERIT;
    public static final String MODE_MANUAL = ConnectionMode.MANUAL;
    public static final String DEFAULT_NAMESPACE = RancherNamespaces.DEFAULT;
    public static final String DEFAULT_VALUES_FILE = "values.yaml";
    public static final String DEFAULT_REPOSITORY_REFERENCE = RancherManifestBuilder.DEFAULT_REPOSITORY_REFERENCE;
    public static final String VALUES_NONE = HelmValuesSource.NONE;
    public static final String VALUES_REPOSITORY = HelmValuesSource.REPOSITORY;
    public static final String VALUES_YAML = HelmValuesSource.YAML;

    private static final Pattern RELEASE_PATTERN = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

    private final String clusterId;
    private final String releaseName;
    private final String chart;
    private final String repo;

    private String namespace = DEFAULT_NAMESPACE;
    private String project;
    private String version;
    private String valuesSource;
    private String values;
    private String valuesRepositoryUrl;
    private String valuesFilePath;
    private String valuesGitCredentialsId;
    private String valuesRepositoryReferenceName;
    private boolean atomic;
    private boolean forceReinstall;
    private String waitTimeoutSeconds;
    private String rancherConnectionMode;
    private String rancherUrl;
    private String rancherCredentialsId;
    private boolean ensureNamespace = true;
    private boolean validateOnly;
    private boolean verboseLogging;

    @DataBoundConstructor
    public RancherHelmBuilder(String clusterId, String releaseName, String chart, String repo) {
        this.clusterId = clusterId == null ? "" : clusterId.trim();
        this.releaseName = releaseName == null ? "" : releaseName.trim();
        this.chart = chart == null ? "" : chart.trim();
        this.repo = repo == null ? "" : repo.trim();
    }

    public String getClusterId() {
        return clusterId;
    }

    public String getReleaseName() {
        return releaseName;
    }

    public String getChart() {
        return chart;
    }

    public String getRepo() {
        return repo;
    }

    public String getNamespace() {
        return namespace == null || namespace.isBlank() ? DEFAULT_NAMESPACE : namespace.trim();
    }

    @DataBoundSetter
    public void setNamespace(String namespace) {
        this.namespace = namespace == null || namespace.isBlank() ? DEFAULT_NAMESPACE : namespace.trim();
    }

    public String getProject() {
        return project == null ? "" : project.trim();
    }

    @DataBoundSetter
    public void setProject(String project) {
        this.project = project == null ? "" : project.trim();
    }

    public String getVersion() {
        return version;
    }

    @DataBoundSetter
    public void setVersion(String version) {
        this.version = version == null || version.isBlank() ? null : version.trim();
    }

    public String getValuesSource() {
        return HelmValuesSource.resolve(valuesSource, values);
    }

    @DataBoundSetter
    public void setValuesSource(String valuesSource) {
        this.valuesSource = valuesSource == null || valuesSource.isBlank()
                ? null
                : HelmValuesSource.normalize(valuesSource);
    }

    public String getValues() {
        return values;
    }

    @DataBoundSetter
    public void setValues(String values) {
        this.values = values;
    }

    public String getValuesRepositoryUrl() {
        return valuesRepositoryUrl;
    }

    @DataBoundSetter
    public void setValuesRepositoryUrl(String valuesRepositoryUrl) {
        this.valuesRepositoryUrl = valuesRepositoryUrl == null ? "" : valuesRepositoryUrl.trim();
    }

    public String getValuesFilePath() {
        return valuesFilePath == null || valuesFilePath.isBlank() ? DEFAULT_VALUES_FILE : valuesFilePath;
    }

    @DataBoundSetter
    public void setValuesFilePath(String valuesFilePath) {
        this.valuesFilePath = valuesFilePath == null || valuesFilePath.isBlank()
                ? DEFAULT_VALUES_FILE
                : valuesFilePath.trim();
    }

    public String getValuesGitCredentialsId() {
        return valuesGitCredentialsId;
    }

    @DataBoundSetter
    public void setValuesGitCredentialsId(String valuesGitCredentialsId) {
        this.valuesGitCredentialsId =
                valuesGitCredentialsId == null || valuesGitCredentialsId.isBlank()
                        ? null
                        : valuesGitCredentialsId.trim();
    }

    public String getValuesRepositoryReferenceName() {
        return valuesRepositoryReferenceName == null || valuesRepositoryReferenceName.isBlank()
                ? DEFAULT_REPOSITORY_REFERENCE
                : valuesRepositoryReferenceName;
    }

    @DataBoundSetter
    public void setValuesRepositoryReferenceName(String valuesRepositoryReferenceName) {
        this.valuesRepositoryReferenceName =
                valuesRepositoryReferenceName == null || valuesRepositoryReferenceName.isBlank()
                        ? null
                        : valuesRepositoryReferenceName.trim();
    }

    public boolean isAtomic() {
        return atomic;
    }

    @DataBoundSetter
    public void setAtomic(boolean atomic) {
        this.atomic = atomic;
    }

    public boolean isForceReinstall() {
        return forceReinstall;
    }

    @DataBoundSetter
    public void setForceReinstall(boolean forceReinstall) {
        this.forceReinstall = forceReinstall;
    }

    public String getWaitTimeoutSeconds() {
        return waitTimeoutSeconds == null || waitTimeoutSeconds.isBlank()
                ? String.valueOf(HelmAppStates.DEFAULT_TIMEOUT_SECONDS)
                : waitTimeoutSeconds.trim();
    }

    @DataBoundSetter
    public void setWaitTimeoutSeconds(String waitTimeoutSeconds) {
        this.waitTimeoutSeconds = waitTimeoutSeconds == null || waitTimeoutSeconds.isBlank()
                ? null
                : waitTimeoutSeconds.trim();
    }

    public String getRancherConnectionMode() {
        return ConnectionMode.normalize(rancherConnectionMode, MODE_INHERIT);
    }

    @DataBoundSetter
    public void setRancherConnectionMode(String rancherConnectionMode) {
        this.rancherConnectionMode = rancherConnectionMode;
    }

    public String getRancherUrl() {
        return rancherUrl;
    }

    @DataBoundSetter
    public void setRancherUrl(String rancherUrl) {
        this.rancherUrl = rancherUrl == null ? "" : rancherUrl.trim();
    }

    public String getRancherCredentialsId() {
        return rancherCredentialsId;
    }

    @DataBoundSetter
    public void setRancherCredentialsId(String rancherCredentialsId) {
        this.rancherCredentialsId = rancherCredentialsId == null ? "" : rancherCredentialsId.trim();
    }

    public boolean isEnsureNamespace() {
        return ensureNamespace;
    }

    @DataBoundSetter
    public void setEnsureNamespace(boolean ensureNamespace) {
        this.ensureNamespace = ensureNamespace;
    }

    public boolean isValidateOnly() {
        return validateOnly;
    }

    @DataBoundSetter
    public void setValidateOnly(boolean validateOnly) {
        this.validateOnly = validateOnly;
    }

    public boolean isVerboseLogging() {
        return verboseLogging;
    }

    @DataBoundSetter
    public void setVerboseLogging(boolean verboseLogging) {
        this.verboseLogging = verboseLogging;
    }

    @Override
    public boolean requiresWorkspace() {
        return HelmValuesSource.isRepository(getValuesSource());
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        return RancherSteps.performFreestyle(build, launcher, listener, this);
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull EnvVars buildEnv,
            @NonNull TaskListener listener) throws InterruptedException, IOException {
        performHelm(run, buildEnv, null, null, listener);
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener) throws InterruptedException, IOException {
        performHelm(run, run.getEnvironment(listener), workspace, launcher, listener);
    }

    private void performHelm(
            Run<?, ?> run,
            EnvVars buildEnv,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener) throws InterruptedException, IOException {
        try (RancherBuildLogger log = new RancherBuildLogger(LOGGER, listener, verboseLogging)) {
            log.open(RancherBuildLogger.TITLE_HELM);
            performBody(run, buildEnv, workspace, launcher, listener, log);
        }
    }

    private void performBody(
            Run<?, ?> run,
            EnvVars buildEnv,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener,
            RancherBuildLogger log) throws AbortException, InterruptedException {
        long startedNs = System.nanoTime();

        RancherConnections.Authenticated auth = RancherConnections.resolveAuthenticated(
                RancherGlobalConfiguration.get(),
                getRancherConnectionMode(),
                rancherUrl,
                rancherCredentialsId,
                run.getParent(),
                log);
        ResolvedConnection connection = auth.connection;
        HelmInputs inputs = parseInputs(buildEnv, log);

        log.debug(RancherBuildLogger.formatConnection(connection));
        log.info("release=" + inputs.release + " chart=" + inputs.chart
                + " namespace=" + inputs.namespace + " project=" + inputs.project);

        Item item = run.getParent();

        try (RancherClient client = new RancherClient(
                connection.connectTimeoutMs, connection.readTimeoutMs, log)) {
            RancherConnections.runPreflight(client, connection, auth.apiToken, inputs.clusterId, log);

            if (validateOnly) {
                finishValidateOnly(log, startedNs, inputs);
                return;
            }

            JsonNode valuesNode = resolveValuesNode(inputs, item, workspace, launcher, listener, log);
            logValuesDebug(log, inputs, valuesNode);

            deployAndSummarize(
                    client, connection, auth.apiToken, inputs, valuesNode, log, startedNs);
        }
    }

    private void logValuesDebug(RancherBuildLogger log, HelmInputs inputs, JsonNode valuesNode) {
        log.info("Values source=" + inputs.valuesSource);
        if (HelmValuesSource.isNone(inputs.valuesSource)) {
            return;
        }
        int length = valuesNode == null || valuesNode.isNull() ? 0 : valuesNode.toString().length();
        log.debug("valuesLength=" + length);
    }

    private JsonNode resolveValuesNode(
            HelmInputs inputs,
            Item item,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener,
            RancherBuildLogger log) throws AbortException {
        try {
            if (HelmValuesSource.isNone(inputs.valuesSource)) {
                return null;
            }
            if (HelmValuesSource.isYaml(inputs.valuesSource)) {
                requireLooksLikeYaml(values);
                return YamlValues.toJsonNode(values);
            }
            if (workspace == null || launcher == null) {
                throw new IllegalStateException(
                        "Helm Values from repository require an agent workspace (wrap the step in node { } / agent any).");
            }
            log.info("Loading values from Git");
            RancherCredentials.GitAuth gitAuth =
                    RancherConnections.resolveOptionalGitAuth(valuesGitCredentialsId, item, log);
            String content = GitRepositoryFiles.readFile(
                    inputs.valuesRepoUrl,
                    inputs.valuesGitRef,
                    inputs.valuesPath,
                    new GitRepositoryFiles.CloneContext(gitAuth, workspace, launcher, listener));
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException(
                        "Values file from repository is empty: " + inputs.valuesPath);
            }
            requireLooksLikeYaml(content);
            return YamlValues.toJsonNode(content);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw RancherConnections.abort(log, e.getMessage());
        } catch (IOException | InterruptedException e) {
            throw RancherConnections.abort(log, RancherConnections.truncateMessage(e), e);
        }
    }

    private void deployAndSummarize(
            RancherClient client,
            ResolvedConnection connection,
            String apiToken,
            HelmInputs inputs,
            JsonNode valuesNode,
            RancherBuildLogger log,
            long startedNs) throws AbortException, InterruptedException {
        try {
            String repoName = client.resolveClusterRepoName(
                    connection.baseUrl, apiToken, inputs.clusterId, inputs.repo);
            log.info("ClusterRepo=" + repoName);

            RancherClient.ResolvedProject resolved = client.resolveProject(
                    connection.baseUrl, apiToken, inputs.clusterId, inputs.project);
            log.info("Project=" + resolved.name() + " id=" + resolved.catalogId());

            RancherNamespaces.requireInProject(
                    client,
                    connection.baseUrl,
                    apiToken,
                    inputs.clusterId,
                    inputs.namespace,
                    resolved,
                    ensureNamespace,
                    log);

            JsonNode existing = client.getHelmAppOrNull(
                    connection.baseUrl, apiToken, inputs.clusterId, inputs.namespace, inputs.release);
            boolean existed = existing != null;

            CatalogApplyResult applied;
            if (forceReinstall) {
                log.info("Force reinstall — uninstalling release=" + inputs.release);
                client.uninstallHelm(
                        connection.baseUrl, apiToken, inputs.clusterId, inputs.namespace, inputs.release);
                applied = applyCatalogRefreshingIndexIfMissing(
                        client,
                        connection,
                        apiToken,
                        inputs,
                        repoName,
                        resolved,
                        valuesNode,
                        log,
                        () -> new CatalogApplyResult(
                                "reinstalled",
                                installHelm(client, connection, apiToken, inputs, repoName, resolved, valuesNode)));
            } else {
                applied = applyCatalogRefreshingIndexIfMissing(
                        client,
                        connection,
                        apiToken,
                        inputs,
                        repoName,
                        resolved,
                        valuesNode,
                        log,
                        () -> upsertHelm(
                                client, connection, apiToken, inputs, repoName, resolved, valuesNode, existed, log));
            }

            log.info("Waiting for Helm release=" + inputs.release
                    + " timeoutSeconds=" + inputs.waitTimeoutSeconds);
            client.waitUntilHelmReleaseSettled(
                    new RancherClient.ClusterAccess(connection.baseUrl, apiToken, inputs.clusterId),
                    inputs.namespace,
                    inputs.release,
                    applied.actionOutput(),
                    existing,
                    new RancherClient.PollBudget(
                            inputs.waitTimeoutSeconds * 1000L, HelmAppStates.pollIntervalMs()));

            var fields = RancherBuildLogger.summaryFields();
            fields.put("outcome", applied.outcome());
            fields.put("clusterId", inputs.clusterId);
            fields.put("project", resolved.name());
            fields.put("releaseName", inputs.release);
            fields.put("chart", inputs.chart);
            fields.put("valuesSource", inputs.valuesSource);
            fields.put("mode", connection.mode);
            if (inputs.version != null) {
                fields.put("version", inputs.version);
            }
            log.summaryWithDuration(startedNs, fields);
        } catch (AbortException e) {
            throw e;
        } catch (IOException e) {
            throw RancherConnections.abort(
                    log, "Helm operation failed: " + RancherConnections.truncateMessage(e), e);
        }
    }

    @FunctionalInterface
    private interface HelmCatalogApply {
        CatalogApplyResult apply() throws IOException;
    }

    private record CatalogApplyResult(String outcome, JsonNode actionOutput) {}

    private static CatalogApplyResult applyCatalogRefreshingIndexIfMissing(
            RancherClient client,
            ResolvedConnection connection,
            String apiToken,
            HelmInputs inputs,
            String repoName,
            RancherClient.ResolvedProject project,
            JsonNode valuesNode,
            RancherBuildLogger log,
            HelmCatalogApply apply) throws IOException, InterruptedException {
        try {
            return apply.apply();
        } catch (IOException e) {
            if (!RancherClient.isMissingChartVersion(e)) {
                throw e;
            }
            log.info("Refreshing ClusterRepo=" + repoName);
            client.refreshAndWaitForClusterRepo(
                    connection.baseUrl,
                    apiToken,
                    inputs.clusterId,
                    repoName,
                    inputs.waitTimeoutSeconds * 1000L,
                    HelmAppStates.pollIntervalMs());
            try {
                return apply.apply();
            } catch (IOException retry) {
                throw RancherClient.mapMissingChartVersion(
                        retry, repoName, helmRequest(inputs, repoName, project, valuesNode));
            }
        }
    }

    private static CatalogApplyResult upsertHelm(
            RancherClient client,
            ResolvedConnection connection,
            String apiToken,
            HelmInputs inputs,
            String repoName,
            RancherClient.ResolvedProject project,
            JsonNode valuesNode,
            boolean existed,
            RancherBuildLogger log) throws IOException {
        RancherClient.HelmChartRequest request = helmRequest(inputs, repoName, project, valuesNode);
        log.info("Ensuring Helm release");
        try {
            JsonNode output = client.upgradeHelm(connection.baseUrl, apiToken, inputs.clusterId, request);
            return new CatalogApplyResult(existed ? "upgraded" : "installed", output);
        } catch (IOException upgradeEx) {
            if (!RancherClient.isHttpStatus(upgradeEx, 404)) {
                throw upgradeEx;
            }
            JsonNode output = client.installHelm(connection.baseUrl, apiToken, inputs.clusterId, request);
            return new CatalogApplyResult("installed", output);
        }
    }

    private static JsonNode installHelm(
            RancherClient client,
            ResolvedConnection connection,
            String apiToken,
            HelmInputs inputs,
            String repoName,
            RancherClient.ResolvedProject project,
            JsonNode valuesNode) throws IOException {
        return client.installHelm(
                connection.baseUrl,
                apiToken,
                inputs.clusterId,
                helmRequest(inputs, repoName, project, valuesNode));
    }

    private static RancherClient.HelmChartRequest helmRequest(
            HelmInputs inputs,
            String repoName,
            RancherClient.ResolvedProject project,
            JsonNode valuesNode) {
        return new RancherClient.HelmChartRequest(
                repoName,
                inputs.release,
                inputs.chart,
                inputs.namespace,
                project.catalogId(),
                inputs.version,
                valuesNode,
                inputs.atomic);
    }

    static void requireLooksLikeYaml(String content) {
        String trimmed = content == null ? "" : content.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Values YAML is empty.");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.contains(":") && !trimmed.contains("---")) {
            throw new IllegalArgumentException("Values content does not look like YAML.");
        }
    }

    private HelmInputs parseInputs(EnvVars buildEnv, RancherBuildLogger log) throws AbortException {
        final String cluster = RancherConnections.abortOn(
                log, () -> RancherConnections.resolveClusterId(clusterId, buildEnv));
        final String expandedRelease = RancherConnections.abortOn(log, () -> {
            String name = buildEnv.expand(releaseName).trim();
            requireValidReleaseName(name);
            return name;
        });
        final String expandedChart = RancherConnections.abortOn(log, () -> requireNonBlank(buildEnv.expand(chart), "Chart"));
        final String expandedRepo = RancherConnections.abortOn(log, () -> {
            String r = requireNonBlank(buildEnv.expand(repo), "Chart repository URL");
            ChartRepositoryUrls.require(r);
            return ChartRepositoryUrls.normalize(r);
        });
        final String expandedNamespace = RancherConnections.abortOn(
                log, () -> RancherNamespaces.resolve(namespace, buildEnv));
        final String expandedProject = RancherConnections.abortOn(
                log, () -> RancherProjects.resolve(project, buildEnv));
        final int waitSeconds = RancherConnections.abortOn(log, () -> {
            String raw = waitTimeoutSeconds == null ? "" : buildEnv.expand(waitTimeoutSeconds).trim();
            return HelmAppStates.parseTimeoutSeconds(raw);
        });
        String mode = getValuesSource();
        if (HelmValuesSource.isYaml(mode)) {
            if (values == null || values.isBlank()) {
                throw RancherConnections.abort(log, "Values YAML is required when Values source is Manual YAML.");
            }
        } else if (HelmValuesSource.isRepository(mode)) {
            String url = valuesRepositoryUrl == null ? "" : buildEnv.expand(valuesRepositoryUrl).trim();
            if (url.isBlank()) {
                throw RancherConnections.abort(log, "Values repository URL is required for Repository values source.");
            }
            RancherConnections.abortOn(log, () -> {
                RancherManifestBuilder.requireHttpRepoUrl(url);
                return null;
            });
        }
        String ver = version == null ? null : buildEnv.expand(version).trim();
        String valuesRepo = null;
        String valuesRef = null;
        String valuesPath = null;
        if (HelmValuesSource.isRepository(mode)) {
            valuesRepo = buildEnv.expand(valuesRepositoryUrl).trim();
            valuesRef = buildEnv.expand(getValuesRepositoryReferenceName()).trim();
            valuesPath = buildEnv.expand(getValuesFilePath()).trim();
        }
        return new HelmInputs(
                cluster,
                expandedProject,
                expandedRelease,
                expandedChart,
                expandedRepo,
                expandedNamespace,
                ver == null || ver.isBlank() ? null : ver,
                mode,
                valuesRepo,
                valuesRef,
                valuesPath,
                atomic,
                waitSeconds);
    }

    private void finishValidateOnly(RancherBuildLogger log, long startedNs, HelmInputs inputs) {
        log.info("Validate-only — skipping Rancher mutations");
        log.debug("Would resolve project=" + inputs.project);
        log.debug("Would refresh ClusterRepo for " + inputs.repo);
        if (ensureNamespace) {
            log.debug("Would create namespace=" + inputs.namespace + " in project if missing");
        } else {
            log.debug("Would require namespace=" + inputs.namespace + " already in project");
        }
        if (forceReinstall) {
            log.debug("Would uninstall then install release=" + inputs.release);
        } else {
            log.debug("Would install-or-upgrade release=" + inputs.release);
        }
        log.debug("Would wait for Helm release timeoutSeconds=" + inputs.waitTimeoutSeconds);
        log.debug("Chart repo=" + inputs.repo + " chart=" + inputs.chart
                + (inputs.version == null ? "" : " version=" + inputs.version));
        log.debug("valuesSource=" + inputs.valuesSource);
        var fields = RancherBuildLogger.summaryFields();
        fields.put("outcome", "validated");
        fields.put("clusterId", inputs.clusterId);
        fields.put("project", inputs.project);
        fields.put("releaseName", inputs.release);
        fields.put("chart", inputs.chart);
        fields.put("valuesSource", inputs.valuesSource);
        log.summaryWithDuration(startedNs, fields);
    }

    static void requireValidReleaseName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Release name is required.");
        }
        String n = name.trim();
        if (n.length() > 53 || !RELEASE_PATTERN.matcher(n).matches()) {
            throw new IllegalArgumentException(
                    "Release name must be DNS-1123 (lowercase alphanumeric and '-').");
        }
    }

    static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return value.trim();
    }

    private record HelmInputs(
            String clusterId,
            String project,
            String release,
            String chart,
            String repo,
            String namespace,
            String version,
            String valuesSource,
            String valuesRepoUrl,
            String valuesGitRef,
            String valuesPath,
            boolean atomic,
            int waitTimeoutSeconds) {}

    @Symbol("rancherHelm")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Rancher Helm Deployment";
        }

        public String getRancherConnectionSummary() {
            return RancherConnections.connectionSummary();
        }

        @POST
        public FormValidation doCheckClusterId(
                @QueryParameter String value,
                @QueryParameter String rancherConnectionMode,
                @AncestorInPath Item item) {
            RancherConnections.checkConfigure(item);
            return RancherConnections.checkClusterId(value, rancherConnectionMode);
        }

        @POST
        public FormValidation doCheckRancherUrl(
                @QueryParameter String value,
                @QueryParameter String rancherConnectionMode,
                @AncestorInPath Item item) {
            RancherConnections.checkConfigure(item);
            return RancherConnections.checkRancherUrl(value, rancherConnectionMode);
        }

        @POST
        public FormValidation doCheckReleaseName(@QueryParameter String value, @AncestorInPath Item item) {
            RancherConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.error("Release name is required.");
            }
            if (value.trim().indexOf('$') >= 0) {
                return FormValidation.ok();
            }
            try {
                requireValidReleaseName(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckProject(@QueryParameter String value, @AncestorInPath Item item) {
            RancherConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.error("Project is required.");
            }
            if (value.trim().indexOf('$') >= 0) {
                return FormValidation.ok();
            }
            String err = RancherProjects.validate(value);
            return err == null ? FormValidation.ok() : FormValidation.error(err);
        }

        @POST
        public FormValidation doCheckNamespace(@QueryParameter String value, @AncestorInPath Item item) {
            RancherConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            if (value.trim().indexOf('$') >= 0) {
                return FormValidation.ok();
            }
            String err = RancherNamespaces.validate(value);
            return err == null ? FormValidation.ok() : FormValidation.error(err);
        }

        @POST
        public FormValidation doCheckWaitTimeoutSeconds(@QueryParameter String value, @AncestorInPath Item item) {
            RancherConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            if (value.trim().indexOf('$') >= 0) {
                return FormValidation.ok();
            }
            try {
                HelmAppStates.parseTimeoutSeconds(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckRepo(@QueryParameter String value, @AncestorInPath Item item) {
            RancherConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.error("Chart repository URL is required.");
            }
            if (value.trim().indexOf('$') >= 0) {
                return FormValidation.ok();
            }
            try {
                ChartRepositoryUrls.require(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckValues(
                @QueryParameter String value,
                @QueryParameter String valuesSource,
                @AncestorInPath Item item) {
            RancherConnections.checkConfigure(item);
            if (!HelmValuesSource.isYaml(valuesSource)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Values YAML is required for Manual YAML source.");
            }
            try {
                requireLooksLikeYaml(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public ListBoxModel doFillRancherCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String rancherCredentialsId) {
            return RancherCredentials.fillSecretText(item, rancherCredentialsId);
        }

        @POST
        public ListBoxModel doFillValuesGitCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String valuesGitCredentialsId) {
            return RancherCredentials.fillSecretOrUsernamePassword(item, valuesGitCredentialsId);
        }
    }
}
