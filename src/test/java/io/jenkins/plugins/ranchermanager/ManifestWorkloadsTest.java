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

    @Test
    void parse_kindsAndSkips() {
        List<ManifestWorkloads.Workload> workloads = ManifestWorkloads.parse(
                "apiVersion: apps/v1\nkind: StatefulSet\nmetadata:\n  name: db\n  namespace: data\n---\n"
                        + "kind: DaemonSet\nmetadata:\n  name: agent\n---\n"
                        + "kind: Job\nmetadata:\n  name: migrate\n---\n"
                        + "kind: Deployment\nmetadata:\n  name: \"\"\n---\n"
                        + "- not-a-map\n---\n"
                        + "kind: Service\nmetadata:\n  name: svc\n---\n"
                        + "kind: Deployment\nmetadata: not-a-map\n");
        assertEquals(3, workloads.size());
        assertEquals("apps.statefulsets/data/db", workloads.get(0).stevePath());
        assertEquals("DaemonSet default/agent", workloads.get(1).display());
        assertEquals(ManifestWorkloads.Kind.JOB, workloads.get(2).kind());
        assertTrue(ManifestWorkloads.parse(null).isEmpty());
        assertTrue(ManifestWorkloads.parse("  ").isEmpty());
        assertTrue(ManifestWorkloads.namespaces(null).isEmpty());
        assertTrue(ManifestWorkloads.namespaces(List.of()).isEmpty());
        assertEquals(ManifestWorkloads.Kind.DEPLOYMENT, ManifestWorkloads.Kind.of("deployment"));
        assertEquals(null, ManifestWorkloads.Kind.of(" "));
        assertEquals(null, ManifestWorkloads.Kind.of("Secret"));
    }

    @Test
    void parse_invalidYamlThrows() {
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> ManifestWorkloads.parse("foo: [unterminated"));
        assertTrue(ex.getMessage().contains("Cannot parse manifest YAML"));
    }
}
