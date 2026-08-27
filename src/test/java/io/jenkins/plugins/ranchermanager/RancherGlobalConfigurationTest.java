package io.jenkins.plugins.ranchermanager;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.GlobalConfigurationCategory;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
public class RancherGlobalConfigurationTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    public void startServer() throws IOException {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/users", exchange -> respond(exchange, 200, "{\"username\":\"admin\"}"));
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
    public void roundTrip_andProbeConnection(JenkinsRule jenkins) throws Exception {
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

        RancherGlobalConfiguration loaded = RancherGlobalConfiguration.get();
        assertEquals("prod", loaded.getName());
        assertEquals(base, loaded.getRancherUrl());
        assertEquals("rancher-api-token", loaded.getCredentialsId());
        assertTrue(loaded.isConfigured());

        FormValidation ok = loaded.probeConnection(base, "rancher-api-token", 2000, 2000);
        assertEquals(FormValidation.Kind.OK, ok.kind);
        assertTrue(ok.getMessage().contains("Connection successful (Rancher user=admin)"));
        assertFalse(ok.getMessage().contains("secret-token-value"));

        FormValidation badUrl = loaded.doCheckRancherUrl("ftp://rancher.example");
        assertEquals(FormValidation.Kind.ERROR, badUrl.kind);

        FormValidation userInfo = loaded.doCheckRancherUrl("https://u:p@rancher.example");
        assertEquals(FormValidation.Kind.ERROR, userInfo.kind);
        assertTrue(userInfo.getMessage().toLowerCase().contains("userinfo"));

        FormValidation syntaxOnly = loaded.doCheckRancherUrl(
                "https://no-such-host-rancher-cfg.invalid");
        assertEquals(FormValidation.Kind.OK, syntaxOnly.kind);
    }

    @Test
    public void probeConnection_maps401WithoutLeakingSecret(JenkinsRule jenkins) throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/users", exchange -> respond(exchange, 401, "{\"message\":\"Unauthorized\"}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "bad-key",
                        "bad",
                        Secret.fromString("leaked-if-present")));
        SystemCredentialsProvider.getInstance().save();

        RancherGlobalConfiguration cfg = RancherGlobalConfiguration.get();
        FormValidation result = cfg.probeConnection(base, "bad-key", 2000, 2000);
        assertEquals(FormValidation.Kind.ERROR, result.kind);
        assertTrue(result.getMessage().startsWith("Connection failed — "));
        assertTrue(result.getMessage().contains("HTTP 401"));
        assertFalse(result.getMessage().contains("leaked-if-present"));
    }

    @Test
    public void category_andDisplayName(JenkinsRule jenkins) {
        RancherGlobalConfiguration cfg = RancherGlobalConfiguration.get();
        assertInstanceOf(GlobalConfigurationCategory.Unclassified.class, cfg.getCategory());
        assertEquals("Rancher Manager", cfg.getDisplayName());
    }

    @Test
    public void setters_persistWithoutExplicitSave(JenkinsRule jenkins) {
        RancherGlobalConfiguration cfg = RancherGlobalConfiguration.get();
        cfg.setName("script-console");
        cfg.setRancherUrl("https://rancher.example");
        cfg.setCredentialsId("script-cred");
        cfg.setConnectTimeoutMs(5000);
        cfg.setReadTimeoutMs(15000);

        RancherGlobalConfiguration reloaded = new RancherGlobalConfiguration();
        assertEquals("script-console", reloaded.getName());
        assertEquals("https://rancher.example", reloaded.getRancherUrl());
        assertEquals("script-cred", reloaded.getCredentialsId());
        assertEquals(5000, reloaded.getConnectTimeoutMs());
        assertEquals(15000, reloaded.getReadTimeoutMs());
        assertTrue(reloaded.isConfigured());
    }

    @Test
    public void clamp_formChecks_andProbeGuards(JenkinsRule jenkins) {
        RancherGlobalConfiguration cfg = RancherGlobalConfiguration.get();
        cfg.setName("  ");
        assertEquals("default", cfg.getName());
        cfg.setRancherUrl(null);
        cfg.setCredentialsId("  ");
        assertFalse(cfg.isConfigured());
        cfg.setConnectTimeoutMs(0);
        assertEquals(RancherGlobalConfiguration.DEFAULT_CONNECT_TIMEOUT_MS, cfg.getConnectTimeoutMs());
        cfg.setReadTimeoutMs(500_000);
        assertEquals(120_000, cfg.getReadTimeoutMs());

        assertEquals(FormValidation.Kind.ERROR, cfg.doCheckRancherUrl("").kind);
        assertEquals(FormValidation.Kind.OK, cfg.doCheckConnectTimeoutMs("").kind);
        assertEquals(FormValidation.Kind.ERROR, cfg.doCheckConnectTimeoutMs("x").kind);
        assertEquals(FormValidation.Kind.ERROR, cfg.doCheckConnectTimeoutMs("1").kind);
        assertEquals(FormValidation.Kind.OK, cfg.doCheckConnectTimeoutMs("1000").kind);
        assertEquals(FormValidation.Kind.OK, cfg.doCheckReadTimeoutMs("").kind);
        assertEquals(FormValidation.Kind.ERROR, cfg.doCheckReadTimeoutMs("50").kind);

        FormValidation noUrl = cfg.probeConnection(" ", "tok", 1000, 1000);
        assertEquals(FormValidation.Kind.ERROR, noUrl.kind);
        FormValidation badUrl = cfg.probeConnection("ftp://x", "tok", 1000, 1000);
        assertEquals(FormValidation.Kind.ERROR, badUrl.kind);
        FormValidation noCreds = cfg.probeConnection("https://rancher.example", " ", 1000, 1000);
        assertEquals(FormValidation.Kind.ERROR, noCreds.kind);
        assertFalse(cfg.doFillCredentialsIdItems("").isEmpty());
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
