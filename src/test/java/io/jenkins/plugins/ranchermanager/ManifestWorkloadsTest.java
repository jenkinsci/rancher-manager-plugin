package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestWorkloadsTest {

    @Test
    void parse_skipsConfigMap_andCollectsWorkloads() {
        List<ManifestWorkloads.Workload> workloads = ManifestWorkloads.parse(
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cfg\n---\n"
                        + "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: web\n"
                        + "  namespace: apps\n---\n"
                        + "apiVersion: batch/v1\nkind: Job\nmetadata:\n  name: migrate\n");
        assertEquals(2, workloads.size());
        assertEquals("Deployment apps/web", workloads.get(0).display());
        assertEquals("Job default/migrate", workloads.get(1).display());
        assertTrue(ManifestWorkloads.namespaces(workloads).contains("apps"));
        assertTrue(ManifestWorkloads.namespaces(workloads).contains("default"));
    }

    @Test
    void classify_activeSteve_isReady() throws Exception {
        var node = new ObjectMapper().readTree("{\"metadata\":{\"state\":{\"name\":\"active\"}}}");
        assertEquals(
                ManifestWorkloadStates.Progress.READY,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.DEPLOYMENT, node));
    }

    @Test
    void classify_missing_isWaiting() {
        assertEquals(
                ManifestWorkloadStates.Progress.WAITING,
                ManifestWorkloadStates.classify(ManifestWorkloads.Kind.DEPLOYMENT, null));
    }
}
