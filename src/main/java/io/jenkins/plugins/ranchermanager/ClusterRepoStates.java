package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/** ClusterRepo index after {@code spec.forceUpdate} PUT (Steve {@code catalog.cattle.io.clusterrepos}). */
final class ClusterRepoStates {

    private static final Set<String> READY = Set.of("downloaded", "active", "ready");
    private static final Set<String> FAILED = Set.of("failed", K8sJson.ERROR, "unsuccessful");
    private static final Set<String> IN_FLIGHT = Set.of(
            K8sJson.TRANSITIONING,
            "downloading",
            "pending",
            "updating");

    enum Progress {
        READY,
        FAILED,
        WAITING
    }

    private ClusterRepoStates() {
    }

    static Progress classify(JsonNode repo, JsonNode before) {
        if (K8sJson.missing(repo)) {
            return Progress.WAITING;
        }
        if (SteveCatalog.inFlight(repo, before, IN_FLIGHT, ClusterRepoStates::fingerprint)) {
            return Progress.WAITING;
        }
        String state = SteveCatalog.stateOf(repo);
        if (SteveCatalog.summaryError(repo) || FAILED.contains(state)) {
            return Progress.FAILED;
        }
        if (READY.contains(state) || downloadedCondition(repo)) {
            return Progress.READY;
        }
        if (before != null && !fingerprint(repo).equals(fingerprint(before))) {
            return Progress.READY;
        }
        return Progress.WAITING;
    }

    static String failureDetail(JsonNode repo) {
        return SteveCatalog.failureDetail(repo);
    }

    private static boolean downloadedCondition(JsonNode repo) {
        JsonNode conditions = K8sJson.status(repo).path("conditions");
        if (!conditions.isArray()) {
            return false;
        }
        for (JsonNode condition : conditions) {
            String type = RancherClient.text(condition, "type");
            String status = RancherClient.text(condition, K8sJson.STATUS);
            if ("downloaded".equalsIgnoreCase(type) && "true".equalsIgnoreCase(status)) {
                return true;
            }
        }
        return false;
    }

    private static String fingerprint(JsonNode repo) {
        JsonNode status = K8sJson.status(repo);
        return SteveCatalog.fingerprint(
                repo,
                RancherClient.text(status, "downloadTime") + "|" + status.path("observedGeneration").asLong(0L));
    }
}
