package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class RancherClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROJECT_JSON =
            "{\"data\":[{\"id\":\"local:p-abc12\",\"name\":\"mnp\",\"clusterId\":\"local\"}]}";
    private static final RancherClient.ResolvedProject MNP =
            new RancherClient.ResolvedProject("local", "p-abc12", "mnp");

    private static final String HELM_REL_UPDATING =
            "{\"toId\":\"default/demo-nginx\",\"toType\":\"apps.deployment\","
                    + "\"rel\":\"helmresource\",\"state\":\"updating\","
                    + "\"message\":\"Deployment does not have minimum availability\"}";
    private static final String HELM_REL_ACTIVE =
            "{\"toId\":\"default/demo-nginx\",\"toType\":\"apps.deployment\","
                    + "\"rel\":\"helmresource\",\"state\":\"active\","
                    + "\"message\":\"Deployment is available. Replicas: 1\"}";
    private static final String PODS_IMAGE_PULL =
            "{\"data\":[{\"metadata\":{\"name\":\"demo-nginx-abc\",\"labels\":{"
                    + "\"app.kubernetes.io/instance\":\"demo-nginx\"}},"
                    + "\"status\":{\"containerStatuses\":[{\"name\":\"nginx\",\"state\":{\"waiting\":{"
                    + "\"reason\":\"ImagePullBackOff\",\"message\":\"Back-off pulling image\"}}}]}}]}";

    private HttpServer server;
    private String base;
    private final AtomicInteger meCode = new AtomicInteger(200);
    private final AtomicReference<String> meBody =
            new AtomicReference<>("{\"id\":\"user-1\",\"username\":\"admin\"}");
    private final AtomicInteger clusterCode = new AtomicInteger(200);
    private final AtomicReference<String> clusterBody =
            new AtomicReference<>("{\"id\":\"local\",\"name\":\"local\"}");
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicBoolean applyCalled = new AtomicBoolean();
    private final AtomicReference<String> applyBody = new AtomicReference<>();
    private final AtomicInteger namespaceGetCode = new AtomicInteger(404);
    private final AtomicInteger namespaceGetHits = new AtomicInteger();
    private final AtomicInteger namespaceCreateCode = new AtomicInteger(201);
    private final AtomicInteger resourceQuotaPostCode = new AtomicInteger(201);
    private final AtomicInteger limitRangePostCode = new AtomicInteger(201);
    private final AtomicReference<String> namespaceGetOkBody =
            new AtomicReference<>(annotatedNamespace("test-ns", "local:p-abc12", "p-abc12"));
    private final AtomicReference<String> lastNsCreateBody = new AtomicReference<>();
    private final AtomicInteger projectsCode = new AtomicInteger(200);
    private final AtomicReference<String> projectsBody = new AtomicReference<>(PROJECT_JSON);
    private final List<String> postPaths = new ArrayList<>();
    private final AtomicInteger helmAppCode = new AtomicInteger(200);
    private final AtomicReference<String> helmAppBody = new AtomicReference<>(
            "{\"status\":{\"summary\":{\"state\":\"deployed\"}}}");
    private final AtomicInteger helmAppGets = new AtomicInteger();
    private final AtomicReference<String> helmAppAfterFirst = new AtomicReference<>();
    private final AtomicInteger deploymentsCode = new AtomicInteger(200);
    private final AtomicReference<String> deploymentsBody = new AtomicReference<>("{\"data\":[]}");
    private final AtomicReference<String> statefulsetsBody = new AtomicReference<>("{\"data\":[]}");
    private final AtomicInteger podsCode = new AtomicInteger(200);
    private final AtomicInteger podsGets = new AtomicInteger();
    private final AtomicReference<String> podsBody = new AtomicReference<>("{\"data\":[]}");
    private final AtomicInteger clusterRepoRefreshCode = new AtomicInteger(200);
    private final AtomicInteger clusterRepoGets = new AtomicInteger();
    private final AtomicBoolean clusterRepoRefreshCalled = new AtomicBoolean();
    private final AtomicReference<String> clusterRepoPutBody = new AtomicReference<>();
    private final AtomicBoolean clusterRepoUnchanged = new AtomicBoolean();
    private final AtomicReference<String> clusterReposListBody = new AtomicReference<>(
            "{\"data\":[{\"metadata\":{\"name\":\"rancher-charts\"},"
                    + "\"spec\":{\"url\":\"https://charts.example/helm\"}}]}");
    private final AtomicInteger helmCatalogCode = new AtomicInteger(200);
    private final AtomicReference<String> helmCatalogBody = new AtomicReference<>(CHART_ACTION_JSON);
    private final AtomicInteger helmOperationCode = new AtomicInteger(200);
    private final AtomicInteger helmOperationGets = new AtomicInteger();
    private final AtomicReference<String> helmOperationBody = new AtomicReference<>(OPERATION_ACTIVE);
    private final AtomicReference<String> helmOperationAfterFirst = new AtomicReference<>();
    private final AtomicInteger helmOperationLogCode = new AtomicInteger(200);
    private final AtomicInteger helmOperationLogGets = new AtomicInteger();
    private final AtomicReference<String> helmOperationLogBody = new AtomicReference<>(HELM_OPERATION_LOG);

    private static final String CHART_ACTION_JSON =
            "{\"type\":\"chartActionOutput\",\"operationName\":\"helm-operation-test\","
                    + "\"operationNamespace\":\"default\"}";
    private static final String OPERATION_ACTIVE =
            "{\"metadata\":{\"state\":{\"error\":false,\"transitioning\":false,\"name\":\"active\"}}}";
    private static final String OPERATION_ERROR =
            "{\"metadata\":{\"state\":{\"error\":true,\"transitioning\":false,\"name\":\"error\","
                    + "\"message\":\"exit code: 123\"}}}";
    private static final String OPERATION_TRANSITIONING =
            "{\"metadata\":{\"state\":{\"error\":false,\"transitioning\":true,\"name\":\"in-progress\","
                    + "\"message\":\"running operation\"}}}";
    private static final String HELM_OPERATION_LOG =
            "Error: UPGRADE FAILED: another operation in progress\n";

    @BeforeEach
    public void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        meCode.set(200);
        meBody.set("{\"id\":\"user-1\",\"username\":\"admin\"}");
        clusterCode.set(200);
        clusterBody.set("{\"id\":\"local\",\"name\":\"local\"}");
        lastAuth.set(null);
        lastPath.set(null);
        applyCalled.set(false);
        applyBody.set(null);
        namespaceGetCode.set(404);
        namespaceGetHits.set(0);
        namespaceCreateCode.set(201);
        resourceQuotaPostCode.set(201);
        limitRangePostCode.set(201);
        namespaceGetOkBody.set(annotatedNamespace("test-ns", "local:p-abc12", "p-abc12"));
        lastNsCreateBody.set(null);
        projectsCode.set(200);
        projectsBody.set(PROJECT_JSON);
        postPaths.clear();
        helmAppCode.set(200);
        helmAppBody.set("{\"status\":{\"summary\":{\"state\":\"deployed\"}}}");
        helmAppGets.set(0);
        helmAppAfterFirst.set(null);
        deploymentsCode.set(200);
        deploymentsBody.set("{\"data\":[]}");
        statefulsetsBody.set("{\"data\":[]}");
        podsCode.set(200);
        podsGets.set(0);
        podsBody.set("{\"data\":[]}");
        clusterRepoRefreshCode.set(200);
        clusterRepoGets.set(0);
        clusterRepoRefreshCalled.set(false);
        clusterRepoPutBody.set(null);
        clusterRepoUnchanged.set(false);
        clusterReposListBody.set(
                "{\"data\":[{\"metadata\":{\"name\":\"rancher-charts\"},"
                        + "\"spec\":{\"url\":\"https://charts.example/helm\"}}]}");
        helmCatalogCode.set(200);
        helmCatalogBody.set(CHART_ACTION_JSON);
        helmOperationCode.set(200);
        helmOperationGets.set(0);
        helmOperationBody.set(OPERATION_ACTIVE);
        helmOperationAfterFirst.set(null);
        helmOperationLogCode.set(200);
        helmOperationLogGets.set(0);
        helmOperationLogBody.set(HELM_OPERATION_LOG);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/users", exchange -> {
            capture(exchange);
            respond(exchange, meCode.get(), meBody.get());
        });
        server.createContext("/v3/clusters", exchange -> {
            capture(exchange);
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/projects")) {
                int code = projectsCode.get();
                String body = code == 200
                        ? projectsBody.get()
                        : "{\"message\":\"Unauthorized 401: must authenticate\"}";
                respond(exchange, code, body);
                return;
            }
            respond(exchange, clusterCode.get(), clusterBody.get());
        });
        server.createContext("/k8s/clusters/local/v1/management.cattle.io.clusters/local", exchange -> {
            capture(exchange);
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())
                    && "action=apply".equals(exchange.getRequestURI().getQuery())) {
                applyCalled.set(true);
                applyBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, "{}");
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        server.createContext("/k8s/clusters/local/v1/namespaces/test-ns", exchange -> {
            capture(exchange);
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/resourcequotas")) {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    postPaths.add(path);
                    respond(exchange, resourceQuotaPostCode.get(), "{\"metadata\":{\"name\":\"namespace-quota\"}}");
                    return;
                }
            }
            if (path.endsWith("/limitranges")) {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    postPaths.add(path);
                    respond(exchange, limitRangePostCode.get(), "{\"metadata\":{\"name\":\"namespace-limits\"}}");
                    return;
                }
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                int hit = namespaceGetHits.incrementAndGet();
                boolean missing = namespaceGetCode.get() == 404 && hit == 1;
                if (missing) {
                    respond(exchange, 404, "{\"message\":\"not found\"}");
                    return;
                }
                respond(exchange, 200, namespaceGetOkBody.get());
                return;
            }
            respond(exchange, 200, "{}");
        });
        server.createContext("/k8s/clusters/local/v1/namespaces/existing-ns", exchange -> {
            capture(exchange);
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 200, "{\"metadata\":{\"name\":\"existing-ns\"}}");
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        server.createContext("/k8s/clusters/local/v1/namespaces", exchange -> {
            capture(exchange);
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                postPaths.add(exchange.getRequestURI().getPath());
                lastNsCreateBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int code = namespaceCreateCode.get();
                String body = code == 403
                        ? "{\"message\":\"Method POST not supported\"}"
                        : "{\"metadata\":{\"name\":\"test-ns\"}}";
                respond(exchange, code, body);
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        server.createContext("/k8s/clusters/local/v1/catalog.cattle.io.clusterrepos", exchange -> {
            capture(exchange);
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            if (query != null && (query.contains("action=upgrade") || query.contains("action=install"))) {
                respond(exchange, helmCatalogCode.get(), helmCatalogBody.get());
                return;
            }
            if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                clusterRepoRefreshCalled.set(true);
                clusterRepoPutBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int code = clusterRepoRefreshCode.get();
                respond(exchange, code, code == 200 ? clusterRepoPutBody.get() : "{\"message\":\"Unauthorized\"}");
                return;
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (clusterRepoNamedPath(path)) {
                    int n = clusterRepoGets.incrementAndGet();
                    if (clusterRepoUnchanged.get() || n == 1) {
                        respond(exchange, 200,
                                "{\"metadata\":{\"name\":\"rancher-charts\",\"resourceVersion\":\"1\"},"
                                        + "\"status\":{\"downloadTime\":\"t1\","
                                        + "\"conditions\":[{\"type\":\"Downloaded\",\"status\":\"True\"}]}}");
                        return;
                    }
                    respond(exchange, 200,
                            "{\"metadata\":{\"name\":\"rancher-charts\",\"resourceVersion\":\"2\"},"
                                    + "\"status\":{\"downloadTime\":\"t2\","
                                    + "\"conditions\":[{\"type\":\"Downloaded\",\"status\":\"True\"}]}}");
                    return;
                }
                respond(exchange, 200, clusterReposListBody.get());
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        server.createContext("/k8s/clusters/local/v1/catalog.cattle.io.operations", exchange -> {
            capture(exchange);
            if (exchange.getRequestURI().getPath().endsWith("/logs")) {
                helmOperationLogGets.incrementAndGet();
                respond(exchange, helmOperationLogCode.get(), helmOperationLogBody.get());
                return;
            }
            int n = helmOperationGets.incrementAndGet();
            String body = helmOperationBody.get();
            if (n > 1 && helmOperationAfterFirst.get() != null) {
                body = helmOperationAfterFirst.get();
            }
            respond(exchange, helmOperationCode.get(), body);
        });
        server.createContext("/k8s/clusters/local/v1/catalog.cattle.io.apps", exchange -> {
            capture(exchange);
            int n = helmAppGets.incrementAndGet();
            if (n > 1 && helmAppAfterFirst.get() != null) {
                respond(exchange, 200, helmAppAfterFirst.get());
                return;
            }
            respond(exchange, helmAppCode.get(), helmAppBody.get());
        });
        server.createContext("/k8s/clusters/local/v1/apps.deployments", exchange -> {
            capture(exchange);
            respond(exchange, deploymentsCode.get(), deploymentsBody.get());
        });
        server.createContext("/k8s/clusters/local/v1/apps.statefulsets", exchange -> {
            capture(exchange);
            respond(exchange, 200, statefulsetsBody.get());
        });
        server.createContext("/k8s/clusters/local/v1/pods", exchange -> {
            capture(exchange);
            podsGets.incrementAndGet();
            respond(exchange, podsCode.get(), podsBody.get());
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    public void stopServer() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void probeAccess_sendsBearerAndReadsUsername() throws Exception {
        try (RancherClient client = new RancherClient(2000, 2000)) {
            RancherClient.ProbeDetails details = client.probeAccess(base, "secret-token-value");
            assertEquals("Rancher user=admin", details.primaryLabel());
        }
        assertEquals("Bearer secret-token-value", lastAuth.get());
        assertTrue(lastPath.get().startsWith("/v3/users"));
    }

    @Test
    public void probeAccess_maps401WithoutLeakingToken() throws Exception {
        meCode.set(401);
        meBody.set("{\"message\":\"Unauthorized\",\"token\":\"secret-token-value\"}");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            try {
                client.probeAccess(base, "secret-token-value");
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("HTTP 401"));
                assertFalse(e.getMessage().contains("secret-token-value"));
            }
        }
    }

    @Test
    public void getCluster_404_mentionsIdNotToken() throws Exception {
        clusterCode.set(404);
        clusterBody.set("{\"message\":\"not found\"}");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            try {
                client.getCluster(base, "secret-token-value", "missing");
                fail("expected IOException");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("missing"));
                assertFalse(e.getMessage().contains("secret-token-value"));
            }
        }
    }

    @Test
    public void applyYaml_postsJsonWrappedYamlBody() throws Exception {
        String yaml = "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo\n";
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.applyYaml(base, "secret-token-value", "local", yaml);
        }
        assertTrue(applyCalled.get());
        JsonNode body = MAPPER.readTree(applyBody.get());
        assertEquals(yaml, body.path("yaml").asText());
    }

    @Test
    public void applyYaml_preservesCommentsAndMultiDoc() throws Exception {
        String yaml =
                "# header comment\n"
                        + "apiVersion: v1\n"
                        + "kind: ConfigMap\n"
                        + "metadata:\n"
                        + "  name: demo\n"
                        + "---\n"
                        + "apiVersion: v1\n"
                        + "kind: Service\n"
                        + "metadata:\n"
                        + "  name: demo\n";
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.applyYaml(base, "secret-token-value", "local", yaml);
        }
        JsonNode body = MAPPER.readTree(applyBody.get());
        assertEquals(yaml, body.path("yaml").asText());
    }

    @Test
    public void buildApplyYamlBody_wrapsYamlField() {
        String yaml = "# comment\napiVersion: v1\nkind: ConfigMap\n";
        JsonNode body = RancherClient.buildApplyYamlBody(yaml);
        assertEquals(yaml, body.path("yaml").asText());
    }

    @Test
    public void resolveProject_matchesName() throws Exception {
        try (RancherClient client = new RancherClient(2000, 2000)) {
            RancherClient.ResolvedProject resolved =
                    client.resolveProject(base, "secret-token-value", "local", "mnp");
            assertEquals("p-abc12", resolved.projectId());
            assertEquals("local:p-abc12", resolved.catalogId());
            assertEquals("mnp", resolved.name());
        }
        assertTrue(lastPath.get().endsWith("/v3/clusters/local/projects"));
        assertFalse(lastPath.get().contains("/v3/projects"));
    }

    @Test
    public void resolveProject_matchesId() throws Exception {
        try (RancherClient client = new RancherClient(2000, 2000)) {
            RancherClient.ResolvedProject resolved =
                    client.resolveProject(base, "secret-token-value", "local", "p-abc12");
            assertEquals("mnp", resolved.name());
            assertEquals("local:p-abc12", resolved.catalogId());
        }
    }

    @Test
    public void resolveProject_rejectsClusterPrefixedValue() throws Exception {
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.resolveProject(base, "secret-token-value", "local", "local:p-abc12");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Cluster belongs in Cluster ID"));
        }
    }

    @Test
    public void resolveProject_missingName() throws Exception {
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.resolveProject(base, "secret-token-value", "local", "missing");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("was not found"));
        }
    }

    @Test
    public void resolveProject_duplicateName() throws Exception {
        projectsBody.set(
                "{\"data\":["
                        + "{\"id\":\"local:p-aaa11\",\"name\":\"mnp\",\"clusterId\":\"local\"},"
                        + "{\"id\":\"local:p-bbb22\",\"name\":\"mnp\",\"clusterId\":\"local\"}"
                        + "]}");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.resolveProject(base, "secret-token-value", "local", "mnp");
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("not unique"));
        }
    }

    @Test
    public void resolveProject_unauthorized_doesNotCallGlobalProjects() throws Exception {
        projectsCode.set(401);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.resolveProject(base, "secret-token-value", "local", "mnp");
            fail("expected IOException");
        } catch (IOException e) {
            assertEquals(
                    "Cannot list projects for cluster \"local\": token cannot read this cluster's projects",
                    e.getMessage());
            assertFalse(e.getMessage().contains("invalid or missing Rancher API token"));
        }
        assertTrue(lastPath.get().endsWith("/v3/clusters/local/projects"));
        assertFalse(lastPath.get().contains("/v3/projects?"));
    }

    @Test
    public void prepareNamespace_createsWhenMissing() throws Exception {
        try (RancherClient client = new RancherClient(2000, 2000)) {
            String result = client.prepareNamespaceInProject(
                    base, "secret-token-value", "local", "test-ns", MNP, true);
            assertEquals("created", result);
        }
        assertTrue(postPaths.stream().anyMatch(p -> p.endsWith("/v1/namespaces")));
        assertTrue(postPaths.stream().anyMatch(p -> p.endsWith("/test-ns/resourcequotas")));
        assertTrue(postPaths.stream().anyMatch(p -> p.endsWith("/test-ns/limitranges")));
        JsonNode body = MAPPER.readTree(lastNsCreateBody.get());
        assertEquals("local:p-abc12", body.path("metadata").path("annotations")
                .path(RancherClient.PROJECT_ID_FIELD).asText());
        assertEquals("p-abc12", body.path("metadata").path("labels")
                .path(RancherClient.PROJECT_ID_FIELD).asText());
    }

    @Test
    public void prepareNamespace_existingMatchingSkipsQuotaPosts() throws Exception {
        namespaceGetCode.set(200);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            String result = client.prepareNamespaceInProject(
                    base, "secret-token-value", "local", "test-ns", MNP, true);
            assertEquals("existed", result);
        }
        assertTrue(postPaths.stream().noneMatch(p -> p.contains("resourcequotas")));
        assertTrue(postPaths.stream().noneMatch(p -> p.contains("limitranges")));
        assertTrue(postPaths.stream().noneMatch(p -> p.endsWith("/v1/namespaces")));
    }

    @Test
    public void prepareNamespace_existingWrongProjectAbortsWithoutMove() throws Exception {
        namespaceGetCode.set(200);
        namespaceGetOkBody.set(annotatedNamespace("test-ns", "local:p-other", "p-other"));
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.prepareNamespaceInProject(base, "secret-token-value", "local", "test-ns", MNP, true);
            fail("expected project mismatch");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("is not in project"));
            assertTrue(e.getMessage().contains("local:p-other"));
        }
        assertTrue(postPaths.isEmpty());
    }

    @Test
    public void prepareNamespace_missingWhenCreateDisabled() throws Exception {
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.prepareNamespaceInProject(base, "secret-token-value", "local", "test-ns", MNP, false);
            fail("expected missing namespace");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("does not exist"));
        }
        assertTrue(postPaths.isEmpty());
    }

    @Test
    public void prepareNamespace_forbidden_messageNotDuplicated() throws Exception {
        namespaceCreateCode.set(403);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.prepareNamespaceInProject(base, "secret-token-value", "local", "test-ns", MNP, true);
            fail("expected permission error");
        } catch (IOException e) {
            String msg = e.getMessage();
            assertEquals(
                    "Cannot ensure namespace \"test-ns\": token lacks permission - Method POST not supported",
                    msg);
            assertEquals(1, countPhrase(msg, "Cannot ensure namespace"));
            assertEquals(1, countPhrase(msg, "lacks permission"));
        }
    }

    @Test
    public void prepareNamespace_quotaAlreadyExistsIsIdempotent() throws Exception {
        resourceQuotaPostCode.set(409);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            String result = client.prepareNamespaceInProject(
                    base, "secret-token-value", "local", "test-ns", MNP, true);
            assertEquals("created", result);
        }
        assertTrue(postPaths.stream().anyMatch(p -> p.endsWith("/test-ns/resourcequotas")));
        assertTrue(postPaths.stream().anyMatch(p -> p.endsWith("/test-ns/limitranges")));
    }

    @Test
    public void prepareNamespace_conflictThenMatchingIsAlreadyExists() throws Exception {
        namespaceCreateCode.set(409);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            String result = client.prepareNamespaceInProject(
                    base, "secret-token-value", "local", "test-ns", MNP, true);
            assertEquals("already-exists", result);
        }
        assertTrue(postPaths.stream().noneMatch(p -> p.contains("resourcequotas")));
    }

    @Test
    public void buildNamespaceCreateBody_setsProjectAnnotationAndLabel() {
        JsonNode body = RancherClient.buildNamespaceCreateBody("apps", MNP);
        assertEquals("Namespace", body.path("kind").asText());
        assertEquals("local:p-abc12", body.path("metadata").path("annotations")
                .path(RancherClient.PROJECT_ID_FIELD).asText());
        assertEquals("p-abc12", body.path("metadata").path("labels")
                .path(RancherClient.PROJECT_ID_FIELD).asText());
    }

    @Test
    public void trailingApiDetail_stripsCannedHttpPrefix() {
        assertEquals(
                "Method POST not supported",
                RancherClient.trailingApiDetail(
                        "HTTP 403 - Rancher API token lacks permission - Method POST not supported"));
        assertEquals("", RancherClient.trailingApiDetail("HTTP 403 - Rancher API token lacks permission"));
    }

    @Test
    public void resolveClusterRepoName_matchesSpecUrl() throws Exception {
        try (RancherClient client = new RancherClient(2000, 2000)) {
            String name = client.resolveClusterRepoName(
                    base, "secret-token-value", "local", "https://charts.example/helm");
            assertEquals("rancher-charts", name);
        }
        assertFalse(clusterRepoRefreshCalled.get());
    }

    @Test
    public void resolveClusterRepoName_matchesOciSpecUrl() throws Exception {
        clusterReposListBody.set(
                "{\"data\":[{\"metadata\":{\"name\":\"oci-charts\"},"
                        + "\"spec\":{\"url\":\"oci://localhost:5000/charts\"}}]}");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            String name = client.resolveClusterRepoName(
                    base, "secret-token-value", "local", "oci://localhost:5000/charts/");
            assertEquals("oci-charts", name);
        }
    }

    @Test
    public void resolveClusterRepoName_matchesOciHostPathWhenTrailingSlashDiffers() throws Exception {
        clusterReposListBody.set(
                "{\"data\":[{\"metadata\":{\"name\":\"oci-charts\"},"
                        + "\"spec\":{\"url\":\"oci://registry.example:5000/charts/\"}}]}");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            String name = client.resolveClusterRepoName(
                    base, "secret-token-value", "local", "oci://registry.example:5000/charts");
            assertEquals("oci-charts", name);
        }
    }

    @Test
    public void refreshAndWaitForClusterRepo_refreshesThenSettles() throws Exception {
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.refreshAndWaitForClusterRepo(
                    base, "secret-token-value", "local", "rancher-charts", 1000L, 10L);
        }
        assertTrue(clusterRepoRefreshCalled.get());
        assertTrue(clusterRepoGets.get() >= 2);
        String put = clusterRepoPutBody.get();
        assertTrue(put != null && put.contains("\"forceUpdate\""));
    }

    @Test
    public void refreshAndWaitForClusterRepo_unauthorizedRefresh() throws Exception {
        clusterRepoRefreshCode.set(401);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.refreshAndWaitForClusterRepo(
                            base, "secret-token-value", "local", "rancher-charts", 1000L, 10L));
            assertEquals(
                    "Cannot update ClusterRepo \"rancher-charts\": token cannot update clusterrepos",
                    e.getMessage());
        }
        assertEquals(1, clusterRepoGets.get());
    }

    @Test
    public void refreshAndWaitForClusterRepo_unchangedSnapshotTimesOut() throws Exception {
        clusterRepoUnchanged.set(true);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.refreshAndWaitForClusterRepo(
                            base, "secret-token-value", "local", "rancher-charts", 40L, 10L));
            assertTrue(e.getMessage().contains("did not refresh"));
        }
        assertTrue(clusterRepoRefreshCalled.get());
    }

    @Test
    public void upgradeHelm_leavesMissingChartVersionRaw() throws Exception {
        helmCatalogCode.set(500);
        helmCatalogBody.set("{\"message\":\"no chart version found for nginx-0.1.11\"}");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.upgradeHelm(
                            base,
                            "secret-token-value",
                            "local",
                            new RancherClient.HelmChartRequest(
                                    "rancher-charts",
                                    "demo-nginx",
                                    "nginx",
                                    "default",
                                    "local:p-abc12",
                                    "0.1.11",
                                    null,
                                    false)));
            assertTrue(e.getMessage().contains("no chart version found for nginx-0.1.11"));
            assertFalse(e.getMessage().contains("after refresh"));
        }
    }

    @Test
    public void isMissingChartVersion_onlyCatalogIndexMiss() {
        assertTrue(RancherClient.isMissingChartVersion(
                new IOException("HTTP 500 - no chart version found for nginx-0.1.11")));
        assertFalse(RancherClient.isMissingChartVersion(
                new IOException("HTTP 500 - cluster unavailable")));
        assertFalse(RancherClient.isMissingChartVersion(new IOException("HTTP 404 - not found")));
        assertFalse(RancherClient.isMissingChartVersion(null));
    }

    @Test
    public void mapMissingChartVersion_rewritesOnlyIndexMiss() {
        RancherClient.HelmChartRequest request = new RancherClient.HelmChartRequest(
                "rancher-charts",
                "demo-nginx",
                "nginx",
                "default",
                "local:p-abc12",
                "0.1.11",
                null,
                false);
        IOException miss = new IOException("HTTP 500 - no chart version found for nginx-0.1.11");
        assertEquals(
                "ClusterRepo \"rancher-charts\" has no chart \"nginx\" version 0.1.11 in its index after refresh",
                RancherClient.mapMissingChartVersion(miss, "rancher-charts", request).getMessage());
        IOException other = new IOException("HTTP 500 - cluster unavailable");
        assertSame(other, RancherClient.mapMissingChartVersion(other, "rancher-charts", request));
    }

    @Test
    public void waitUntilHelmReleaseSettled_returnsWhenDeployedAndNoWorkloads() throws Exception {
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.waitUntilHelmReleaseSettled(
                    base, "secret-token-value", "local", "default", "demo-nginx",
                    chartAction(), null, 1000L, 10L);
        }
        assertEquals(1, helmOperationGets.get());
        assertEquals(0, helmOperationLogGets.get());
        assertEquals(1, helmAppGets.get());
        assertEquals(0, podsGets.get());
        assertTrue(lastPath.get().contains("/catalog.cattle.io.apps/"));
        assertFalse(lastPath.get().contains("/apps.deployments/"));
    }

    @Test
    public void waitUntilHelmReleaseSettled_activeDeploymentDoesNotListWorkloads() throws Exception {
        helmAppBody.set(helmAppJson("deployed", HELM_REL_ACTIVE));
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.waitUntilHelmReleaseSettled(
                    base, "secret-token-value", "local", "default", "demo-nginx",
                    chartAction(), null, 1000L, 10L);
        }
        assertEquals(1, helmAppGets.get());
        assertEquals(0, podsGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_failedClusterServing() throws Exception {
        helmAppBody.set(
                "{\"status\":{\"summary\":{\"state\":\"failed\",\"message\":\"exit 123\"}}}");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 1000L, 10L));
            assertTrue(e.getMessage().contains("Helm app \"demo-nginx\" failed"));
            assertTrue(e.getMessage().contains("exit 123"));
            assertTrue(e.getMessage().contains("cluster serving"));
        }
        assertEquals(0, podsGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_staleFailedThenDeployed() throws Exception {
        String failed = "{\"status\":{\"summary\":{\"state\":\"failed\"}}}";
        helmAppBody.set(failed);
        helmAppAfterFirst.set("{\"status\":{\"summary\":{\"state\":\"deployed\"}}}");
        JsonNode before = MAPPER.readTree(failed);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.waitUntilHelmReleaseSettled(
                    base, "secret-token-value", "local", "default", "demo-nginx",
                    chartAction(), before, 1000L, 10L);
        }
        assertTrue(helmAppGets.get() >= 2);
        assertEquals(0, podsGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_timeoutWhileTransitioning() throws Exception {
        helmAppBody.set("{\"status\":{\"summary\":{\"state\":\"transitioning\"}}}");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 80L, 10L));
            assertTrue(e.getMessage().contains("Helm app"));
            assertTrue(e.getMessage().contains("did not become ready"));
            assertTrue(e.getMessage().contains("state=transitioning"));
        }
        assertTrue(helmAppGets.get() >= 2);
        assertEquals(0, podsGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_pendingUpgradeActiveWorkloadsDoesNotFetchPods()
            throws Exception {
        helmAppBody.set(helmAppJson("pending-upgrade", HELM_REL_ACTIVE));
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 80L, 10L));
            assertTrue(e.getMessage().contains("did not become ready"));
            assertTrue(e.getMessage().contains("state=pending-upgrade"));
        }
        assertTrue(helmAppGets.get() >= 2);
        assertEquals(0, podsGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_pendingUpgradeTimesOutWithPodReason() throws Exception {
        helmAppBody.set(helmAppJson("pending-upgrade", HELM_REL_UPDATING));
        podsBody.set(PODS_IMAGE_PULL);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 80L, 10L));
            assertTrue(e.getMessage().contains("did not become ready"));
            assertTrue(e.getMessage().contains("state=pending-upgrade"));
            assertTrue(e.getMessage().contains("demo-nginx"));
            assertTrue(e.getMessage().contains("ImagePullBackOff"));
        }
        assertEquals(1, podsGets.get());
        assertTrue(lastPath.get().contains("/v1/pods/default"));
    }

    @Test
    public void waitUntilHelmReleaseSettled_podsUnauthorized() throws Exception {
        helmAppBody.set(helmAppJson("pending-upgrade", HELM_REL_UPDATING));
        podsCode.set(401);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 80L, 10L));
            assertTrue(e.getMessage().contains("token cannot list pods"));
        }
        assertEquals(1, podsGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_inactiveWorkloadTimesOut() throws Exception {
        helmAppBody.set(helmAppJson("deployed", HELM_REL_UPDATING));
        podsBody.set(PODS_IMAGE_PULL);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 80L, 10L));
            assertTrue(e.getMessage().contains("workloads not ready"));
            assertTrue(e.getMessage().contains("demo-nginx"));
            assertTrue(e.getMessage().contains("ImagePullBackOff"));
        }
        assertEquals(1, podsGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_unauthorizedMapsMessage() throws Exception {
        helmAppCode.set(401);
        helmAppBody.set("{\"message\":\"Unauthorized 401: must authenticate\"}");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 1000L, 10L));
            assertEquals(
                    "Cannot get Helm app \"demo-nginx\" in namespace \"default\": token cannot read catalog apps",
                    e.getMessage());
        }
    }

    @Test
    public void waitUntilHelmReleaseSettled_operationErrorDoesNotPollApp() throws Exception {
        helmOperationBody.set(OPERATION_ERROR);
        helmAppCode.set(404);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 1000L, 10L));
            assertTrue(e.getMessage().contains("Helm operation \"helm-operation-test\""));
            assertTrue(e.getMessage().contains("failed"));
            assertTrue(e.getMessage().contains("exit code: 123"));
            assertTrue(e.getMessage().contains("UPGRADE FAILED"));
            String truncated = RancherConnections.truncateMessage(
                    new IOException("Helm operation failed: " + e.getMessage()));
            assertTrue(truncated.contains("UPGRADE FAILED"));
            assertTrue(truncated.contains("exit code: 123"));
        }
        assertEquals(1, helmOperationGets.get());
        assertEquals(1, helmOperationLogGets.get());
        assertEquals(0, helmAppGets.get());
        assertEquals(0, podsGets.get());
        assertTrue(lastPath.get().contains("/catalog.cattle.io.operations/"));
        assertTrue(lastPath.get().endsWith("/logs"));
        assertFalse(lastPath.get().contains("link=logs"));
        assertFalse(lastPath.get().contains("/api/v1/namespaces/"));
    }

    @Test
    public void waitUntilHelmReleaseSettled_operationErrorLongLogTailSurvivesTruncate() throws Exception {
        helmOperationBody.set(OPERATION_ERROR);
        helmOperationLogBody.set(
                "pad ".repeat(RancherClient.MAX_ERROR_DETAIL_CHARS)
                        + "Error: UPGRADE FAILED: chart conflict at end");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 1000L, 10L));
            assertTrue(e.getMessage().contains("exit code: 123"));
            assertTrue(e.getMessage().contains("UPGRADE FAILED"));
            String builderError = RancherConnections.truncateMessage(
                    new IOException("Helm operation failed: " + e.getMessage()));
            assertTrue(builderError.contains("UPGRADE FAILED"));
            assertTrue(builderError.contains("exit code: 123"));
            assertTrue(builderError.contains("chart conflict at end"));
        }
        assertEquals(1, helmOperationLogGets.get());
        assertEquals(0, helmAppGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_operationErrorEmptyLogMarksEmpty() throws Exception {
        helmOperationBody.set(OPERATION_ERROR);
        helmOperationLogBody.set("");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 1000L, 10L));
            assertTrue(e.getMessage().contains("exit code: 123"));
            assertTrue(e.getMessage().contains("helm job log was empty"));
            assertFalse(e.getMessage().contains("UPGRADE FAILED"));
        }
        assertEquals(1, helmOperationLogGets.get());
        assertEquals(0, helmAppGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_operationErrorLogsMissingStillAborts() throws Exception {
        helmOperationBody.set(OPERATION_ERROR);
        helmOperationLogCode.set(404);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 1000L, 10L));
            assertTrue(e.getMessage().contains("exit code: 123"));
            assertFalse(e.getMessage().contains("UPGRADE FAILED"));
            assertFalse(e.getMessage().contains("helm job log was empty"));
            assertFalse(e.getMessage().contains("cannot read operation logs"));
            assertFalse(e.getMessage().contains("token cannot read operation logs"));
        }
        assertEquals(1, helmOperationLogGets.get());
        assertEquals(0, helmAppGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_operationErrorLogsUnauthorizedStillAborts() throws Exception {
        helmOperationBody.set(OPERATION_ERROR);
        helmOperationLogCode.set(401);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 1000L, 10L));
            assertTrue(e.getMessage().contains("exit code: 123"));
            assertTrue(e.getMessage().contains("token cannot read operation logs"));
            assertFalse(e.getMessage().contains("UPGRADE FAILED"));
        }
        assertEquals(1, helmOperationLogGets.get());
        assertEquals(0, helmAppGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_operationErrorLogsHttpErrorStillAborts() throws Exception {
        helmOperationBody.set(OPERATION_ERROR);
        helmOperationLogCode.set(406);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 1000L, 10L));
            assertTrue(e.getMessage().contains("exit code: 123"));
            assertTrue(e.getMessage().contains("cannot read operation logs"));
            assertTrue(e.getMessage().contains("406"));
            assertFalse(e.getMessage().contains("UPGRADE FAILED"));
        }
        assertEquals(1, helmOperationLogGets.get());
        assertEquals(0, helmAppGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_operationTransitioningTimesOutWithoutApp() throws Exception {
        helmOperationBody.set(OPERATION_TRANSITIONING);
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 80L, 10L));
            assertTrue(e.getMessage().contains("Helm operation \"helm-operation-test\""));
            assertTrue(e.getMessage().contains("did not become ready"));
            assertTrue(e.getMessage().contains("state=in-progress"));
            assertTrue(e.getMessage().contains("running operation"));
            assertTrue(e.getMessage().contains("UPGRADE FAILED"));
        }
        assertTrue(helmOperationGets.get() >= 2);
        assertEquals(1, helmOperationLogGets.get());
        assertEquals(0, helmAppGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_operationTimeoutEmptyLogKeepsKstatus() throws Exception {
        helmOperationBody.set(OPERATION_TRANSITIONING);
        helmOperationLogBody.set("   ");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 80L, 10L));
            assertTrue(e.getMessage().contains("did not become ready"));
            assertTrue(e.getMessage().contains("running operation"));
            assertTrue(e.getMessage().contains("helm job log was empty"));
            assertFalse(e.getMessage().contains("UPGRADE FAILED"));
        }
        assertEquals(1, helmOperationLogGets.get());
        assertEquals(0, helmAppGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_operationThenAppAppears() throws Exception {
        helmAppCode.set(404);
        helmAppAfterFirst.set("{\"status\":{\"summary\":{\"state\":\"deployed\"}}}");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            client.waitUntilHelmReleaseSettled(
                    base, "secret-token-value", "local", "default", "demo-nginx",
                    chartAction(), null, 1000L, 10L);
        }
        assertEquals(1, helmOperationGets.get());
        assertEquals(0, helmOperationLogGets.get());
        assertTrue(helmAppGets.get() >= 2);
    }

    @Test
    public void waitUntilHelmReleaseSettled_missingChartActionOutput() throws Exception {
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            MAPPER.readTree("{}"), null, 1000L, 10L));
            assertTrue(e.getMessage().contains("missing operationName"));
        }
        assertEquals(0, helmOperationGets.get());
        assertEquals(0, helmOperationLogGets.get());
        assertEquals(0, helmAppGets.get());
    }

    @Test
    public void waitUntilHelmReleaseSettled_operationUnauthorized() throws Exception {
        helmOperationCode.set(401);
        helmOperationBody.set("{\"message\":\"Unauthorized 401: must authenticate\"}");
        try (RancherClient client = new RancherClient(2000, 2000)) {
            IOException e = assertThrows(
                    IOException.class,
                    () -> client.waitUntilHelmReleaseSettled(
                            base, "secret-token-value", "local", "default", "demo-nginx",
                            chartAction(), null, 1000L, 10L));
            assertEquals(
                    "Cannot get Helm operation \"helm-operation-test\" in namespace \"default\":"
                            + " token cannot read operations",
                    e.getMessage());
        }
        assertEquals(0, helmAppGets.get());
        assertEquals(0, helmOperationLogGets.get());
    }

    @Test
    public void sanitizeErrorDetail_redactsBearer() {
        String out = RancherClient.sanitizeErrorDetail("Authorization: Bearer abcdefghijklmnop");
        assertFalse(out.contains("abcdefghijklmnop"));
        assertTrue(out.toLowerCase().contains("redacted"));
    }

    @Test
    public void formatHelmOperationLog_takesTail() {
        assertEquals("", RancherClient.formatHelmOperationLog("  \n"));
        assertTrue(
                RancherClient.formatHelmOperationLog("helm upgrade\nError: UPGRADE FAILED: conflict")
                        .contains("UPGRADE FAILED"));
        String padded = "x".repeat(RancherClient.MAX_ERROR_DETAIL_CHARS + 10) + "TAIL";
        String out = RancherClient.formatHelmOperationLog(padded);
        assertTrue(out.startsWith("…"));
        assertTrue(out.endsWith("TAIL"));
    }

    @Test
    public void truncateMessage_keepsKstatusAndHelmTail() {
        String marker = "Error: UPGRADE FAILED: keep-me";
        String longMsg = "Helm operation failed: Helm operation \"op\" failed: exit code: 123: "
                + "x".repeat(RancherClient.MAX_ERROR_DETAIL_CHARS)
                + marker;
        String out = RancherConnections.truncateMessage(new IOException(longMsg));
        assertTrue(out.contains("exit code: 123"));
        assertTrue(out.contains(marker));
        assertTrue(out.contains("…"));
        assertTrue(out.length() <= RancherClient.MAX_ERROR_DETAIL_CHARS);
    }

    @Test
    public void httpStatusCode_parsesMappedErrors() {
        assertEquals(406, RancherClient.httpStatusCode(new IOException("HTTP 406 - not acceptable")));
        assertEquals(400, RancherClient.httpStatusCode(new IOException("HTTP 400 - bad request")));
        assertEquals(-1, RancherClient.httpStatusCode(new IOException("connection reset")));
    }

    private static JsonNode chartAction() throws Exception {
        return MAPPER.readTree(CHART_ACTION_JSON);
    }

    private static String helmAppJson(String state, String relationship) {
        return "{\"status\":{\"summary\":{\"state\":\""
                + state
                + "\"}},\"relationships\":["
                + relationship
                + "]}";
    }

    private void capture(HttpExchange exchange) {
        lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        URI uri = exchange.getRequestURI();
        String query = uri.getQuery();
        lastPath.set(query == null || query.isBlank() ? uri.getPath() : uri.getPath() + "?" + query);
    }

    /** GET/PUT item under ClusterRepo type — not the collection URL. */
    private static boolean clusterRepoNamedPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return false;
        }
        int typeEnd = path.lastIndexOf("clusterrepos");
        if (typeEnd >= 0) {
            int after = typeEnd + "clusterrepos".length();
            if (after >= path.length()) {
                return false;
            }
            return path.charAt(after) == '/' && after + 1 < path.length();
        }
        // createContext(…/clusterrepos): path is context-relative "/{name}"
        String rel = path.startsWith("/") ? path.substring(1) : path;
        return !rel.isEmpty() && rel.indexOf('/') < 0;
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String annotatedNamespace(String name, String catalogId, String projectId) {
        return "{\"metadata\":{\"name\":\""
                + name
                + "\",\"annotations\":{\""
                + RancherClient.PROJECT_ID_FIELD
                + "\":\""
                + catalogId
                + "\"},\"labels\":{\""
                + RancherClient.PROJECT_ID_FIELD
                + "\":\""
                + projectId
                + "\"}}}";
    }

    private static int countPhrase(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                break;
            }
            count++;
            from = at + needle.length();
        }
        return count;
    }
}
