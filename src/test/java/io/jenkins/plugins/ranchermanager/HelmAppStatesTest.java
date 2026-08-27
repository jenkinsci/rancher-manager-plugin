package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HelmAppStatesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void classify_readyStates() throws Exception {
        assertEquals(HelmAppStates.Progress.READY, HelmAppStates.classify(app("deployed", false)));
        assertEquals(HelmAppStates.Progress.READY, HelmAppStates.classify(app("active", false)));
        assertEquals(HelmAppStates.Progress.READY, HelmAppStates.classify(app("installed", false)));
    }

    @Test
    public void classify_failedStates() throws Exception {
        assertEquals(HelmAppStates.Progress.FAILED, HelmAppStates.classify(app("failed", false)));
        assertEquals(HelmAppStates.Progress.FAILED, HelmAppStates.classify(app("error", false)));
        assertEquals(HelmAppStates.Progress.FAILED, HelmAppStates.classify(app("deployed", true)));
    }

    @Test
    public void classify_waiting() throws Exception {
        assertEquals(HelmAppStates.Progress.WAITING, HelmAppStates.classify(null));
        assertEquals(HelmAppStates.Progress.WAITING, HelmAppStates.classify(app("transitioning", false)));
        assertEquals(HelmAppStates.Progress.WAITING, HelmAppStates.classify(app("pending-upgrade", false)));
        assertEquals("pending-upgrade", HelmAppStates.displayState(app("pending-upgrade", false)));
        assertEquals("missing", HelmAppStates.displayState(null));
        assertEquals(HelmAppStates.Progress.WAITING, HelmAppStates.classify(MAPPER.readTree("{\"metadata\":{}}")));
    }

    @Test
    public void classify_inFlightEvenIfErrorOrFailedState() throws Exception {
        assertEquals(HelmAppStates.Progress.WAITING, HelmAppStates.classify(app("transitioning", true)));
        JsonNode failedWhileMoving = MAPPER.readTree(
                "{\"status\":{\"summary\":{\"state\":\"failed\",\"transitioning\":true}}}");
        assertEquals(HelmAppStates.Progress.WAITING, HelmAppStates.classify(failedWhileMoving));
    }

    @Test
    public void classify_staleSnapshotVsBeforeIsWaiting() throws Exception {
        JsonNode failed = app("failed", false);
        assertEquals(HelmAppStates.Progress.WAITING, HelmAppStates.classify(failed, failed));
        assertEquals(HelmAppStates.Progress.FAILED, HelmAppStates.classify(failed, null));
    }

    @Test
    public void classify_generationLagIsWaiting() throws Exception {
        JsonNode lag = MAPPER.readTree(
                "{\"metadata\":{\"generation\":2},\"status\":{\"observedGeneration\":1,"
                        + "\"summary\":{\"state\":\"failed\"}}}");
        assertEquals(HelmAppStates.Progress.WAITING, HelmAppStates.classify(lag));
        JsonNode caughtUp = MAPPER.readTree(
                "{\"metadata\":{\"generation\":2},\"status\":{\"observedGeneration\":2,"
                        + "\"summary\":{\"state\":\"failed\"}}}");
        assertEquals(HelmAppStates.Progress.FAILED, HelmAppStates.classify(caughtUp));
    }

    @Test
    public void classify_sameResourceVersionIsWaiting() throws Exception {
        JsonNode before = MAPPER.readTree(
                "{\"metadata\":{\"resourceVersion\":\"11\"},\"status\":{\"summary\":{\"state\":\"failed\"}}}");
        JsonNode sameRv = MAPPER.readTree(
                "{\"metadata\":{\"resourceVersion\":\"11\"},\"status\":{\"summary\":{\"state\":\"failed\"}}}");
        JsonNode newRv = MAPPER.readTree(
                "{\"metadata\":{\"resourceVersion\":\"12\"},\"status\":{\"summary\":{\"state\":\"failed\"}}}");
        assertEquals(HelmAppStates.Progress.WAITING, HelmAppStates.classify(sameRv, before));
        assertEquals(HelmAppStates.Progress.FAILED, HelmAppStates.classify(newRv, before));
    }

    @Test
    public void thisOperation_falseForSameSnapshot() throws Exception {
        JsonNode snap = app("deployed", false);
        assertFalse(HelmAppStates.thisOperation(snap, snap));
        assertTrue(HelmAppStates.thisOperation(app("pending-upgrade", false), snap));
        assertTrue(HelmAppStates.thisOperation(null, snap));
    }

    @Test
    public void parseTimeout_defaultAndPositive() {
        assertEquals(300, HelmAppStates.parseTimeoutSeconds(null));
        assertEquals(300, HelmAppStates.parseTimeoutSeconds("  "));
        assertEquals(45, HelmAppStates.parseTimeoutSeconds("45"));
    }

    @Test
    void pollIntervalMs_propertyAndFallback() {
        String previous = System.getProperty(HelmAppStates.POLL_INTERVAL_MS_PROP);
        try {
            System.clearProperty(HelmAppStates.POLL_INTERVAL_MS_PROP);
            assertEquals(HelmAppStates.DEFAULT_POLL_INTERVAL_MS, HelmAppStates.pollIntervalMs());
            System.setProperty(HelmAppStates.POLL_INTERVAL_MS_PROP, "  ");
            assertEquals(HelmAppStates.DEFAULT_POLL_INTERVAL_MS, HelmAppStates.pollIntervalMs());
            System.setProperty(HelmAppStates.POLL_INTERVAL_MS_PROP, "0");
            assertEquals(1L, HelmAppStates.pollIntervalMs());
            System.setProperty(HelmAppStates.POLL_INTERVAL_MS_PROP, "15");
            assertEquals(15L, HelmAppStates.pollIntervalMs());
            System.setProperty(HelmAppStates.POLL_INTERVAL_MS_PROP, "nope");
            assertEquals(HelmAppStates.DEFAULT_POLL_INTERVAL_MS, HelmAppStates.pollIntervalMs());
        } finally {
            if (previous == null) {
                System.clearProperty(HelmAppStates.POLL_INTERVAL_MS_PROP);
            } else {
                System.setProperty(HelmAppStates.POLL_INTERVAL_MS_PROP, previous);
            }
        }
    }

    @Test
    void displayState_unknownWhenBlank() throws Exception {
        assertEquals("unknown", HelmAppStates.displayState(MAPPER.readTree("{\"status\":{}}")));
        assertEquals("", HelmAppStates.failureDetail(null));
        assertTrue(HelmAppStates.thisOperation(null, null));
    }

    @Test
    public void parseTimeout_rejectsZeroAndNonNumber() {
        assertThrows(IllegalArgumentException.class, () -> HelmAppStates.parseTimeoutSeconds("0"));
        assertThrows(IllegalArgumentException.class, () -> HelmAppStates.parseTimeoutSeconds("-1"));
        assertThrows(IllegalArgumentException.class, () -> HelmAppStates.parseTimeoutSeconds("nope"));
    }

    @Test
    public void failureDetail_fromSummaryMessage() throws Exception {
        JsonNode app = MAPPER.readTree(
                "{\"status\":{\"summary\":{\"state\":\"failed\",\"message\":\"exit 123\"}}}");
        assertEquals("exit 123", HelmAppStates.failureDetail(app));
    }

    private static JsonNode app(String state, boolean error) throws Exception {
        return MAPPER.readTree(
                "{\"status\":{\"summary\":{\"state\":\"" + state + "\",\"error\":" + error + "}}}");
    }
}
