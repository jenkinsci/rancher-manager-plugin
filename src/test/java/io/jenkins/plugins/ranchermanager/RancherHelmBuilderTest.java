package io.jenkins.plugins.ranchermanager;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.util.Secret;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
public class RancherHelmBuilderTest {

    private static final String CLUSTERREPOS_JSON =
            "{\"data\":[{\"metadata\":{\"name\":\"charts-example\"},"
                    + "\"spec\":{\"url\":\"https://charts.example/helm\"}}]}";
    private static final String CLUSTERREPO_BEFORE =
            "{\"metadata\":{\"name\":\"charts-example\",\"resourceVersion\":\"1\"},"
                    + "\"status\":{\"downloadTime\":\"t1\"}}";
    private static final String CLUSTERREPO_SETTLED =
            "{\"metadata\":{\"name\":\"charts-example\",\"resourceVersion\":\"2\"},"
                    + "\"status\":{\"downloadTime\":\"t2\","
                    + "\"conditions\":[{\"type\":\"Downloaded\",\"status\":\"True\"}]}}";
    private static final String CLUSTERREPO_TRANSITIONING =
            "{\"metadata\":{\"name\":\"charts-example\",\"resourceVersion\":\"2\"},"
                    + "\"status\":{\"summary\":{\"state\":\"transitioning\",\"transitioning\":true}}}";
    private static final String NS_IN_PROJECT =
            "{\"metadata\":{\"name\":\"default\",\"annotations\":{"
                    + "\"field.cattle.io/projectId\":\"local:p-abc12\"},\"labels\":{"
                    + "\"field.cattle.io/projectId\":\"p-abc12\"}}}";
    private static final String PROJECTS_JSON =
            "{\"data\":[{\"id\":\"local:p-abc12\",\"name\":\"mnp\",\"clusterId\":\"local\"}]}";

    private HttpServer server;
    private String base;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastNsCreateBody = new AtomicReference<>();
    private final AtomicBoolean installCalled = new AtomicBoolean();
    private final AtomicInteger installCalls = new AtomicInteger();
    private final AtomicInteger installStaleIndexLeft = new AtomicInteger();
    private final AtomicBoolean upgradeCalled = new AtomicBoolean();
    private final AtomicBoolean uninstallCalled = new AtomicBoolean();
    private final AtomicInteger uninstallCalls = new AtomicInteger();
    private final AtomicBoolean projectsCalled = new AtomicBoolean();
    private final AtomicBoolean nsPosted = new AtomicBoolean();
    private final AtomicInteger nsQuotaPosts = new AtomicInteger();
    private final AtomicBoolean refreshCalled = new AtomicBoolean();
    private final AtomicInteger refreshCode = new AtomicInteger(200);
    private final AtomicReference<String> clusterRepoPutBody = new AtomicReference<>();
    private final AtomicInteger upgradeCode = new AtomicInteger(200);
    private final AtomicInteger upgradeCalls = new AtomicInteger();
    private final AtomicInteger upgradeStaleIndexLeft = new AtomicInteger();
    private final AtomicReference<String> upgradeErrorBody = new AtomicReference<>(
            "{\"message\":\"no chart version found for nginx-0.1.11\"}");
    private final AtomicInteger clusterRepoGets = new AtomicInteger();
    private final AtomicReference<String> clusterRepoPollState = new AtomicReference<>("settled");
    private final AtomicReference<String> clusterReposListBody = new AtomicReference<>();
    private final AtomicBoolean releaseExists = new AtomicBoolean(false);
    private final AtomicInteger namespaceGetCode = new AtomicInteger(200);
    private final AtomicInteger namespacePostCode = new AtomicInteger(201);
    private final AtomicReference<String> namespaceGetBody = new AtomicReference<>(NS_IN_PROJECT);
    private final AtomicInteger helmAppGets = new AtomicInteger();
    private final AtomicInteger helmAppPollGets = new AtomicInteger();
    private final AtomicInteger helmOperationGets = new AtomicInteger();
    private final AtomicInteger helmOperationCode = new AtomicInteger(200);
    private final AtomicReference<String> helmOperationBody = new AtomicReference<>();
    private final AtomicInteger helmOperationLogGets = new AtomicInteger();
    private final AtomicInteger helmOperationLogCode = new AtomicInteger(200);
    private final AtomicReference<String> helmOperationLogBody = new AtomicReference<>();
    private final AtomicInteger deploymentLists = new AtomicInteger();
    private final AtomicInteger statefulSetLists = new AtomicInteger();
    private final AtomicInteger podLists = new AtomicInteger();
    private final AtomicInteger podsCode = new AtomicInteger(200);
    private final AtomicReference<String> helmPollState = new AtomicReference<>("deployed");
    private final AtomicReference<String> helmExistsBody = new AtomicReference<>();
    private final AtomicReference<String> deploymentListBody = new AtomicReference<>();
    private final AtomicReference<String> statefulSetListBody = new AtomicReference<>();
    private final AtomicReference<String> podsBody = new AtomicReference<>();

