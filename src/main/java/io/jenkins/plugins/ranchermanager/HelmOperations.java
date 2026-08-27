package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Locale;

/**
 * Steve catalog {@code chartActionOutput} and GET {@code catalog.cattle.io.operations}.
 * Install/upgrade POST is HTTP 201 with {@code operationName} / {@code operationNamespace},
 * not a full Operation CR. Steve GET copies wrangler kstatus onto {@code metadata.state}
 * ({@code name}, {@code error}, {@code transitioning}, {@code message}).
 */
final class HelmOperations {

    enum Progress {
        ACTIVE,
        FAILED,
        WAITING
    }

    record ChartAction(String operationName, String operationNamespace) {}

    private HelmOperations() {
    }

    static ChartAction parse(JsonNode body) throws IOException {
        JsonNode node = K8sJson.missing(body) ? null : body;
        String name = field(node, "operationName");
        String namespace = field(node, "operationNamespace");
        if (name.isBlank()) {
            throw new IOException(
                    "Catalog install/upgrade response is not chartActionOutput (missing operationName).");
        }
        if (namespace.isBlank()) {
            throw new IOException(
                    "Catalog install/upgrade response is not chartActionOutput (missing operationNamespace).");
        }
        return new ChartAction(name, namespace);
    }

    static Progress classify(JsonNode operation) {
        if (K8sJson.missing(operation)) {
            return Progress.WAITING;
        }
        JsonNode state = steveState(operation);
        if (!state.isObject()) {
            return Progress.WAITING;
        }
        if (state.path(K8sJson.ERROR).asBoolean(false)) {
            return Progress.FAILED;
        }
        if (state.path(K8sJson.TRANSITIONING).asBoolean(false)) {
            return Progress.WAITING;
        }
        return Progress.ACTIVE;
    }

    static String displayState(JsonNode operation) {
        if (K8sJson.missing(operation)) {
            return "missing";
        }
        JsonNode state = steveState(operation);
        String name = RancherClient.text(state, K8sJson.NAME);
        if (!name.isBlank()) {
            return name.trim().toLowerCase(Locale.ROOT);
        }
        if (state.path(K8sJson.ERROR).asBoolean(false)) {
            return K8sJson.ERROR;
        }
        if (state.path(K8sJson.TRANSITIONING).asBoolean(false)) {
            return K8sJson.TRANSITIONING;
        }
        return "unknown";
    }

    static String failureMessage(JsonNode operation) {
        if (K8sJson.missing(operation)) {
            return "";
        }
        return RancherClient.text(steveState(operation), K8sJson.MESSAGE);
    }

    /** Steve GET formatter: {@code metadata.state} only (not root {@code state} or {@code status.summary}). */
    private static JsonNode steveState(JsonNode operation) {
        return K8sJson.metadataState(operation);
    }

    private static String field(JsonNode node, String name) {
        if (node == null) {
            return "";
        }
        String direct = RancherClient.text(node, name);
        if (!direct.isBlank()) {
            return direct.trim();
        }
        return RancherClient.text(node.path("data"), name).trim();
    }
}
