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
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
public class RancherManifestBuilderTest {

    private HttpServer server;
    private String base;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicBoolean applyCalled = new AtomicBoolean();
    private final AtomicBoolean namespaceCalled = new AtomicBoolean();
    private final AtomicBoolean usersHit = new AtomicBoolean();
    private final AtomicReference<String> lastApplyBody = new AtomicReference<>();
    private final Set<String> workloadGetPaths = ConcurrentHashMap.newKeySet();
    private final AtomicInteger podsGets = new AtomicInteger();
    private final AtomicReference<String> deploymentAppsWebBody = new AtomicReference<>();
    private final AtomicReference<String> deploymentAppsWebBodyAfterFirst = new AtomicReference<>();
    private final AtomicReference<String> deploymentOtherApiBody = new AtomicReference<>();
    private final AtomicInteger deploymentAppsWebGets = new AtomicInteger();

    @BeforeEach
    public void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        System.setProperty(HelmAppStates.POLL_INTERVAL_MS_PROP, "20");
        lastPath.set(null);
        lastMethod.set(null);
        lastContentType.set(null);
        applyCalled.set(false);
        namespaceCalled.set(false);
        usersHit.set(false);
        lastApplyBody.set(null);
        workloadGetPaths.clear();
        podsGets.set(0);
        deploymentAppsWebBody.set(activeDeploymentJson(1, 1));
        deploymentAppsWebBodyAfterFirst.set(null);
        deploymentOtherApiBody.set(activeDeploymentJson(1, 1));
        deploymentAppsWebGets.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            lastPath.set(path);
            lastMethod.set(exchange.getRequestMethod());
            if (path.contains("/v3/users")) {
                usersHit.set(true);
                respond(exchange, 200, "{\"username\":\"admin\"}");
                return;
            }
            if (path.contains("/v3/clusters")) {
                respond(exchange, 200, "{\"id\":\"local\",\"name\":\"local\"}");
                return;
            }
            if (query != null && query.contains("action=apply")) {
                applyCalled.set(true);
                lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                lastApplyBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, "{}");
                return;
            }
            if (path.endsWith("/v1/pods/apps") || path.endsWith("/v1/pods/other")
                    || path.endsWith("/v1/pods/default")) {
                podsGets.incrementAndGet();
                respond(exchange, 200, "{\"data\":[{\"metadata\":{\"name\":\"web-0\"},"
                        + "\"status\":{\"phase\":\"Pending\",\"containerStatuses\":[{"
                        + "\"name\":\"app\",\"state\":{\"waiting\":{\"reason\":\"ImagePullBackOff\","
                        + "\"message\":\"pull failed\"}}}]}}]}");
                return;
            }
            if (path.contains("/v1/apps.deployments/")
                    || path.contains("/v1/apps.statefulsets/")
                    || path.contains("/v1/apps.daemonsets/")
                    || path.contains("/v1/batch.jobs/")) {
                workloadGetPaths.add(path);
                if (path.endsWith("/apps.deployments/apps/web")) {
                    int n = deploymentAppsWebGets.incrementAndGet();
                    String afterFirst = deploymentAppsWebBodyAfterFirst.get();
                    if (afterFirst != null && n > 1) {
                        respond(exchange, 200, afterFirst);
                        return;
                    }
                    respond(exchange, 200, deploymentAppsWebBody.get());
                    return;
                }
                if (path.endsWith("/apps.deployments/other/api")) {
                    respond(exchange, 200, deploymentOtherApiBody.get());
                    return;
                }
                respond(exchange, 404, "{\"message\":\"not found\"}");
                return;
            }
            if (path.contains("/v1/namespaces")) {
                namespaceCalled.set(true);
                respond(exchange, 200, "{\"metadata\":{\"name\":\"default\"}}");
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
    public void defaultsToInherit() {
        RancherManifestBuilder step = new RancherManifestBuilder("local");
        assertEquals(RancherManifestBuilder.MODE_INHERIT, step.getRancherConnectionMode());
        assertEquals("300", step.getWaitTimeoutSeconds());
        assertTrue(step.requiresWorkspace());
        step.setManifestSource(RancherManifestBuilder.SOURCE_YAML);
        assertFalse(step.requiresWorkspace());
    }

    @Test
    public void configRoundtrip_inheritYaml(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherManifestBuilder step = minimalYamlStep();
        step.setWaitTimeoutSeconds("120");
        project.getBuildersList().add(step);

        jenkins.configRoundtrip(project);
        RancherManifestBuilder loaded = project.getBuildersList().get(RancherManifestBuilder.class);
        assertEquals("local", loaded.getClusterId());
        assertEquals(RancherManifestBuilder.MODE_INHERIT, loaded.getRancherConnectionMode());
        assertEquals(RancherManifestBuilder.SOURCE_YAML, loaded.getManifestSource());
        assertEquals("120", loaded.getWaitTimeoutSeconds());
        assertTrue(loaded.getManifestYaml().contains("name: demo"));
    }

    @Test
    public void configRoundtrip_manualRepository(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "rancher-manual-token",
                        "manual",
                        Secret.fromString("manual-secret")));
        SystemCredentialsProvider.getInstance().save();

        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherManifestBuilder step = new RancherManifestBuilder("local");
        step.setRancherConnectionMode(RancherManifestBuilder.MODE_MANUAL);
        step.setRancherUrl("https://rancher.example");
        step.setRancherCredentialsId("rancher-manual-token");
        step.setManifestSource(RancherManifestBuilder.SOURCE_REPOSITORY);
        step.setRepositoryUrl("https://gitlab.example/group/manifests.git");
        step.setManifestFilePath("apps/manifest.yaml");
        project.getBuildersList().add(step);

        jenkins.configRoundtrip(project);
        RancherManifestBuilder loaded = project.getBuildersList().get(RancherManifestBuilder.class);
        assertEquals(RancherManifestBuilder.MODE_MANUAL, loaded.getRancherConnectionMode());
        assertEquals("https://rancher.example", loaded.getRancherUrl());
        assertEquals("rancher-manual-token", loaded.getRancherCredentialsId());
        assertEquals(RancherManifestBuilder.SOURCE_REPOSITORY, loaded.getManifestSource());
        assertEquals("https://gitlab.example/group/manifests.git", loaded.getRepositoryUrl());
        assertEquals("apps/manifest.yaml", loaded.getManifestFilePath());
    }

    @Test
    public void leftoverPersistedNamespaceXml_isIgnored(JenkinsRule jenkins) {
        String xml =
                "<io.jenkins.plugins.ranchermanager.RancherManifestBuilder>"
                        + "<clusterId>local</clusterId>"
                        + "<namespace>apps</namespace>"
                        + "<manifestSource>yaml</manifestSource>"
                        + "<ensureNamespace>true</ensureNamespace>"
                        + "</io.jenkins.plugins.ranchermanager.RancherManifestBuilder>";
        assertNotNull(jenkins.jenkins);
        Object loaded = hudson.model.Items.XSTREAM.fromXML(xml);
        RancherManifestBuilder step = assertInstanceOf(RancherManifestBuilder.class, loaded);
        assertEquals("local", step.getClusterId());
        assertEquals(RancherManifestBuilder.SOURCE_YAML, step.getManifestSource());
    }

    @Test
    public void configMapOnly_appliesWithoutWorkloadPoll(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(minimalYamlStep());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Preflight check of cluster local (local)", build);
        jenkins.assertLogContains("Applying manifest YAML", build);
        jenkins.assertLogContains("Summary outcome=applied", build);
        jenkins.assertLogContains("mode=inherit", build);
        assertTrue(applyCalled.get());
        assertFalse(namespaceCalled.get());
        assertTrue(workloadGetPaths.isEmpty());
        assertEquals(0, podsGets.get());
        assertTrue(lastContentType.get() != null && lastContentType.get().contains("json"));
        assertTrue(lastApplyBody.get() != null && lastApplyBody.get().contains("name: demo"));
        assertFalse(lastApplyBody.get().contains("namespace: default"));
    }

    @Test
    public void yamlWithTwoConfigMapNamespaces_appliesUnchanged(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherManifestBuilder step = new RancherManifestBuilder("local");
        step.setManifestSource(RancherManifestBuilder.SOURCE_YAML);
        step.setManifestYaml(
                "apiVersion: v1\n"
                        + "kind: ConfigMap\n"
                        + "metadata:\n"
                        + "  name: a\n"
                        + "  namespace: apps\n"
                        + "---\n"
                        + "apiVersion: v1\n"
                        + "kind: ConfigMap\n"
                        + "metadata:\n"
                        + "  name: b\n"
                        + "  namespace: other\n");
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertTrue(applyCalled.get());
        assertFalse(namespaceCalled.get());
        assertTrue(workloadGetPaths.isEmpty());
        assertTrue(lastApplyBody.get().contains("namespace: apps"));
        assertTrue(lastApplyBody.get().contains("namespace: other"));
    }

    @Test
    public void deployment_ready_afterTransition_succeeds(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        deploymentAppsWebBody.set(waitingDeploymentJson(0, 1));
        deploymentAppsWebBodyAfterFirst.set(activeDeploymentJson(1, 1));
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherManifestBuilder step = deploymentYamlStep("apps", "web");
        step.setWaitTimeoutSeconds("30");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Waiting for manifest workloads count=1", build);
        jenkins.assertLogContains("Summary outcome=applied", build);
        assertTrue(applyCalled.get());
        assertTrue(workloadGetPaths.stream().anyMatch(p -> p.endsWith("/apps.deployments/apps/web")));
        assertTrue(deploymentAppsWebGets.get() >= 2);
        assertEquals(0, podsGets.get());
        assertFalse(namespaceCalled.get());
    }

    @Test
    public void deployment_neverReady_failsWithPodsHint(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        deploymentAppsWebBody.set(waitingDeploymentJson(0, 1));
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherManifestBuilder step = deploymentYamlStep("apps", "web");
        step.setWaitTimeoutSeconds("1");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("did not become ready", build);
        jenkins.assertLogContains("Deployment apps/web", build);
        assertTrue(applyCalled.get());
        assertTrue(workloadGetPaths.stream().anyMatch(p -> p.endsWith("/apps.deployments/apps/web")));
        assertEquals(1, podsGets.get());
        assertTrue(lastPath.get().contains("/v1/pods/apps") || podsGets.get() == 1);
    }

    @Test
    public void multiNamespaceDeployments_pollEach(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherManifestBuilder step = new RancherManifestBuilder("local");
        step.setManifestSource(RancherManifestBuilder.SOURCE_YAML);
        step.setWaitTimeoutSeconds("30");
        step.setManifestYaml(
                "apiVersion: apps/v1\n"
                        + "kind: Deployment\n"
                        + "metadata:\n"
                        + "  name: web\n"
                        + "  namespace: apps\n"
                        + "spec:\n"
                        + "  replicas: 1\n"
                        + "---\n"
                        + "apiVersion: apps/v1\n"
                        + "kind: Deployment\n"
                        + "metadata:\n"
                        + "  name: api\n"
                        + "  namespace: other\n"
                        + "spec:\n"
                        + "  replicas: 1\n");
        project.getBuildersList().add(step);

        jenkins.buildAndAssertSuccess(project);
        assertTrue(applyCalled.get());
        assertTrue(workloadGetPaths.stream().anyMatch(p -> p.endsWith("/apps.deployments/apps/web")));
        assertTrue(workloadGetPaths.stream().anyMatch(p -> p.endsWith("/apps.deployments/other/api")));
        assertEquals(0, podsGets.get());
    }

    @Test
    public void validateOnly_skipsApplyAndWait(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherManifestBuilder step = deploymentYamlStep("apps", "web");
        step.setValidateOnly(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Validate-only", build);
        jenkins.assertLogContains("Summary outcome=validated", build);
        assertFalse(applyCalled.get());
        assertTrue(workloadGetPaths.isEmpty());
        assertFalse(namespaceCalled.get());
    }

    @Test
    public void invalidYaml_abortsBeforeApply(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherManifestBuilder step = new RancherManifestBuilder("local");
        step.setManifestSource(RancherManifestBuilder.SOURCE_YAML);
        step.setManifestYaml("this is not kubernetes");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("does not look like Kubernetes YAML", build);
        assertFalse(applyCalled.get());
        assertFalse(namespaceCalled.get());
    }

    @Test
    public void inherit_preflight_clusterScopedToken_skipsUsersMe(JenkinsRule jenkins) throws Exception {
        usersHit.set(false);
        server.createContext("/v3/users", exchange -> {
            usersHit.set(true);
            respond(exchange, 401, "{\"message\":\"Unauthorized 401: must authenticate\"}");
        });
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(minimalYamlStep());

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Summary outcome=applied", build);
        assertFalse(usersHit.get());
        assertTrue(workloadGetPaths.isEmpty());
    }

    @Test
    public void inherit_failsWhenSystemEmpty(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new RancherManifestBuilder("local"));
        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Rancher Manager is not configured", build);
    }

    @Test
    public void preflight_clusterMissing_aborts(JenkinsRule jenkins) throws Exception {
        server.createContext("/v3/clusters", exchange ->
                respond(exchange, 404, "{\"message\":\"cluster not found\"}"));
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(minimalYamlStep());

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        jenkins.assertLogContains("Preflight failed", build);
        assertFalse(applyCalled.get());
        assertFalse(namespaceCalled.get());
    }

    @Test
    public void pipeline_withoutNode_yamlApply(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "manifest-no-node");
        job.setDefinition(new CpsFlowDefinition(
                "rancherManifest(\n"
                        + "  clusterId: 'local',\n"
                        + "  manifestSource: 'yaml',\n"
                        + "  manifestYaml: '''apiVersion: v1\\nkind: ConfigMap\\nmetadata:\\n  name: demo\\n'''\n"
                        + ")\n",
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("Summary outcome=applied", run);
        jenkins.assertLogContains("clusterId=local", run);
        jenkins.assertLogContains("mode=inherit", run);
        assertTrue(applyCalled.get());
        assertTrue(workloadGetPaths.isEmpty());
        assertFalse(namespaceCalled.get());
    }

    @Test
    public void pipeline_repository_withoutNode_aborts(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "manifest-git-no-node");
        job.setDefinition(new CpsFlowDefinition(
                "rancherManifest(\n"
                        + "  clusterId: 'local',\n"
                        + "  manifestSource: 'repository',\n"
                        + "  repositoryUrl: 'https://gitlab.example/group/manifests.git'\n"
                        + ")\n",
                true));
        WorkflowRun run = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        jenkins.assertLogContains("FilePath is missing", run);
        assertFalse(applyCalled.get());
    }

    @Test
    public void pipeline_manual_overridesSystem(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "manual-token",
                        "manual",
                        Secret.fromString("manual-secret")));
        SystemCredentialsProvider.getInstance().save();

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "manifest-manual");
        job.setDefinition(new CpsFlowDefinition(
                "rancherManifest(\n"
                        + "  clusterId: 'local',\n"
                        + "  manifestSource: 'yaml',\n"
                        + "  manifestYaml: '''apiVersion: v1\\nkind: ConfigMap\\nmetadata:\\n  name: demo\\n''',\n"
                        + "  rancherConnectionMode: 'manual',\n"
                        + "  rancherUrl: '" + base + "',\n"
                        + "  rancherCredentialsId: 'manual-token'\n"
                        + ")\n",
                true));
        WorkflowRun run = jenkins.buildAndAssertSuccess(job);
        jenkins.assertLogContains("mode=manual", run);
        jenkins.assertLogContains("Summary outcome=applied", run);
    }

    @Test
    public void repositorySource_usesGitOverride(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        GitRepositoryFiles.testOverride.set(req -> {
            assertEquals("https://gitlab.example/group/manifests.git", req.repositoryUrl);
            assertEquals("manifest.yaml", req.relativePath);
            return "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: from-git\n";
        });
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherManifestBuilder step = new RancherManifestBuilder("local");
        step.setManifestSource(RancherManifestBuilder.SOURCE_REPOSITORY);
        step.setRepositoryUrl("https://gitlab.example/group/manifests.git");
        step.setManifestFilePath("manifest.yaml");
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Fetching manifest from Git", build);
        jenkins.assertLogContains("Summary outcome=applied", build);
        assertTrue(applyCalled.get());
        assertTrue(workloadGetPaths.isEmpty());
        assertFalse(namespaceCalled.get());
        assertTrue(lastApplyBody.get().contains("name: from-git"));
        assertFalse(lastApplyBody.get().contains("namespace: default"));
    }

    @Test
    public void validateOnly_repositorySource_skipsGit(JenkinsRule jenkins) throws Exception {
        configureRancher(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject();
        RancherManifestBuilder step = new RancherManifestBuilder("local");
        step.setManifestSource(RancherManifestBuilder.SOURCE_REPOSITORY);
        step.setRepositoryUrl("https://gitlab.example/group/manifests.git");
        step.setValidateOnly(true);
        step.setVerboseLogging(true);
        project.getBuildersList().add(step);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        jenkins.assertLogContains("Summary outcome=validated", build);
        assertFalse(applyCalled.get());
    }

    private static RancherManifestBuilder minimalYamlStep() {
        RancherManifestBuilder step = new RancherManifestBuilder("local");
        step.setManifestSource(RancherManifestBuilder.SOURCE_YAML);
        step.setManifestYaml(
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo\n");
        return step;
    }

    private static RancherManifestBuilder deploymentYamlStep(String namespace, String name) {
        RancherManifestBuilder step = new RancherManifestBuilder("local");
        step.setManifestSource(RancherManifestBuilder.SOURCE_YAML);
        step.setManifestYaml(
                "apiVersion: apps/v1\n"
                        + "kind: Deployment\n"
                        + "metadata:\n"
                        + "  name: "
                        + name
                        + "\n"
                        + "  namespace: "
                        + namespace
                        + "\n"
                        + "spec:\n"
                        + "  replicas: 1\n");
        return step;
    }

    private static String activeDeploymentJson(int ready, int desired) {
        return "{\"metadata\":{\"state\":{\"name\":\"active\"}},"
                + "\"spec\":{\"replicas\":"
                + desired
                + "},"
                + "\"status\":{\"readyReplicas\":"
                + ready
                + "}}";
    }

    private static String waitingDeploymentJson(int ready, int desired) {
        return "{\"metadata\":{\"state\":{\"name\":\"updating\",\"transitioning\":true}},"
                + "\"spec\":{\"replicas\":"
                + desired
                + "},"
                + "\"status\":{\"readyReplicas\":"
                + ready
                + "}}";
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

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