    private static final String APP_EXISTS_NAME_ONLY =
            "{\"metadata\":{\"name\":\"demo-nginx\"}}";
    private static final String REL_DEPLOYMENT_ACTIVE =
            "{\"toId\":\"default/demo-nginx\",\"toType\":\"apps.deployment\","
                    + "\"rel\":\"helmresource\",\"state\":\"active\","
                    + "\"message\":\"Deployment is available. Replicas: 1\"}";
    private static final String REL_DEPLOYMENT_UPDATING =
            "{\"toId\":\"default/demo-nginx\",\"toType\":\"apps.deployment\","
                    + "\"rel\":\"helmresource\",\"state\":\"updating\","
                    + "\"message\":\"Deployment does not have minimum availability\"}";
    private static final String APP_DEPLOYED =
            "{\"metadata\":{\"name\":\"demo-nginx\",\"relationships\":[" + REL_DEPLOYMENT_ACTIVE + "]},"
                    + "\"status\":{\"summary\":{\"state\":\"deployed\"}}}";
    private static final String APP_TRANSITIONING =
            "{\"metadata\":{\"name\":\"demo-nginx\"},\"status\":{\"summary\":{\"state\":\"transitioning\"}}}";
    private static final String APP_FAILED =
            "{\"metadata\":{\"name\":\"demo-nginx\"},\"status\":{\"summary\":{\"state\":\"failed\",\"message\":\"exit 123\"}}}";
    private static final String EMPTY_WORKLOADS = "{\"data\":[]}";
    private static final String APP_PENDING_UPGRADE =
            "{\"metadata\":{\"name\":\"demo-nginx\"},\"status\":{\"summary\":{\"state\":\"pending-upgrade\"}},"
                    + "\"relationships\":[" + REL_DEPLOYMENT_UPDATING + "]}";
    private static final String APP_DEPLOYED_WORKLOAD_NOT_READY =
            "{\"metadata\":{\"name\":\"demo-nginx\",\"relationships\":[" + REL_DEPLOYMENT_UPDATING + "]},"
                    + "\"status\":{\"summary\":{\"state\":\"deployed\"}}}";
    private static final String APP_FAILED_WORKLOAD_NOT_READY =
            "{\"metadata\":{\"name\":\"demo-nginx\"},\"status\":{\"summary\":{\"state\":\"failed\",\"message\":\"exit 123\"}},"
                    + "\"relationships\":[" + REL_DEPLOYMENT_UPDATING + "]}";
    private static final String PODS_IMAGE_PULL =
            "{\"data\":[{\"metadata\":{\"name\":\"demo-nginx-abc\",\"labels\":{"
                    + "\"app.kubernetes.io/instance\":\"demo-nginx\"}},"
                    + "\"status\":{\"containerStatuses\":[{\"name\":\"nginx\",\"state\":{\"waiting\":{"
                    + "\"reason\":\"ImagePullBackOff\","
                    + "\"message\":\"Back-off pulling image \\\"registry.example/nginx:alpine\\\"\"}}}]}}]}";
    private static final String CHART_ACTION_JSON =
            "{\"type\":\"chartActionOutput\",\"operationName\":\"helm-operation-test\","
                    + "\"operationNamespace\":\"default\"}";
    private static final String OPERATION_ACTIVE =
            "{\"metadata\":{\"state\":{\"error\":false,\"transitioning\":false,\"name\":\"active\"}}}";
    private static final String OPERATION_ERROR =
            "{\"metadata\":{\"state\":{\"error\":true,\"transitioning\":false,\"name\":\"error\","
                    + "\"message\":\"exit code: 123\"}}}";
    private static final String HELM_OPERATION_LOG =
            "Error: UPGRADE FAILED: another operation in progress\n";

