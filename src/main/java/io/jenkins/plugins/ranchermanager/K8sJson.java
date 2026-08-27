package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Steve / Kubernetes JSON field names and path helpers. */
final class K8sJson {

    static final String API_VERSION = "apiVersion";
    static final String METADATA = "metadata";
    static final String NAMESPACE = "namespace";
    static final String ANNOTATIONS = "annotations";
    static final String STATUS = "status";
    static final String STATE = "state";
    static final String SUMMARY = "summary";
    static final String ERROR = "error";
    static final String TRANSITIONING = "transitioning";
    static final String MESSAGE = "message";
    static final String NAME = "name";

    private K8sJson() {}

    static boolean missing(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    static JsonNode metadata(JsonNode node) {
        return node.path(METADATA);
    }

    static JsonNode status(JsonNode node) {
        return node.path(STATUS);
    }

    static JsonNode summary(JsonNode node) {
        return status(node).path(SUMMARY);
    }

    static JsonNode metadataState(JsonNode node) {
        return metadata(node).path(STATE);
    }

    static String namespaceOrDefault(String namespace) {
        return namespace == null || namespace.isBlank() ? RancherNamespaces.DEFAULT : namespace.trim();
    }

    static ArrayNode collectionItems(JsonNode list) {
        if (missing(list)) {
            return JsonNodeFactory.instance.arrayNode();
        }
        JsonNode data = list.path("data");
        if (data.isArray()) {
            return (ArrayNode) data;
        }
        JsonNode items = list.path("items");
        if (items.isArray()) {
            return (ArrayNode) items;
        }
        return JsonNodeFactory.instance.arrayNode();
    }
}
