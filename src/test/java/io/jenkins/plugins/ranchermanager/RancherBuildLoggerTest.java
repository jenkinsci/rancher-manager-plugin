package io.jenkins.plugins.ranchermanager;

import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
}