    @BeforeEach
    public void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        installCalled.set(false);
        installCalls.set(0);
        installStaleIndexLeft.set(0);
        upgradeCalled.set(false);
        uninstallCalled.set(false);
        uninstallCalls.set(0);
        projectsCalled.set(false);
        nsPosted.set(false);
        nsQuotaPosts.set(0);
        refreshCalled.set(false);
        refreshCode.set(200);
        clusterRepoPutBody.set(null);
        upgradeCode.set(200);
        upgradeCalls.set(0);
        upgradeStaleIndexLeft.set(0);
        clusterRepoGets.set(0);
        clusterRepoPollState.set("settled");
        clusterReposListBody.set(CLUSTERREPOS_JSON);
        releaseExists.set(false);
        lastBody.set(null);
        lastNsCreateBody.set(null);
        lastPath.set(null);
        lastMethod.set(null);
        namespaceGetCode.set(200);
        namespacePostCode.set(201);
        namespaceGetBody.set(NS_IN_PROJECT);
        helmAppGets.set(0);
        helmAppPollGets.set(0);
        helmOperationGets.set(0);
        helmOperationCode.set(200);
        helmOperationBody.set(OPERATION_ACTIVE);
        helmOperationLogGets.set(0);
        helmOperationLogCode.set(200);
        helmOperationLogBody.set(HELM_OPERATION_LOG);
        deploymentLists.set(0);
        statefulSetLists.set(0);
        podLists.set(0);
        podsCode.set(200);
        helmPollState.set("deployed");
        helmExistsBody.set(APP_EXISTS_NAME_ONLY);
        deploymentListBody.set(EMPTY_WORKLOADS);
        statefulSetListBody.set(EMPTY_WORKLOADS);
        podsBody.set(EMPTY_WORKLOADS);
        GitRepositoryFiles.testOverride.set(null);
        System.setProperty(HelmAppStates.POLL_INTERVAL_MS_PROP, "10");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            lastPath.set(path + (query == null ? "" : "?" + query));
            lastMethod.set(exchange.getRequestMethod());
            if (path.contains("/v3/clusters/") && path.endsWith("/projects")) {
                projectsCalled.set(true);
                respond(exchange, 200, PROJECTS_JSON);
                return;
            }
            if (path.contains("/v3/clusters")) {
                respond(exchange, 200, "{\"id\":\"local\",\"name\":\"local\"}");
                return;
            }
            if (path.contains("clusterrepos")) {
                if ("PUT".equalsIgnoreCase(exchange.getRequestMethod()) && clusterRepoNamedPath(path)) {
                    refreshCalled.set(true);
                    String putBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    clusterRepoPutBody.set(putBody);
                    int code = refreshCode.get();
                    respond(exchange, code, code == 200 ? putBody : "{\"message\":\"Unauthorized\"}");
                    return;
                }
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    if (clusterRepoNamedPath(path)) {
                        clusterRepoGets.incrementAndGet();
                        if ("transitioning".equals(clusterRepoPollState.get()) && clusterRepoGets.get() > 1) {
                            respond(exchange, 200, CLUSTERREPO_TRANSITIONING);
                            return;
                        }
                        respond(exchange, 200, clusterRepoGets.get() == 1 ? CLUSTERREPO_BEFORE : CLUSTERREPO_SETTLED);
                        return;
                    }
                    respond(exchange, 200, clusterReposListBody.get());
                    return;
                }
            }
            if (path.contains("catalog.cattle.io.operations") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (path.endsWith("/logs")) {
                    helmOperationLogGets.incrementAndGet();
                    int code = helmOperationLogCode.get();
                    respond(exchange, code, code == 200 ? helmOperationLogBody.get() : "{\"message\":\"Unauthorized\"}");
                    return;
                }
                helmOperationGets.incrementAndGet();
                int code = helmOperationCode.get();
                respond(exchange, code, code == 200 ? helmOperationBody.get() : "{\"message\":\"Unauthorized\"}");
                return;
            }
            if (path.contains("catalog.cattle.io.apps/") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                helmAppGets.incrementAndGet();
                boolean afterCatalog = upgradeCalled.get() || installCalled.get();
                if (!afterCatalog) {
                    if (releaseExists.get()) {
                        respond(exchange, 200, helmExistsBody.get());
                    } else {
                        respond(exchange, 404, "{\"message\":\"not found\"}");
                    }
                    return;
                }
                helmAppPollGets.incrementAndGet();
                String mode = helmPollState.get();
                if ("failed".equals(mode)) {
                    respond(exchange, 200, APP_FAILED);
                    return;
                }
                if ("failed-not-ready".equals(mode)) {
                    respond(exchange, 200, APP_FAILED_WORKLOAD_NOT_READY);
                    return;
                }
                if ("pending-upgrade".equals(mode)) {
                    respond(exchange, 200, APP_PENDING_UPGRADE);
                    return;
                }
                if ("deployed-not-ready".equals(mode)) {
                    respond(exchange, 200, APP_DEPLOYED_WORKLOAD_NOT_READY);
                    return;
                }
                if ("transitioning".equals(mode)) {
                    respond(exchange, 200, APP_TRANSITIONING);
                    return;
                }
                if ("transition-then-deployed".equals(mode) && helmAppPollGets.get() == 1) {
                    respond(exchange, 200, APP_TRANSITIONING);
                    return;
                }
                if ("stale-failed-then-deployed".equals(mode) && helmAppPollGets.get() == 1) {
                    respond(exchange, 200, APP_FAILED);
                    return;
                }
                respond(exchange, 200, APP_DEPLOYED);
                return;
            }
            if (path.contains("/v1/pods/") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                podLists.incrementAndGet();
                int code = podsCode.get();
                respond(exchange, code, code == 200 ? podsBody.get() : "{\"message\":\"Unauthorized\"}");
                return;
            }
            if (path.contains("/v1/apps.deployments/") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                deploymentLists.incrementAndGet();
                respond(exchange, 200, deploymentListBody.get());
                return;
            }
            if (path.contains("/v1/apps.statefulsets/") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                statefulSetLists.incrementAndGet();
                respond(exchange, 200, statefulSetListBody.get());
                return;
            }
            if (query != null && query.contains("action=uninstall")) {
                uninstallCalled.set(true);
                uninstallCalls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 200, "{}");
                return;
            }
            if (query != null && query.contains("action=upgrade")) {
                upgradeCalled.set(true);
                upgradeCalls.incrementAndGet();
                lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (upgradeStaleIndexLeft.get() > 0) {
                    upgradeStaleIndexLeft.decrementAndGet();
                    respond(exchange, 500, upgradeErrorBody.get());
                    return;
                }
                int code = upgradeCode.get();
                respond(exchange, code, code == 200 ? CHART_ACTION_JSON : upgradeErrorBody.get());
                return;
            }
            if (query != null && query.contains("action=install")) {
                installCalled.set(true);
                installCalls.incrementAndGet();
                lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (installStaleIndexLeft.get() > 0) {
                    installStaleIndexLeft.decrementAndGet();
                    respond(exchange, 500, upgradeErrorBody.get());
                    return;
                }
                respond(exchange, 201, CHART_ACTION_JSON);
                return;
            }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && path.endsWith("/v1/namespaces")) {
                nsPosted.set(true);
                lastNsCreateBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int code = namespacePostCode.get();
                if (code >= 200 && code < 300) {
                    respond(exchange, code, "{\"metadata\":{\"name\":\"default\"}}");
                } else {
                    respond(exchange, code, "{\"message\":\"Method POST not supported\"}");
                }
                return;
            }
            if (path.contains("/v1/namespaces/") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                int code = namespaceGetCode.get();
                respond(exchange, code, code == 200 ? namespaceGetBody.get() : "{\"message\":\"not found\"}");
                return;
            }
            if (path.contains("/resourcequotas") || path.contains("/limitranges")) {
                nsQuotaPosts.incrementAndGet();
                respond(exchange, 201, "{}");
                return;
            }
            respond(exchange, 404, "{\"message\":\"not found\"}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    public void stopServer() {
        GitRepositoryFiles.testOverride.set(null);
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
        System.clearProperty(HelmAppStates.POLL_INTERVAL_MS_PROP);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void configRoundtrip_inheritNone(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(minimalHelmStep());

        jenkins.configRoundtrip(project);
        RancherHelmBuilder loaded = project.getBuildersList().get(RancherHelmBuilder.class);
        assertEquals("local", loaded.getClusterId());
        assertEquals("demo-nginx", loaded.getReleaseName());
        assertEquals("nginx", loaded.getChart());
        assertEquals("https://charts.example/helm", loaded.getRepo());
        assertEquals("mnp", loaded.getProject());
        assertEquals(RancherHelmBuilder.MODE_INHERIT, loaded.getRancherConnectionMode());
        assertEquals(RancherHelmBuilder.VALUES_NONE, loaded.getValuesSource());
        assertNull(loaded.getValuesOverlay());
    }

    @Test
    public void configRoundtrip_manualYaml(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "rancher-manual-token",
                        "manual",
                        Secret.fromString("manual-secret")));
        SystemCredentialsProvider.getInstance().save();

        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setRancherConnectionMode(RancherHelmBuilder.MODE_MANUAL);
        step.setRancherUrl("https://rancher.example");
        step.setRancherCredentialsId("rancher-manual-token");
        step.setValuesSource(RancherHelmBuilder.VALUES_YAML);
        step.setValues("replicaCount: 1\n");
        project.getBuildersList().add(step);

        jenkins.configRoundtrip(project);
        RancherHelmBuilder loaded = project.getBuildersList().get(RancherHelmBuilder.class);
        assertEquals(RancherHelmBuilder.MODE_MANUAL, loaded.getRancherConnectionMode());
        assertEquals("https://rancher.example", loaded.getRancherUrl());
        assertEquals("rancher-manual-token", loaded.getRancherCredentialsId());
        assertEquals(RancherHelmBuilder.VALUES_YAML, loaded.getValuesSource());
        assertTrue(loaded.getValues().contains("replicaCount: 1"));
    }

    @Test
    public void configRoundtrip_valuesOverlay(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setValuesOverlay("image:\n  tag: v2\n");
        project.getBuildersList().add(step);

        jenkins.configRoundtrip(project);
        RancherHelmBuilder loaded = project.getBuildersList().get(RancherHelmBuilder.class);
        assertTrue(loaded.getValuesOverlay().contains("tag: v2"));
    }

    @Test
    public void legacyXml_withoutValuesOverlay_loadsEmpty(JenkinsRule jenkins) {
        String xml =
                "<io.jenkins.plugins.ranchermanager.RancherHelmBuilder>"
                        + "<clusterId>local</clusterId>"
                        + "<releaseName>demo-nginx</releaseName>"
                        + "<chart>nginx</chart>"
                        + "<repo>https://charts.example/helm</repo>"
                        + "<project>mnp</project>"
                        + "<namespace>default</namespace>"
                        + "<valuesSource>none</valuesSource>"
                        + "</io.jenkins.plugins.ranchermanager.RancherHelmBuilder>";
        assertNotNull(jenkins.jenkins);
        Object loaded = hudson.model.Items.XSTREAM.fromXML(xml);
        RancherHelmBuilder step = assertInstanceOf(RancherHelmBuilder.class, loaded);
        assertEquals("local", step.getClusterId());
        assertNull(step.getValuesOverlay());
        assertEquals(RancherHelmBuilder.VALUES_NONE, step.getValuesSource());
    }

    @Test
    public void configRoundtrip_repositoryValues(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setValuesSource(RancherHelmBuilder.VALUES_REPOSITORY);
        step.setValuesRepositoryUrl("https://gitlab.example/group/helm-values.git");
        step.setValuesFilePath("charts/values.yaml");
        step.setValuesRepositoryReferenceName("refs/heads/main");
        project.getBuildersList().add(step);

        jenkins.configRoundtrip(project);
        RancherHelmBuilder loaded = project.getBuildersList().get(RancherHelmBuilder.class);
        assertEquals(RancherHelmBuilder.VALUES_REPOSITORY, loaded.getValuesSource());
        assertEquals("https://gitlab.example/group/helm-values.git", loaded.getValuesRepositoryUrl());
        assertEquals("charts/values.yaml", loaded.getValuesFilePath());
        assertEquals("refs/heads/main", loaded.getValuesRepositoryReferenceName());
    }

    @Test
    public void freestyle_installWhenMissing(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("ClusterRepo=charts-example", build);
        jenkins.assertLogContains("Summary outcome=installed", build);
        jenkins.assertLogContains("Helm deployment demo-nginx: active", build);
        assertNoClusterRepoRefresh();
        assertEquals(1, upgradeCalls.get());
        assertTrue(upgradeCalled.get());
        assertFalse(installCalled.get());
        String body = lastBody.get();
        assertTrue(body != null && body.contains("\"install\":true"));
        assertTrue(body.contains("local:p-abc12"));
        assertTrue(body.contains("\"projectId\""));
        assertFalse(body.contains("replicaCount"));
        assertFalse(nsPosted.get());
        assertTrue(helmAppPollGets.get() >= 1);
        assertEquals(0, deploymentLists.get());
        assertEquals(0, statefulSetLists.get());
        assertEquals(0, podLists.get());
    }

    @Test
    public void freestyle_upgradeWhenExists(JenkinsRule jenkins) throws Exception {
        releaseExists.set(true);
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Summary outcome=upgraded", build);
        assertNoClusterRepoRefresh();
        assertEquals(1, upgradeCalls.get());
        assertTrue(upgradeCalled.get());
        assertTrue(lastBody.get().contains("\"projectId\""));
        assertTrue(helmAppPollGets.get() >= 1);
    }

    @Test
    public void freestyle_yamlValues_omitsBodyInLog(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setValuesSource(RancherHelmBuilder.VALUES_YAML);
        step.setValues("replicaCount: 1\n");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("replicaCount", build);
        jenkins.assertLogContains("valuesOverlay=false", build);
        assertTrue(upgradeCalled.get());
        assertTrue(lastBody.get().contains("replicaCount"));
        assertTrue(lastBody.get().contains("local:p-abc12"));
        assertTrue(helmAppPollGets.get() >= 1);
    }

    @Test
    public void freestyle_nonePlusOverlay_putsValuesInCatalogBody(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setValuesSource(RancherHelmBuilder.VALUES_NONE);
        step.setValuesOverlay(
                "backend:\n  image:\n    tag: \"v11-dev-abc\"\n");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("valuesOverlay=true", build);
        jenkins.assertLogNotContains("v11-dev-abc", build);
        assertTrue(upgradeCalled.get());
        String body = lastBody.get();
        assertTrue(body.contains("\"tag\":\"v11-dev-abc\"") || body.contains("\"tag\" : \"v11-dev-abc\""));
        assertTrue(body.contains("backend"));
    }

    @Test
    public void freestyle_yamlPlusOverlay_deepMerge(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setValuesSource(RancherHelmBuilder.VALUES_YAML);
        step.setValues("replicaCount: 3\nimage:\n  tag: base\n");
        step.setValuesOverlay("image:\n  tag: overlay\n");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("valuesOverlay=true", build);
        jenkins.assertLogNotContains("tag: overlay", build);
        jenkins.assertLogNotContains("\"tag\":\"overlay\"", build);
        String body = lastBody.get();
        assertTrue(body.contains("\"replicaCount\":3") || body.contains("\"replicaCount\": 3"));
        assertTrue(body.contains("\"tag\":\"overlay\"") || body.contains("\"tag\" : \"overlay\""));
        assertFalse(body.contains("\"tag\":\"base\"") || body.contains("\"tag\" : \"base\""));
    }

    @Test
    public void freestyle_repositoryPlusOverlay_deepMerge(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        GitRepositoryFiles.testOverride.set(req -> "replicaCount: 2\nimage:\n  tag: git\n");
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setValuesSource(RancherHelmBuilder.VALUES_REPOSITORY);
        step.setValuesRepositoryUrl("https://gitlab.example/group/values.git");
        step.setValuesOverlay("image:\n  tag: from-ci\n");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogNotContains("from-ci", build);
        String body = lastBody.get();
        assertTrue(body.contains("replicaCount"));
        assertTrue(body.contains("from-ci"));
        assertFalse(body.contains("\"tag\":\"git\"") || body.contains("\"tag\" : \"git\""));
    }

    @Test
    public void badOverlay_abortsBeforeCatalog(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setValuesOverlay("---\n- not\n- a\n- mapping\n");
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("mapping", build);
        assertFalse(upgradeCalled.get());
    }

    @Test
    public void validateOnly_withOverlay_skipsCatalog(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setValidateOnly(true);
        step.setValuesOverlay("image:\n  tag: only-validate\n");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Summary outcome=validated", build);
        jenkins.assertLogContains("valuesOverlay=true", build);
        jenkins.assertLogNotContains("only-validate", build);
        assertFalse(upgradeCalled.get());
        assertFalse(installCalled.get());
    }

    @Test
    public void validateOnly_skipsHelmMutationsAndProjectLookup(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setValidateOnly(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Validate-only", build);
        jenkins.assertLogContains("Summary outcome=validated", build);
        assertFalse(upgradeCalled.get());
        assertFalse(installCalled.get());
        assertFalse(uninstallCalled.get());
        assertFalse(projectsCalled.get());
        assertFalse(nsPosted.get());
        assertEquals(0, helmAppGets.get());
        assertEquals(0, helmOperationGets.get());
        assertEquals(0, helmOperationLogGets.get());
        assertEquals(0, deploymentLists.get());
        assertNoClusterRepoRefresh();
        assertEquals(0, podLists.get());
    }

    @Test
    public void missingClusterRepo_abortsWithoutRefresh(JenkinsRule jenkins) throws Exception {
        clusterReposListBody.set("{\"data\":[]}");
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("No Rancher ClusterRepo matches", build);
        jenkins.assertLogNotContains("Helm operation failed:", build);
        jenkins.assertLogNotContains("[ERROR] No Rancher ClusterRepo", build);
        assertNoClusterRepoRefresh();
        assertFalse(upgradeCalled.get());
    }

    @Test
    public void ensureNamespaceForbidden_abortsWithoutHelmPrefix(JenkinsRule jenkins) throws Exception {
        namespaceGetCode.set(404);
        namespacePostCode.set(403);
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("Cannot ensure namespace", build);
        jenkins.assertLogContains("token lacks permission", build);
        jenkins.assertLogNotContains("Helm operation failed:", build);
        jenkins.assertLogNotContains("[ERROR] Cannot ensure namespace", build);
        assertTrue(nsPosted.get());
        assertFalse(upgradeCalled.get());
    }

    @Test
    public void ociChartRepo_installsWhenClusterRepoMatches(JenkinsRule jenkins) throws Exception {
        clusterReposListBody.set(
                "{\"data\":[{\"metadata\":{\"name\":\"oci-local\"},"
                        + "\"spec\":{\"url\":\"oci://localhost:5000/charts\"}}]}");
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = new RancherHelmBuilder(
                "local", "demo-nginx", "nginx", "oci://localhost:5000/charts/");
        step.setProject("mnp");
        step.setNamespace("default");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("ClusterRepo=oci-local", build);
        jenkins.assertLogContains("Summary outcome=installed", build);
        assertTrue(upgradeCalled.get() || installCalled.get());
        assertNoClusterRepoRefresh();
    }

    @Test
    public void ociChartRepo_validateOnly_acceptsScheme(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = new RancherHelmBuilder(
                "local", "demo-nginx", "nginx", "oci://localhost:5000/charts");
        step.setProject("mnp");
        step.setValidateOnly(true);
        step.setVerboseLogging(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Summary outcome=validated", build);
        jenkins.assertLogContains("oci://localhost:5000/charts", build);
        assertFalse(upgradeCalled.get());
        assertFalse(projectsCalled.get());
    }

    @Test
    public void clusterRepoRefreshUnauthorized_abortsAfterMissingChart(JenkinsRule jenkins) throws Exception {
        upgradeCode.set(500);
        refreshCode.set(401);
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("token cannot update clusterrepos", build);
        assertTrue(refreshCalled.get());
        assertEquals(1, upgradeCalls.get());
        jenkins.assertLogNotContains("Summary outcome=installed", build);
    }

    @Test
    public void clusterRepoRefreshTimeout_abortsAfterMissingChart(JenkinsRule jenkins) throws Exception {
        upgradeCode.set(500);
        clusterRepoPollState.set("transitioning");
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setWaitTimeoutSeconds("1");
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("did not refresh", build);
        assertTrue(refreshCalled.get());
        assertEquals(1, upgradeCalls.get());
        jenkins.assertLogNotContains("Summary outcome=installed", build);
    }

    @Test
    public void clusterRepoRefreshOnMissingChart_retriesUpgrade(JenkinsRule jenkins) throws Exception {
        upgradeStaleIndexLeft.set(1);
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Summary outcome=installed", build);
        assertTrue(refreshCalled.get());
        assertTrue(clusterRepoGets.get() >= 2);
        String put = clusterRepoPutBody.get();
        assertTrue(put != null && put.contains("\"forceUpdate\""));
        assertEquals(2, upgradeCalls.get());
        assertTrue(helmAppPollGets.get() >= 1);
    }

    @Test
    public void clusterRepoIndexMissingChart_mapsCatalogError(JenkinsRule jenkins) throws Exception {
        upgradeCode.set(500);
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setVersion("0.1.11");
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("has no chart \"nginx\" version 0.1.11 in its index after refresh", build);
        assertTrue(refreshCalled.get());
        assertEquals(2, upgradeCalls.get());
        jenkins.assertLogNotContains("Summary outcome=installed", build);
    }

    @Test
    public void clusterRepoOtherCatalogError_doesNotRefresh(JenkinsRule jenkins) throws Exception {
        upgradeCode.set(500);
        upgradeErrorBody.set("{\"message\":\"cluster unavailable\"}");
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("cluster unavailable", build);
        jenkins.assertLogNotContains("after refresh", build);
        assertNoClusterRepoRefresh();
        assertEquals(1, upgradeCalls.get());
        jenkins.assertLogNotContains("Summary outcome=installed", build);
    }

    @Test
    public void clusterRepoMissingRelease_installsWithoutRefresh(JenkinsRule jenkins) throws Exception {
        upgradeCode.set(404);
        upgradeErrorBody.set("{\"message\":\"not found\"}");
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Summary outcome=installed", build);
        assertNoClusterRepoRefresh();
        assertEquals(1, upgradeCalls.get());
        assertEquals(1, installCalls.get());
        assertTrue(lastBody.get().contains("\"projectId\""));
    }

    @Test
    public void clusterRepoForceReinstall_missingChart_retriesInstall(JenkinsRule jenkins) throws Exception {
        releaseExists.set(true);
        installStaleIndexLeft.set(1);
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setForceReinstall(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Summary outcome=reinstalled", build);
        assertTrue(refreshCalled.get());
        assertTrue(clusterRepoGets.get() >= 2);
        String put = clusterRepoPutBody.get();
        assertTrue(put != null && put.contains("\"forceUpdate\""));
        assertEquals(1, uninstallCalls.get());
        assertEquals(2, installCalls.get());
        assertEquals(0, upgradeCalls.get());
        assertTrue(helmAppPollGets.get() >= 1);
    }

    @Test
    public void missingProject_abortsBeforeRancherMutations(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = new RancherHelmBuilder(
                "local", "demo-nginx", "nginx", "https://charts.example/helm");
        step.setValuesSource(RancherHelmBuilder.VALUES_NONE);
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("Project is required", build);
        assertFalse(upgradeCalled.get());
        assertFalse(projectsCalled.get());
    }

    @Test
    public void clusterPrefixedProject_aborts(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setProject("local:p-abc12");
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("Cluster belongs in Cluster ID", build);
        assertFalse(projectsCalled.get());
    }

    @Test
    public void missingNamespace_ensureOff_abortsWithoutCreate(JenkinsRule jenkins) throws Exception {
        namespaceGetCode.set(404);
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setEnsureNamespace(false);
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("does not exist", build);
        assertFalse(nsPosted.get());
        assertFalse(upgradeCalled.get());
    }

    @Test
    public void missingNamespace_ensureOn_createsInProject(JenkinsRule jenkins) throws Exception {
        namespaceGetCode.set(404);
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Summary outcome=installed", build);
        jenkins.assertLogContains("Namespace ready name=default result=created", build);
        jenkins.assertLogNotContains("ResourceQuota", build);
        jenkins.assertLogNotContains("LimitRange", build);
        assertTrue(nsPosted.get());
        assertEquals(0, nsQuotaPosts.get());
        assertTrue(lastNsCreateBody.get().contains("local:p-abc12"));
        assertTrue(lastNsCreateBody.get().contains("field.cattle.io/projectId"));
        assertTrue(lastBody.get().contains("\"projectId\""));
        assertTrue(helmAppPollGets.get() >= 1);
    }

    @Test
    public void existingNamespaceWrongProject_abortsWithoutMove(JenkinsRule jenkins) throws Exception {
        namespaceGetBody.set(
                "{\"metadata\":{\"name\":\"default\",\"annotations\":{"
                        + "\"field.cattle.io/projectId\":\"local:p-other\"}}}");
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("is not in project", build);
        assertFalse(nsPosted.get());
        assertFalse(upgradeCalled.get());
    }

    @Test
    public void freestyle_repositoryValues(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        GitRepositoryFiles.testOverride.set(req -> {
            assertTrue(req.repositoryUrl.contains("gitlab.example"));
            return "replicaCount: 2\n";
        });
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setValuesSource(RancherHelmBuilder.VALUES_REPOSITORY);
        step.setValuesRepositoryUrl("https://gitlab.example/group/values.git");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Values source=repository", build);
        assertTrue(upgradeCalled.get());
        assertTrue(lastBody.get().contains("replicaCount"));
        assertTrue(helmAppPollGets.get() >= 1);
    }

    @Test
    public void freestyle_forceReinstall(JenkinsRule jenkins) throws Exception {
        releaseExists.set(true);
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setForceReinstall(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Summary outcome=reinstalled", build);
        assertNoClusterRepoRefresh();
        assertEquals(1, uninstallCalls.get());
        assertEquals(1, installCalls.get());
        assertTrue(lastBody.get().contains("\"projectId\""));
        assertTrue(helmAppPollGets.get() >= 1);
    }

    @Test
    public void wait_transitionThenDeployed_succeeds(JenkinsRule jenkins) throws Exception {
        helmPollState.set("transition-then-deployed");
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Summary outcome=installed", build);
        assertTrue(helmAppPollGets.get() >= 2);
        assertEquals(0, podLists.get());
        jenkins.assertLogContains("Helm app state=transitioning", build);
        jenkins.assertLogContains("Helm app state=deployed", build);
    }

    @Test
    public void wait_staleFailedThenDeployed_succeeds(JenkinsRule jenkins) throws Exception {
        releaseExists.set(true);
        helmExistsBody.set(APP_FAILED);
        helmPollState.set("stale-failed-then-deployed");
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Summary outcome=upgraded", build);
        assertTrue(helmAppPollGets.get() >= 2);
        assertEquals(0, podLists.get());
        jenkins.assertLogNotContains("Helm app state=failed", build);
        jenkins.assertLogContains("Helm app state=deployed", build);
    }

    @Test
    public void wait_failedApp_aborts(JenkinsRule jenkins) throws Exception {
        helmPollState.set("failed");
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("Helm app \"demo-nginx\" failed", build);
        jenkins.assertLogContains("cluster serving", build);
        jenkins.assertLogContains("Helm app state=failed", build);
        jenkins.assertLogNotContains("Summary outcome=installed", build);
        assertTrue(upgradeCalled.get());
        assertTrue(helmAppPollGets.get() >= 1);
        assertEquals(0, podLists.get());
    }

    @Test
    public void wait_pendingUpgrade_timesOutWithPodReason(JenkinsRule jenkins) throws Exception {
        helmPollState.set("pending-upgrade");
        podsBody.set(PODS_IMAGE_PULL);
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setWaitTimeoutSeconds("1");
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("did not become ready", build);
        jenkins.assertLogContains("state=pending-upgrade", build);
        jenkins.assertLogContains("demo-nginx", build);
        jenkins.assertLogContains("ImagePullBackOff", build);
        jenkins.assertLogContains("Helm app state=pending-upgrade", build);
        jenkins.assertLogContains("Helm deployment demo-nginx: updating", build);
        assertTrue(upgradeCalled.get());
        assertTrue(helmAppPollGets.get() >= 1);
        assertEquals(1, podLists.get());
        assertEquals(0, deploymentLists.get());
        jenkins.assertLogNotContains("Summary outcome=installed", build);
    }

    @Test
    public void wait_podsUnauthorized_abortsBeforeSuccess(JenkinsRule jenkins) throws Exception {
        helmPollState.set("pending-upgrade");
        podsCode.set(401);
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setWaitTimeoutSeconds("1");
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("token cannot list pods", build);
        assertEquals(1, podLists.get());
        jenkins.assertLogNotContains("Summary outcome=installed", build);
    }

    @Test
    public void waitTimeoutZero_abortsOnParse(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setWaitTimeoutSeconds("0");
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("Settle timeout must be a positive number of seconds", build);
        jenkins.assertLogNotContains("Wait timeout must be", build);
        assertFalse(upgradeCalled.get());
        assertEquals(0, helmAppGets.get());
        assertEquals(0, helmOperationGets.get());
        assertEquals(0, helmOperationLogGets.get());
    }

    @Test
    public void helmTimeoutZero_abortsOnParse(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setHelmWait(true);
        step.setHelmTimeoutSeconds("0");
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("Helm timeout must be a positive number of seconds", build);
        jenkins.assertLogNotContains("Settle timeout must be", build);
        jenkins.assertLogNotContains("Wait timeout must be", build);
        assertFalse(upgradeCalled.get());
    }

    @Test
    public void legacyAtomic_migratesToWaitCleanupAndTimeout(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setAtomic(true);
        step.setWaitTimeoutSeconds("120");
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Summary outcome=installed", build);
        String body = lastBody.get();
        assertTrue(body.contains("\"wait\":true"));
        assertTrue(body.contains("\"timeout\":\"120s\""));
        assertTrue(body.contains("\"cleanupOnFail\":true"));
        assertFalse(body.contains("\"atomic\":true"));
        assertTrue(helmAppPollGets.get() >= 1);
    }

    @Test
    public void legacyAtomicXml_readResolveMigrates(JenkinsRule jenkins) {
        String xml =
                "<io.jenkins.plugins.ranchermanager.RancherHelmBuilder>"
                        + "<clusterId>local</clusterId>"
                        + "<releaseName>demo-nginx</releaseName>"
                        + "<chart>nginx</chart>"
                        + "<repo>https://charts.example/helm</repo>"
                        + "<project>mnp</project>"
                        + "<namespace>default</namespace>"
                        + "<atomic>true</atomic>"
                        + "<waitTimeoutSeconds>120</waitTimeoutSeconds>"
                        + "</io.jenkins.plugins.ranchermanager.RancherHelmBuilder>";
        assertNotNull(jenkins.jenkins);
        Object loaded = hudson.model.Items.XSTREAM.fromXML(xml);
        RancherHelmBuilder step = assertInstanceOf(RancherHelmBuilder.class, loaded);
        assertEquals(Boolean.TRUE, step.getHelmWait());
        assertEquals(Boolean.TRUE, step.getCleanupOnFail());
        assertEquals("120", step.getHelmTimeoutSeconds());
        assertFalse(step.isAtomic());
    }

    @Test
    public void newAtomic_withExplicitHelmWait_independentBody(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setHelmWait(true);
        step.setHelmTimeoutSeconds("90");
        step.setAtomic(true);
        job.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(job);
        String body = lastBody.get();
        assertTrue(body.contains("\"wait\":true"));
        assertTrue(body.contains("\"timeout\":\"90s\""));
        assertTrue(body.contains("\"atomic\":true"));
        assertFalse(body.contains("\"cleanupOnFail\":true"));
    }

    @Test
    public void cleanupOnFailAlone_inCatalogBody(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setCleanupOnFail(true);
        job.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(job);
        String body = lastBody.get();
        assertTrue(body.contains("\"cleanupOnFail\":true"));
        assertFalse(body.contains("\"wait\":true"));
        assertFalse(body.contains("\"atomic\":true"));
    }

    @Test
    public void settleOnly_omitsCatalogWaitFlags(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        jenkins.buildAndAssertSuccess(job);
        String body = lastBody.get();
        assertFalse(body.contains("\"wait\""));
        assertFalse(body.contains("\"timeout\""));
        assertFalse(body.contains("\"atomic\""));
        assertFalse(body.contains("cleanupOnFail"));
        assertTrue(helmAppPollGets.get() >= 1);
    }

    @Test
    public void helmWaitWithoutTimeout_aborts(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setHelmWait(true);
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("Helm timeout", build);
        assertFalse(upgradeCalled.get());
    }

    @Test
    public void wait_operationError_abortsWithoutAppPoll(JenkinsRule jenkins) throws Exception {
        helmOperationBody.set(OPERATION_ERROR);
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("Helm operation \"helm-operation-test\"", build);
        jenkins.assertLogContains("exit code: 123", build);
        jenkins.assertLogContains("UPGRADE FAILED", build);
        jenkins.assertLogNotContains("Summary outcome=installed", build);
        assertTrue(upgradeCalled.get());
        assertTrue(helmOperationGets.get() >= 1);
        assertEquals(1, helmOperationLogGets.get());
        assertTrue(lastPath.get().endsWith("/logs"));
        assertFalse(lastPath.get().contains("link=logs"));
        assertEquals(0, helmAppPollGets.get());
        assertEquals(0, podLists.get());
    }

    @Test
    public void wait_operationError_longLogTailInBuilderError(JenkinsRule jenkins) throws Exception {
        helmOperationBody.set(OPERATION_ERROR);
        helmOperationLogBody.set(
                "pad ".repeat(RancherClient.MAX_ERROR_DETAIL_CHARS)
                        + "Error: UPGRADE FAILED: values too long at end");
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("exit code: 123", build);
        jenkins.assertLogContains("UPGRADE FAILED", build);
        jenkins.assertLogContains("values too long at end", build);
        assertEquals(1, helmOperationLogGets.get());
        assertEquals(0, helmAppPollGets.get());
    }

    @Test
    public void wait_operationError_emptyLogMarksEmpty(JenkinsRule jenkins) throws Exception {
        helmOperationBody.set(OPERATION_ERROR);
        helmOperationLogBody.set("");
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("exit code: 123", build);
        jenkins.assertLogContains("helm job log was empty", build);
        jenkins.assertLogNotContains("UPGRADE FAILED", build);
        assertEquals(1, helmOperationLogGets.get());
        assertEquals(0, helmAppPollGets.get());
    }

    @Test
    public void wait_operationError_logsHttpStatusInExtras(JenkinsRule jenkins) throws Exception {
        helmOperationBody.set(OPERATION_ERROR);
        helmOperationLogCode.set(400);
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        job.getBuildersList().add(minimalHelmStep());

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("exit code: 123", build);
        jenkins.assertLogContains("cannot read operation logs", build);
        jenkins.assertLogContains("400", build);
        jenkins.assertLogNotContains("UPGRADE FAILED", build);
        assertEquals(1, helmOperationLogGets.get());
        assertEquals(0, helmAppPollGets.get());
    }

    @Test
    public void wait_deployedWorkloadNotReady_aborts(JenkinsRule jenkins) throws Exception {
        helmPollState.set("deployed-not-ready");
        podsBody.set(PODS_IMAGE_PULL);
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setWaitTimeoutSeconds("1");
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("workloads not ready", build);
        jenkins.assertLogContains("demo-nginx", build);
        jenkins.assertLogContains("ImagePullBackOff", build);
        jenkins.assertLogNotContains("Summary outcome=installed", build);
        assertEquals(0, deploymentLists.get());
        assertEquals(1, podLists.get());
    }

    @Test
    public void wait_failedClusterNotServing_aborts(JenkinsRule jenkins) throws Exception {
        helmPollState.set("failed-not-ready");
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setWaitTimeoutSeconds("1");
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("Helm app \"demo-nginx\" failed", build);
        jenkins.assertLogContains("cluster not serving", build);
        jenkins.assertLogContains("demo-nginx", build);
        jenkins.assertLogNotContains("Summary outcome=installed", build);
        assertEquals(1, podLists.get());
    }

    @Test
    public void yamlValuesMissing_abortsBeforeRancher(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setValuesSource(RancherHelmBuilder.VALUES_YAML);
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("Values YAML is required", build);
        assertFalse(upgradeCalled.get());
    }

    @Test
    public void repositoryValuesMissingUrl_aborts(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setValuesSource(RancherHelmBuilder.VALUES_REPOSITORY);
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("Values repository URL is required", build);
        assertFalse(upgradeCalled.get());
    }

    @Test
    public void validateOnly_repositorySource_skipsClone(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject job = jenkins.createFreeStyleProject();
        RancherHelmBuilder step = minimalHelmStep();
        step.setValuesSource(RancherHelmBuilder.VALUES_REPOSITORY);
        step.setValuesRepositoryUrl("https://gitlab.example/group/values.git");
        step.setValidateOnly(true);
        step.setVerboseLogging(true);
        step.setForceReinstall(true);
        job.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Summary outcome=validated", build);
        assertFalse(upgradeCalled.get());
    }

    private void assertNoClusterRepoRefresh() {
        assertFalse(refreshCalled.get());
        assertEquals(0, clusterRepoGets.get());
    }

    private static RancherHelmBuilder minimalHelmStep() {
        RancherHelmBuilder step = new RancherHelmBuilder(
                "local", "demo-nginx", "nginx", "https://charts.example/helm");
        step.setProject("mnp");
        step.setNamespace("default");
        step.setValuesSource(RancherHelmBuilder.VALUES_NONE);
        return step;
    }

    private void configureRancher(JenkinsRule jenkins) throws IOException {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "rancher-api-token",
                        "Rancher token",
                        Secret.fromString("secret-token-value")));
        SystemCredentialsProvider.getInstance().save();

        RancherGlobalConfiguration cfg = RancherGlobalConfiguration.get();
        cfg.setName("prod");
        cfg.setRancherUrl(base);
        cfg.setCredentialsId("rancher-api-token");
        cfg.setConnectTimeoutMs(2000);
        cfg.setReadTimeoutMs(2000);
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
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
