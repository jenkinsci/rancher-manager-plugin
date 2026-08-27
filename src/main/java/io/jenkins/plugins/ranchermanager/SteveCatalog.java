package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Catalog Steve resources ({@code clusterrepos}, {@code apps}): {@code status.summary}
 * plus generation / resourceVersion.
 */
final class SteveCatalog {

    private SteveCatalog() {}

    static boolean inFlight(
            JsonNode resource, JsonNode before, Set<String> inFlightStates, Function<JsonNode, String> fingerprint) {
        if (summaryFlag(resource, K8sJson.TRANSITIONING) || inFlightStates.contains(stateOf(resource))) {
            return true;
        }
        if (generationPending(resource)) {
            return true;
        }
        return unchangedSince(resource, before, fingerprint);
    }

    static boolean summaryError(JsonNode resource) {
        return summaryFlag(resource, K8sJson.ERROR);
    }

    static String stateOf(JsonNode resource) {
        return RancherClient.firstNonBlank(
                        RancherClient.text(K8sJson.summaryNode(resource), K8sJson.STATE),
                        RancherClient.text(K8sJson.statusNode(resource), K8sJson.STATE))
                .toLowerCase(Locale.ROOT);
    }

    static String failureDetail(JsonNode resource) {
        if (resource == null) {
            return "";
        }
        JsonNode summary = K8sJson.summaryNode(resource);
        JsonNode status = K8sJson.statusNode(resource);
        return RancherClient.firstNonBlank(
                RancherClient.text(summary, K8sJson.MESSAGE),
                RancherClient.text(status, K8sJson.MESSAGE),
                RancherClient.text(summary, K8sJson.STATE),
                RancherClient.text(status, K8sJson.STATE));
    }

    static boolean unchangedSince(JsonNode current, JsonNode before, Function<JsonNode, String> fingerprint) {
        if (K8sJson.missing(before)) {
            return false;
        }
        String rv = RancherClient.text(K8sJson.metadataNode(current), "resourceVersion");
        String rvBefore = RancherClient.text(K8sJson.metadataNode(before), "resourceVersion");
        if (!rv.isBlank() && !rvBefore.isBlank()) {
            return rv.equals(rvBefore);
        }
        return fingerprint.apply(current).equals(fingerprint.apply(before));
    }

    static String fingerprint(JsonNode resource, String extra) {
        JsonNode summary = K8sJson.summaryNode(resource);
        return stateOf(resource)
                + "|"
                + summary.path(K8sJson.ERROR).asBoolean(false)
                + "|"
                + summary.path(K8sJson.TRANSITIONING).asBoolean(false)
                + "|"
                + extra;
    }

    private static boolean summaryFlag(JsonNode resource, String field) {
        return K8sJson.summaryNode(resource).path(field).asBoolean(false);
    }

    private static boolean generationPending(JsonNode resource) {
        long generation = K8sJson.metadataNode(resource).path("generation").asLong(0L);
        return generation > 0L && K8sJson.statusNode(resource).path("observedGeneration").asLong(0L) < generation;
    }
}
