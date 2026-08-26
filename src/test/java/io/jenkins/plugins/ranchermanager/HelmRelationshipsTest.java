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
    public void fingerprint_joinsLines() {
        assertEquals("", HelmRelationships.fingerprint(List.of()));
        assertEquals("a\nb", HelmRelationships.fingerprint(List.of("a", "b")));
    }

    private static String appWith(String relationship) {
        return "{\"status\":{\"summary\":{\"state\":\"deployed\"}},\"relationships\":[" + relationship + "]}";
    }

    private static String deployment(String state, String message) {
        return "{\"toId\":\"default/demo-nginx\",\"toType\":\"apps.deployment\","
                + "\"rel\":\"helmresource\",\"state\":\"" + state + "\",\"message\":\"" + message + "\"}";
    }
}
