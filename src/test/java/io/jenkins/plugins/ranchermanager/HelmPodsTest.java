package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HelmPodsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void problems_imagePullBackOff() throws Exception {
        JsonNode list = MAPPER.readTree(
                "{\"data\":[{\"metadata\":{\"name\":\"mnp-abc\",\"labels\":{"
                        + "\"app.kubernetes.io/instance\":\"mnp\"}},"
                        + "\"status\":{\"phase\":\"Pending\",\"containerStatuses\":[{"
                        + "\"name\":\"mnp\",\"state\":{\"waiting\":{"
                        + "\"reason\":\"ImagePullBackOff\","
                        + "\"message\":\"Back-off pulling image \\\"registry.example/mnp:0.1.11\\\"\"}}}]}}]}");
        List<String> problems = HelmPods.problems(list, "mnp");
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("pod mnp-abc container mnp"));
        assertTrue(problems.get(0).contains("ImagePullBackOff"));
        assertTrue(problems.get(0).contains("registry.example/mnp:0.1.11"));
    }

    @Test
    public void problems_ignoresOtherReleaseAndReadyPods() throws Exception {
        JsonNode list = MAPPER.readTree(
                "{\"data\":["
                        + "{\"metadata\":{\"name\":\"other\",\"labels\":{"
                        + "\"app.kubernetes.io/instance\":\"other\"}},"
                        + "\"status\":{\"containerStatuses\":[{\"name\":\"c\",\"state\":{\"waiting\":{"
                        + "\"reason\":\"ImagePullBackOff\",\"message\":\"x\"}}}]}},"
                        + "{\"metadata\":{\"name\":\"ok\",\"labels\":{"
                        + "\"app.kubernetes.io/instance\":\"mnp\"}},"
                        + "\"status\":{\"phase\":\"Running\",\"containerStatuses\":["
                        + "{\"name\":\"c\",\"ready\":true,\"state\":{\"running\":{}}}]}}"
                        + "]}");
        assertTrue(HelmPods.problems(list, "mnp").isEmpty());
    }

    @Test
    public void problems_unschedulableWhenNoContainerStatuses() throws Exception {
        JsonNode list = MAPPER.readTree(
                "{\"data\":[{\"metadata\":{\"name\":\"mnp-abc\",\"annotations\":{"
                        + "\"meta.helm.sh/release-name\":\"mnp\"}},"
                        + "\"status\":{\"phase\":\"Pending\",\"conditions\":[{"
                        + "\"type\":\"PodScheduled\",\"status\":\"False\","
                        + "\"reason\":\"Unschedulable\",\"message\":\"0/1 nodes are available\"}]}}]}");
        List<String> problems = HelmPods.problems(list, "mnp");
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("Unschedulable"));
        assertTrue(problems.get(0).contains("0/1 nodes"));
    }

    @Test
    public void fingerprint_stableJoin() {
        assertEquals("", HelmPods.fingerprint(List.of()));
        assertEquals("a\nb", HelmPods.fingerprint(List.of("a", "b")));
        assertEquals("a; b", HelmPods.joined(List.of("a", "b")));
    }

    @Test
    public void belongsToRelease_annotationFallback() throws Exception {
        JsonNode wl = MAPPER.readTree(
                "{\"metadata\":{\"annotations\":{\"meta.helm.sh/release-name\":\"demo-nginx\"}}}");
        assertTrue(HelmPods.belongsToRelease(wl, "demo-nginx"));
        assertFalse(HelmPods.belongsToRelease(wl, "other"));
    }
}
