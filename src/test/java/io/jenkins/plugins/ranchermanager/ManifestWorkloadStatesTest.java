package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManifestWorkloadStatesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void classify_missingAndSteveActive() throws Exception {
        assertEquals(
                ManifestWorkloadStates.Progress.WAITING,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.DEPLOYMENT, null));
        assertEquals("missing", ManifestWorkloadStates.displayState(ManifestWorkloads.Kind.JOB, null));

        JsonNode active = MAPPER.readTree("{\"metadata\":{\"state\":{\"name\":\"active\"}}}");
        assertEquals(
                ManifestWorkloadStates.Progress.READY,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.STATEFULSET, active));
        assertEquals("active", ManifestWorkloadStates.displayState(ManifestWorkloads.Kind.DEPLOYMENT, active));
    }

    @Test
    void classify_steveErrorAndUpdatingWait() throws Exception {
        JsonNode updating = MAPPER.readTree(
                "{\"metadata\":{\"state\":{\"name\":\"updating\",\"transitioning\":true}}}");
        assertEquals(
                ManifestWorkloadStates.Progress.WAITING,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.DEPLOYMENT, updating));
        assertEquals("updating", ManifestWorkloadStates.displayState(ManifestWorkloads.Kind.DEPLOYMENT, updating));

        JsonNode failed = MAPPER.readTree("{\"metadata\":{\"state\":{\"name\":\"failed\",\"error\":true}}}");
        assertEquals(
                ManifestWorkloadStates.Progress.WAITING,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.JOB, failed));

        JsonNode pending = MAPPER.readTree("{\"metadata\":{\"state\":{\"name\":\"pending\"}}}");
        assertEquals(
                ManifestWorkloadStates.Progress.WAITING,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.DAEMONSET, pending));

        JsonNode inProgress = MAPPER.readTree("{\"metadata\":{\"state\":{\"message\":\"in-progress\"}}}");
        assertEquals(
                ManifestWorkloadStates.Progress.WAITING,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.DEPLOYMENT, inProgress));
    }

    @Test
    void classify_deploymentReplicas() throws Exception {
        JsonNode ready = MAPPER.readTree(
                "{\"spec\":{\"replicas\":2},\"status\":{\"readyReplicas\":2}}");
        assertEquals(
                ManifestWorkloadStates.Progress.READY,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.DEPLOYMENT, ready));
        assertEquals(
                "ready=2/2",
                ManifestWorkloadStates.displayState(ManifestWorkloads.Kind.DEPLOYMENT, ready));

        JsonNode waiting = MAPPER.readTree(
                "{\"spec\":{\"replicas\":3},\"status\":{\"availableReplicas\":1}}");
        assertEquals(
                ManifestWorkloadStates.Progress.WAITING,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.STATEFULSET, waiting));
        assertEquals(
                "ready=1/3",
                ManifestWorkloadStates.displayState(ManifestWorkloads.Kind.STATEFULSET, waiting));

        JsonNode zeroDesired = MAPPER.readTree("{\"spec\":{\"replicas\":0},\"status\":{}}");
        assertEquals(
                ManifestWorkloadStates.Progress.READY,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.DEPLOYMENT, zeroDesired));

        JsonNode missingSpec = MAPPER.readTree("{\"status\":{\"readyReplicas\":1}}");
        assertEquals(
                ManifestWorkloadStates.Progress.READY,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.DEPLOYMENT, missingSpec));
    }

    @Test
    void classify_daemonSet() throws Exception {
        JsonNode ready = MAPPER.readTree(
                "{\"status\":{\"desiredNumberScheduled\":2,\"numberReady\":2}}");
        assertEquals(
                ManifestWorkloadStates.Progress.READY,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.DAEMONSET, ready));
        assertEquals(
                "ready=2/2",
                ManifestWorkloadStates.displayState(ManifestWorkloads.Kind.DAEMONSET, ready));

        JsonNode waiting = MAPPER.readTree(
                "{\"status\":{\"desiredNumberScheduled\":3,\"numberReady\":1}}");
        assertEquals(
                ManifestWorkloadStates.Progress.WAITING,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.DAEMONSET, waiting));

        JsonNode noneScheduled = MAPPER.readTree("{\"status\":{\"desiredNumberScheduled\":0}}");
        assertEquals(
                ManifestWorkloadStates.Progress.WAITING,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.DAEMONSET, noneScheduled));
    }

    @Test
    void classify_job() throws Exception {
        JsonNode succeeded = MAPPER.readTree(
                "{\"spec\":{\"completions\":2},\"status\":{\"succeeded\":2}}");
        assertEquals(
                ManifestWorkloadStates.Progress.READY,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.JOB, succeeded));
        assertEquals(
                "succeeded=2/2",
                ManifestWorkloadStates.displayState(ManifestWorkloads.Kind.JOB, succeeded));

        JsonNode failedCondition = MAPPER.readTree(
                "{\"spec\":{\"completions\":1},\"status\":{\"succeeded\":0,"
                        + "\"conditions\":[{\"type\":\"Failed\",\"status\":\"True\"}]}}");
        assertEquals(
                ManifestWorkloadStates.Progress.WAITING,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.JOB, failedCondition));

        JsonNode stillRunning = MAPPER.readTree(
                "{\"status\":{\"succeeded\":0,\"conditions\":[{\"type\":\"Complete\",\"status\":\"False\"}]}}");
        assertEquals(
                ManifestWorkloadStates.Progress.WAITING,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.JOB, stillRunning));
        assertEquals(
                "succeeded=0/1",
                ManifestWorkloadStates.displayState(ManifestWorkloads.Kind.JOB, stillRunning));
    }
}
