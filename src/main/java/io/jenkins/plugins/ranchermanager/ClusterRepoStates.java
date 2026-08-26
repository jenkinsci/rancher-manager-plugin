package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Set;

/** ClusterRepo index after {@code spec.forceUpdate} PUT (Steve {@code catalog.cattle.io.clusterrepos}). */
final class ClusterRepoStates {

    private static final Set<String> READY = Set.of("downloaded", "active", "ready");
    private static final Set<String> FAILED = Set.of("failed", "error", "unsuccessful");
    private static final Set<String> IN_FLIGHT = Set.of(
            "transitioning",
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
        if (repo == null || repo.isNull() || repo.isMissingNode()) {
            return Progress.WAITING;
        }
        if (inFlight(repo, before)) {
            return Progress.WAITING;
        }
        JsonNode summary = repo.path("status").path("summary");
        String state = stateOf(repo);
        if (summary.path("error").asBoolean(false) || FAILED.contains(state)) {
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
        if (repo == null) {
            return "";
        }
        JsonNode summary = repo.path("status").path("summary");
        return RancherClient.firstNonBlank(
                RancherClient.text(summary, "message"),
                RancherClient.text(repo.path("status"), "message"),
                RancherClient.text(summary, "state"),
                RancherClient.text(repo.path("status"), "state"));
    }

    private static boolean inFlight(JsonNode repo, JsonNode before) {
        JsonNode summary = repo.path("status").path("summary");
        if (summary.path("transitioning").asBoolean(false)) {
            return true;
        }
        if (IN_FLIGHT.contains(stateOf(repo))) {
            return true;
        }
        long generation = repo.path("metadata").path("generation").asLong(0L);
        if (generation > 0L && repo.path("status").path("observedGeneration").asLong(0L) < generation) {
            return true;
        }
        return unchangedSince(repo, before);
    }

    private static boolean unchangedSince(JsonNode repo, JsonNode before) {
        if (before == null || before.isNull() || before.isMissingNode()) {
            return false;
        }
        String rv = RancherClient.text(repo.path("metadata"), "resourceVersion");
        String rvBefore = RancherClient.text(before.path("metadata"), "resourceVersion");
        if (!rv.isBlank() && !rvBefore.isBlank()) {
            return rv.equals(rvBefore);
        }
        return fingerprint(repo).equals(fingerprint(before));
    }

    private static boolean downloadedCondition(JsonNode repo) {
        JsonNode conditions = repo.path("status").path("conditions");
        if (!conditions.isArray()) {
            return false;
        }
        for (JsonNode condition : conditions) {
            String type = RancherClient.text(condition, "type");
            String status = RancherClient.text(condition, "status");
            if ("downloaded".equalsIgnoreCase(type) && "true".equalsIgnoreCase(status)) {
                return true;
            }
        }
        return false;
    }

    private static String fingerprint(JsonNode repo) {
        JsonNode status = repo.path("status");
        JsonNode summary = status.path("summary");
        return stateOf(repo)
                + "|"
                + summary.path("error").asBoolean(false)
                + "|"
                + summary.path("transitioning").asBoolean(false)
                + "|"
                + RancherClient.text(status, "downloadTime")
                + "|"
                + status.path("observedGeneration").asLong(0L);
    }

    private static String stateOf(JsonNode repo) {
        return RancherClient.firstNonBlank(
                        RancherClient.text(repo.path("status").path("summary"), "state"),
                        RancherClient.text(repo.path("status"), "state"))
                .toLowerCase(Locale.ROOT);
    }
}
