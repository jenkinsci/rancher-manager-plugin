package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ClusterRepoStatesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void classify_downloadedIsReady() throws Exception {
        JsonNode before = MAPPER.readTree(
                "{\"metadata\":{\"resourceVersion\":\"1\"},\"status\":{\"downloadTime\":\"t1\"}}");
        JsonNode after = MAPPER.readTree(
                "{\"metadata\":{\"resourceVersion\":\"2\"},\"status\":{\"downloadTime\":\"t2\","
                        + "\"conditions\":[{\"type\":\"Downloaded\",\"status\":\"True\"}]}}");
        assertEquals(ClusterRepoStates.Progress.READY, ClusterRepoStates.classify(after, before));
    }

    @Test
    public void classify_sameSnapshotIsWaiting() throws Exception {
        JsonNode snap = MAPPER.readTree(
                "{\"metadata\":{\"resourceVersion\":\"1\"},\"status\":{\"downloadTime\":\"t1\","
                        + "\"conditions\":[{\"type\":\"Downloaded\",\"status\":\"True\"}]}}");
        assertEquals(ClusterRepoStates.Progress.WAITING, ClusterRepoStates.classify(snap, snap));
    }

    @Test
    public void classify_transitioningIsWaiting() throws Exception {
        JsonNode repo = MAPPER.readTree(
                "{\"metadata\":{\"resourceVersion\":\"2\"},"
                        + "\"status\":{\"summary\":{\"state\":\"transitioning\",\"transitioning\":true}}}");
        assertEquals(ClusterRepoStates.Progress.WAITING, ClusterRepoStates.classify(repo, null));
    }

    @Test
    public void classify_errorIsFailed() throws Exception {
        JsonNode repo = MAPPER.readTree(
                "{\"status\":{\"summary\":{\"state\":\"failed\",\"error\":true,\"message\":\"bad index\"}}}");
        assertEquals(ClusterRepoStates.Progress.FAILED, ClusterRepoStates.classify(repo, null));
        assertEquals("bad index", ClusterRepoStates.failureDetail(repo));
    }
}
