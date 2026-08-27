package io.jenkins.plugins.ranchermanager;

import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RancherBuildLoggerTest {

    @Test
    void infoGoesToConsoleNotJulInfo() throws Exception {
        Logger jul = Logger.getLogger("RancherBuildLoggerTest.infoJul");
        Level previous = jul.getLevel();
        jul.setLevel(Level.ALL);
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        jul.addHandler(handler);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
                RancherBuildLogger log = new RancherBuildLogger(jul, listener, false)) {
            log.open(RancherBuildLogger.TITLE_HELM);
            log.info("release=demo chart=nginx");
            log.error("preflight failed");
        } finally {
            jul.removeHandler(handler);
            jul.setLevel(previous);
        }
        String console = buf.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("[INFO] Release=demo chart=nginx"));
        assertTrue(console.contains("[ERROR] Preflight failed"));
        assertTrue(records.stream().noneMatch(r -> r.getLevel() == Level.INFO));
        assertTrue(records.stream().anyMatch(
                r -> r.getLevel() == Level.FINE && r.getMessage().contains("Release=demo chart=nginx")));
        assertTrue(records.stream().anyMatch(
                r -> r.getLevel() == Level.SEVERE && r.getMessage().contains("Preflight failed")));
    }

    @Test
    void formatDuration_andSummary() {
        assertEquals("0ms", RancherBuildLogger.formatDuration(0));
        assertEquals("42ms", RancherBuildLogger.formatDuration(42));
        assertEquals("1.0s", RancherBuildLogger.formatDuration(1000));
        assertEquals("Summary", RancherBuildLogger.formatSummary(null));
        LinkedHashMap<String, String> fields = RancherBuildLogger.summaryFields();
        fields.put("outcome", "applied");
        fields.put("empty", "  ");
        fields.put(null, "x");
        fields.put("secret", null);
        assertEquals("Summary outcome=applied", RancherBuildLogger.formatSummary(fields));
        assertEquals(
                "Connection=prod mode=inherit",
                RancherBuildLogger.formatConnection(
                        new ResolvedConnection("prod", "inherit", "https://rancher.example", "id", 1, 2)));
    }

    @Test
    void console_openCloseVerboseHttpAndSecondErrorIgnored() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Logger jul = Logger.getLogger("RancherBuildLoggerTest.verbose");
        jul.setLevel(Level.OFF);
        try (StreamTaskListener listener = new StreamTaskListener(buf, StandardCharsets.UTF_8);
                RancherBuildLogger log = new RancherBuildLogger(jul, listener, true)) {
            log.open(RancherBuildLogger.TITLE_MANIFEST);
            log.open("ignored-second-open");
            log.debug("GET /v3 (1ms)");
            log.http("post", "/v3/clusters", 12L, "probe");
            log.http(null, "  ", -5L, "  ");
            log.error("first\nsecond");
            log.error("second-error-ignored");
            log.errorJul("jul-off", new RuntimeException("boom"));
            log.close();
            log.close();
        }
        String out = buf.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(out.contains(RancherBuildLogger.bannerHeader(RancherBuildLogger.TITLE_MANIFEST)));
        assertTrue(out.contains("[DEBUG] GET /v3 (1ms)"));
        assertTrue(out.contains("[DEBUG] POST /v3/clusters (12ms) — probe"));
        assertTrue(out.contains("[ERROR] First"));
        assertTrue(out.contains("second"));
        assertFalse(out.contains("second-error-ignored"));
    }

    @Test
    void formatLine_andSafePath() {
        assertEquals("[INFO] Hi", RancherBuildLogger.formatLine(Level.INFO, "hi"));
        assertEquals("[INFO] Hi", RancherBuildLogger.formatLine(Level.INFO, "Rancher: hi"));
        assertEquals("[DEBUG] Hi", RancherBuildLogger.formatLine(Level.FINE, "hi"));
        assertEquals("", RancherBuildLogger.capitalizeMessage("  "));
        assertEquals("123", RancherBuildLogger.capitalizeMessage("123"));
        assertEquals("/", RancherBuildLogger.safeRequestPath(URI.create("https://rancher.example")));
        RancherBuildLogger log = new RancherBuildLogger(null, TaskListener.NULL, false);
        log.summaryWithDuration(System.nanoTime() - 5_000_000L, null);
        assertFalse(log.hasLoggedError());
    }
}
