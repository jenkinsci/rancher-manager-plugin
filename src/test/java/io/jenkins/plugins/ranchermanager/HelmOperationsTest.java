package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HelmOperationsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void parse_readsChartActionOutputFields() throws Exception {
        JsonNode body = MAPPER.readTree(
                "{\"type\":\"chartActionOutput\",\"operationName\":\"helm-operation-kqnjb\","
                        + "\"operationNamespace\":\"nginx-ingress\"}");
        HelmOperations.ChartAction action = HelmOperations.parse(body);
        assertEquals("helm-operation-kqnjb", action.operationName());
        assertEquals("nginx-ingress", action.operationNamespace());
    }

    @Test
    public void parse_readsNestedData() throws Exception {
        JsonNode body = MAPPER.readTree(
                "{\"data\":{\"operationName\":\"helm-operation-a\",\"operationNamespace\":\"apps\"}}");
        HelmOperations.ChartAction action = HelmOperations.parse(body);
        assertEquals("helm-operation-a", action.operationName());
        assertEquals("apps", action.operationNamespace());
    }

    @Test
    public void parse_missingNameAborts() throws Exception {
        JsonNode empty = MAPPER.readTree("{}");
        IOException e = assertThrows(IOException.class, () -> HelmOperations.parse(empty));
        assertEquals(
                "Catalog install/upgrade response is not chartActionOutput (missing operationName).",
                e.getMessage());
    }

    @Test
    public void classify_metadataState() throws Exception {
        assertEquals(HelmOperations.Progress.FAILED, HelmOperations.classify(op(true, false, "error")));
        assertEquals(HelmOperations.Progress.WAITING, HelmOperations.classify(op(false, true, "in-progress")));
        assertEquals(HelmOperations.Progress.ACTIVE, HelmOperations.classify(op(false, false, "active")));
        assertEquals(HelmOperations.Progress.WAITING, HelmOperations.classify(null));
        assertEquals("error", HelmOperations.displayState(op(true, false, "error")));
        JsonNode failed = MAPPER.readTree(
                "{\"metadata\":{\"name\":\"helm-operation-lgdhr\",\"namespace\":\"nginx-ingress\","
                        + "\"state\":{\"error\":true,\"name\":\"error\",\"message\":\"exit code: 123\","
                        + "\"transitioning\":false}},\"status\":{\"action\":\"upgrade\",\"podCreated\":true}}");
        assertEquals(HelmOperations.Progress.FAILED, HelmOperations.classify(failed));
        assertEquals("exit code: 123", HelmOperations.failureMessage(failed));
    }

    @Test
    public void classify_errorWinsOverTransitioning() throws Exception {
        assertEquals(HelmOperations.Progress.FAILED, HelmOperations.classify(op(true, true, "error")));
    }

    @Test
    public void parse_missingNamespaceAborts() throws Exception {
        JsonNode body = MAPPER.readTree("{\"operationName\":\"op\"}");
        IOException e = assertThrows(IOException.class, () -> HelmOperations.parse(body));
        assertTrue(e.getMessage().contains("missing operationNamespace"));
        IOException missingBody = assertThrows(IOException.class, () -> HelmOperations.parse(null));
        assertTrue(missingBody.getMessage().contains("missing operationName"));
    }

    @Test
    public void displayState_missingAndFlagsWithoutName() throws Exception {
        assertEquals("missing", HelmOperations.displayState(null));
        assertEquals("", HelmOperations.failureMessage(null));
        JsonNode errorNoName = MAPPER.readTree(
                "{\"metadata\":{\"state\":{\"error\":true,\"transitioning\":false}}}");
        assertEquals("error", HelmOperations.displayState(errorNoName));
        JsonNode transitioningNoName = MAPPER.readTree(
                "{\"metadata\":{\"state\":{\"error\":false,\"transitioning\":true}}}");
        assertEquals("transitioning", HelmOperations.displayState(transitioningNoName));
        assertEquals(HelmOperations.Progress.WAITING, HelmOperations.classify(MAPPER.readTree("{}")));
    }

    @Test
    public void classify_ignoresRootStateAndStatusSummary() throws Exception {
        JsonNode rootOnly = MAPPER.readTree(
                "{\"state\":{\"error\":true,\"name\":\"error\",\"message\":\"exit code: 123\","
                        + "\"transitioning\":false}}");
        JsonNode summaryOnly = MAPPER.readTree(
                "{\"status\":{\"summary\":{\"error\":true,\"state\":\"error\",\"message\":\"exit code: 123\"}}}");
        assertEquals(HelmOperations.Progress.WAITING, HelmOperations.classify(rootOnly));
        assertEquals("unknown", HelmOperations.displayState(rootOnly));
        assertEquals(HelmOperations.Progress.WAITING, HelmOperations.classify(summaryOnly));
        assertEquals("unknown", HelmOperations.displayState(summaryOnly));
    }

    private static JsonNode op(boolean error, boolean transitioning, String name) throws Exception {
        return MAPPER.readTree(
                "{\"metadata\":{\"state\":{\"error\":"
                        + error
                        + ",\"transitioning\":"
                        + transitioning
                        + ",\"name\":\""
                        + name
                        + "\"}}}");
    }
}
