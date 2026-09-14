package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HelmRelationshipsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void gate_missingRelationshipsPasses() throws Exception {
        JsonNode app = MAPPER.readTree("{\"status\":{\"summary\":{\"state\":\"deployed\"}}}");
        assertEquals(HelmRelationships.Gate.PASSED, HelmRelationships.gate(app));
        assertFalse(HelmRelationships.hasInactiveWorkload(app));
        assertTrue(HelmRelationships.boardLines(app).isEmpty());
        assertEquals("", HelmRelationships.inactiveWorkloads(app));
    }

    @Test
    public void gate_configmapOnlyPasses() throws Exception {
        JsonNode app = MAPPER.readTree(appWith(
                "{\"toId\":\"mnp/mnp-frontend-config\",\"toType\":\"configmap\","
                        + "\"rel\":\"helmresource\",\"state\":\"active\","
                        + "\"message\":\"Resource is always ready\"}"));
        assertEquals(HelmRelationships.Gate.PASSED, HelmRelationships.gate(app));
        List<String> board = HelmRelationships.boardLines(app);
        assertEquals(1, board.size());
        assertTrue(board.get(0).contains("Helm configmap mnp-frontend-config: active"));
    }

    @Test
    public void gate_activeDeploymentPasses() throws Exception {
        JsonNode app = MAPPER.readTree(appWith(deployment("active", "Deployment is available. Replicas: 1")));
        assertEquals(HelmRelationships.Gate.PASSED, HelmRelationships.gate(app));
    }

    @Test
    public void gate_updatingDeploymentNotReady() throws Exception {
        JsonNode app = MAPPER.readTree(appWith(
                deployment("updating", "Deployment does not have minimum availability")));
        assertEquals(HelmRelationships.Gate.NOT_READY, HelmRelationships.gate(app));
        assertTrue(HelmRelationships.hasInactiveWorkload(app));
        assertTrue(HelmRelationships.inactiveWorkloads(app).contains("deployment demo-nginx: updating"));
        assertTrue(HelmRelationships.inactiveWorkloads(app).contains("minimum availability"));
    }

    @Test
    public void boardLines_skipsOwnerSecretAndHelmReleaseSecret() throws Exception {
        JsonNode app = MAPPER.readTree(
                "{\"relationships\":["
                        + "{\"fromId\":\"mnp/sh.helm.release.v1.mnp.v14\",\"fromType\":\"secret\","
                        + "\"rel\":\"owner\",\"state\":\"active\",\"message\":\"Resource is always ready\"},"
                        + "{\"toId\":\"mnp/sh.helm.release.v1.mnp.v14\",\"toType\":\"secret\","
                        + "\"rel\":\"helmresource\",\"state\":\"active\"},"
                        + "{\"toId\":\"mnp/mnp-backend\",\"toType\":\"service\","
                        + "\"rel\":\"helmresource\",\"state\":\"active\",\"message\":\"Service is ready\"}"
                        + "]}");
        List<String> board = HelmRelationships.boardLines(app);
        assertEquals(1, board.size());
        assertEquals("Helm service mnp-backend: active (Service is ready)", board.get(0));
    }

    @Test
    public void metadataRelationships_liveShape_boardAndGate() throws Exception {
        String depActive = "{\"toId\":\"mnp/mnp-backend\",\"toType\":\"apps.deployment\","
                + "\"rel\":\"helmresource\",\"state\":\"active\","
                + "\"message\":\"Deployment is available. Replicas: 1\"}";
        String svc = "{\"toId\":\"mnp/mnp-backend\",\"toType\":\"service\","
                + "\"rel\":\"helmresource\",\"state\":\"active\",\"message\":\"Service is ready\"}";
        String cm = "{\"toId\":\"mnp/mnp-frontend-config\",\"toType\":\"configmap\","
                + "\"rel\":\"helmresource\",\"state\":\"active\",\"message\":\"Resource is always ready\"}";
        String secret = "{\"toId\":\"mnp/sh.helm.release.v1.mnp.v86\",\"toType\":\"secret\","
                + "\"rel\":\"helmresource\",\"state\":\"active\"}";
        JsonNode app = MAPPER.readTree(
                "{\"status\":{\"summary\":{\"state\":\"deployed\"}},"
                        + "\"metadata\":{\"name\":\"mnp\",\"relationships\":["
                        + cm + "," + svc + "," + depActive + "," + secret + "]}}");
        assertEquals(HelmRelationships.Gate.PASSED, HelmRelationships.gate(app));
        List<String> board = HelmRelationships.boardLines(app);
        assertEquals(3, board.size());
        assertTrue(board.stream().anyMatch(l -> l.contains("Helm deployment mnp-backend: active")));
        assertTrue(board.stream().anyMatch(l -> l.contains("Helm service mnp-backend: active")));
        assertTrue(board.stream().anyMatch(l -> l.contains("Helm configmap mnp-frontend-config: active")));
    }

    @Test
    public void metadataRelationships_updatingDeployment_notReady() throws Exception {
        JsonNode app = MAPPER.readTree(
                "{\"status\":{\"summary\":{\"state\":\"deployed\"}},"
                        + "\"metadata\":{\"relationships\":["
                        + "{\"toId\":\"mnp/mnp-frontend\",\"toType\":\"apps.deployment\","
                        + "\"rel\":\"helmresource\",\"state\":\"updating\","
                        + "\"message\":\"Deployment does not have minimum availability\"}"
                        + "]}}");
        assertEquals(HelmRelationships.Gate.NOT_READY, HelmRelationships.gate(app));
        assertTrue(HelmRelationships.inactiveWorkloads(app).contains("mnp-frontend"));
        assertTrue(HelmRelationships.inactiveWorkloads(app).contains("minimum availability"));
    }

    @Test
    public void metadataPreferredOverEmptyRoot() throws Exception {
        JsonNode app = MAPPER.readTree(
                "{\"relationships\":[],"
                        + "\"metadata\":{\"relationships\":["
                        + deployment("active", "ok")
                        + "]},"
                        + "\"status\":{\"summary\":{\"state\":\"deployed\"}}}");
        assertEquals(HelmRelationships.Gate.PASSED, HelmRelationships.gate(app));
        assertEquals(1, HelmRelationships.boardLines(app).size());
    }

    @Test
    public void fingerprint_joinsLines() {
        assertEquals("", HelmRelationships.fingerprint(List.of()));
        assertEquals("", HelmRelationships.fingerprint(null));
        assertEquals("a\nb", HelmRelationships.fingerprint(List.of("a", "b")));
    }

    @Test
    public void gate_statefulSetJobUnnamedAndUnknownType() throws Exception {
        assertEquals(HelmRelationships.Gate.PASSED, HelmRelationships.gate(null));
        JsonNode sts = MAPPER.readTree(appWith(
                "{\"toId\":\"default/db\",\"toType\":\"apps.statefulset\","
                        + "\"rel\":\"helmresource\",\"state\":\"updating\"}"));
        assertEquals(HelmRelationships.Gate.NOT_READY, HelmRelationships.gate(sts));

        JsonNode job = MAPPER.readTree(appWith(
                "{\"toId\":\"default/migrate\",\"toType\":\"apps.daemonset\","
                        + "\"rel\":\"helmresource\",\"state\":\"active\"}"));
        assertEquals(HelmRelationships.Gate.PASSED, HelmRelationships.gate(job));

        JsonNode unnamed = MAPPER.readTree(appWith(
                "{\"toType\":\"configmap\",\"rel\":\"helmresource\",\"state\":\"\"}"));
        List<String> board = HelmRelationships.boardLines(unnamed);
        assertEquals(1, board.size());
        assertTrue(board.get(0).contains("unnamed"));
        assertTrue(board.get(0).contains("unknown"));

        JsonNode noDot = MAPPER.readTree(appWith(
                "{\"toId\":\"only-name\",\"toType\":\"Service\",\"rel\":\"helmresource\",\"state\":\"active\"}"));
        assertTrue(HelmRelationships.boardLines(noDot).get(0).contains("Service only-name"));
    }

    private static String appWith(String relationship) {
        return "{\"status\":{\"summary\":{\"state\":\"deployed\"}},\"relationships\":[" + relationship + "]}";
    }

    private static String deployment(String state, String message) {
        return "{\"toId\":\"default/demo-nginx\",\"toType\":\"apps.deployment\","
                + "\"rel\":\"helmresource\",\"state\":\"" + state + "\",\"message\":\"" + message + "\"}";
    }
}
