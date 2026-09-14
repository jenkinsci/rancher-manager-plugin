package io.jenkins.plugins.ranchermanager;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.util.FormValidation;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class RancherConnectionsTest {

    @AfterEach
    void clearLoopback() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
    }

    @Test
    void parseAndResolveClusterId() throws Exception {
        assertEquals("local", RancherConnections.parseClusterId(" local "));
        AbortException blank = assertThrows(AbortException.class, () -> RancherConnections.parseClusterId(" "));
        assertTrue(blank.getMessage().contains("Cluster ID is required"));
        assertThrows(AbortException.class, () -> RancherConnections.parseClusterId("a/b"));
        assertThrows(AbortException.class, () -> RancherConnections.parseClusterId("a?b"));
        assertThrows(AbortException.class, () -> RancherConnections.parseClusterId("a#b"));
        assertThrows(AbortException.class, () -> RancherConnections.parseClusterId("a..b"));

        EnvVars env = new EnvVars();
        env.put("CLUSTER", "local");
        assertEquals("local", RancherConnections.resolveClusterId("${CLUSTER}", env));
        assertEquals("c1", RancherConnections.resolveClusterId("c1", null));
    }

    @Test
    void formatPreflightCluster() {
        assertEquals("Preflight check of cluster local", RancherConnections.formatPreflightCluster("local", "  "));
        assertEquals(
                "Preflight check of cluster local (prod)",
                RancherConnections.formatPreflightCluster("local", "prod"));
    }

    @Test
    void abort_throwsLoggedAbortWithoutConsoleError() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
        RancherBuildLogger log =
                new RancherBuildLogger(Logger.getLogger("RancherConnectionsTest"), listener, false);

        AbortException first = RancherConnections.abort(log, "  boom  ");
        assertInstanceOf(RancherLoggedAbort.class, first);
        assertEquals("boom", first.getMessage());
        assertFalse(log.hasLoggedError());
        assertFalse(buf.toString(StandardCharsets.UTF_8).contains("[ERROR]"));

        AbortException withCause = RancherConnections.abort(log, "with cause", new IOException("root"));
        assertEquals("with cause", withCause.getMessage());
        assertFalse(log.hasLoggedError());
        assertFalse(buf.toString(StandardCharsets.UTF_8).contains("[ERROR]"));

        AbortException empty = RancherConnections.abort(quietLog(), "   ");
        assertEquals("failed", empty.getMessage());

        RancherLoggedAbort already = new RancherLoggedAbort("already");
        assertSame(already, RancherConnections.abort(log, "other", already));
        assertEquals("failed", new RancherLoggedAbort(null).getMessage());
        assertEquals("failed", new RancherLoggedAbort("  ").getMessage());
    }

    @Test
    void abortOn_wrapsIllegalArgumentAndAbort() throws Exception {
        assertEquals(7, RancherConnections.abortOn(quietLog(), () -> 7));
        AbortException arg = assertThrows(
                AbortException.class,
                () -> RancherConnections.abortOn(quietLog(), () -> {
                    throw new IllegalArgumentException("bad field");
                }));
        assertTrue(arg.getMessage().contains("bad field"));
        AbortException abort = assertThrows(
                AbortException.class,
                () -> RancherConnections.abortOn(quietLog(), () -> {
                    throw new AbortException("step failed");
                }));
        assertTrue(abort.getMessage().contains("step failed"));
    }

    @Test
    void truncateMessage_andShortHash() {
        assertEquals("IOException", RancherConnections.truncateMessage(new IOException()));
        assertEquals("short", RancherConnections.truncateMessage(new IOException("short")));
        String longMsg = "H".repeat(RancherClient.MAX_ERROR_DETAIL_CHARS + 80);
        String truncated = RancherConnections.truncateMessage(new IOException(longMsg));
        assertTrue(truncated.contains("…"));
        assertTrue(truncated.length() <= RancherClient.MAX_ERROR_DETAIL_CHARS);

        assertEquals("-", RancherConnections.shortContentHash(null));
        assertEquals("-", RancherConnections.shortContentHash(""));
        String hash = RancherConnections.shortContentHash("kind: ConfigMap\n");
        assertEquals(16, hash.length());
        assertFalse(hash.contains("ConfigMap"));
    }

    @Test
    void resolveOptionalGitAuth_blankIsNull() {
        assertNull(RancherConnections.resolveOptionalGitAuth(null, null));
        assertNull(RancherConnections.resolveOptionalGitAuth("  ", null));
    }

    @Test
    void formChecks_clusterAndUrl() {
        FormValidation inheritOk = RancherConnections.checkClusterId("local", ConnectionMode.MANUAL);
        assertEquals(FormValidation.Kind.OK, inheritOk.kind);

        FormValidation blank = RancherConnections.checkClusterId(" ", ConnectionMode.MANUAL);
        assertEquals(FormValidation.Kind.ERROR, blank.kind);

        FormValidation env = RancherConnections.checkClusterId("${CLUSTER}", ConnectionMode.MANUAL);
        assertEquals(FormValidation.Kind.OK, env.kind);

        FormValidation badId = RancherConnections.checkClusterId("a/b", ConnectionMode.MANUAL);
        assertEquals(FormValidation.Kind.ERROR, badId.kind);

        assertEquals(
                FormValidation.Kind.OK,
                RancherConnections.checkRancherUrl("ignored", ConnectionMode.INHERIT).kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                RancherConnections.checkRancherUrl(" ", ConnectionMode.MANUAL).kind);
        assertEquals(
                FormValidation.Kind.OK,
                RancherConnections.checkRancherUrl("https://rancher.example", ConnectionMode.MANUAL).kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                RancherConnections.checkRancherUrl("ftp://x", ConnectionMode.MANUAL).kind);
    }

    @Test
    void resolvedConnection_defaultsNullModeToInherit() {
        ResolvedConnection c = new ResolvedConnection("n", null, "https://rancher.example", "id", 1, 2);
        assertEquals(ConnectionMode.INHERIT, c.mode);
        assertEquals("n", c.displayName);
    }

    @Test
    void resolveManual_requiresUrlAndNormalizes(JenkinsRule jenkins) throws Exception {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        AbortException missing = assertThrows(
                AbortException.class,
                () -> RancherConnections.resolve(null, ConnectionMode.MANUAL, "https://rancher.example", " "));
        assertTrue(missing.getMessage().contains("Manual"));

        AbortException badUrl = assertThrows(
                AbortException.class,
                () -> RancherConnections.resolve(null, ConnectionMode.MANUAL, "ftp://x", "tok"));
        assertTrue(badUrl.getMessage().toLowerCase().contains("http"));

        ResolvedConnection manual = RancherConnections.resolve(
                null, ConnectionMode.MANUAL, "http://127.0.0.1:1/", "tok");
        assertEquals(ConnectionMode.MANUAL, manual.mode);
        assertEquals("tok", manual.credentialsId);
        assertEquals("http://127.0.0.1:1", manual.baseUrl);
        assertEquals(RancherGlobalConfiguration.DEFAULT_CONNECT_TIMEOUT_MS, manual.connectTimeoutMs);
    }

    @Test
    void resolveInherit_andConnectionSummary(JenkinsRule jenkins) throws Exception {
        assertTrue(RancherConnections.connectionSummary().contains("not configured"));
        FormValidation src = RancherConnections.checkRancherSource(ConnectionMode.INHERIT);
        assertEquals(FormValidation.Kind.ERROR, src.kind);
        assertEquals(FormValidation.Kind.OK, RancherConnections.checkRancherSource(ConnectionMode.MANUAL).kind);

        RancherGlobalConfiguration cfg = RancherGlobalConfiguration.get();
        cfg.setName("  ");
        cfg.setRancherUrl("https://rancher.example");
        cfg.setCredentialsId("tok");
        ResolvedConnection inherit = RancherConnections.resolve(cfg, ConnectionMode.INHERIT, null, null);
        assertEquals("default", inherit.displayName);
        assertEquals(ConnectionMode.INHERIT, inherit.mode);
        assertTrue(RancherConnections.connectionSummary().contains("is configured"));
        assertEquals(FormValidation.Kind.OK, RancherConnections.checkRancherSource(ConnectionMode.INHERIT).kind);
    }

    @Test
    void safeRequestPath_andLoggerStatics() {
        assertEquals("/", RancherBuildLogger.safeRequestPath(null));
        assertEquals("/v3", RancherBuildLogger.safeRequestPath(URI.create("https://rancher.example/v3")));
        assertEquals(
                "/v3/users?me=true",
                RancherBuildLogger.safeRequestPath(URI.create("https://rancher.example/v3/users?me=true")));
        assertEquals("0ms", RancherBuildLogger.formatDuration(-3));
        assertEquals("INFO", RancherBuildLogger.consoleLabel(null));
        assertEquals("WARN", RancherBuildLogger.consoleLabel(Level.WARNING));
        assertEquals("Connection= mode=", RancherBuildLogger.formatConnection(null));
        assertEquals(
                "======== Rancher Manager ========",
                RancherBuildLogger.bannerHeader("  "));
    }

    private static RancherBuildLogger quietLog() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
        return new RancherBuildLogger(Logger.getLogger("RancherConnectionsTest"), listener, false);
    }
}
