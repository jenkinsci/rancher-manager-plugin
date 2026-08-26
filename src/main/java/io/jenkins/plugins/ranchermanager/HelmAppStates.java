package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Set;

/** Terminal vs in-flight Helm catalog app (Steve {@code catalog.cattle.io.apps}). */
final class HelmAppStates {

    static final int DEFAULT_TIMEOUT_SECONDS = 300;
    static final String POLL_INTERVAL_MS_PROP = "rancher.helm.pollIntervalMs";
    static final long DEFAULT_POLL_INTERVAL_MS = 2000L;

    private static final Set<String> READY = Set.of("deployed", "active", "installed");
    private static final Set<String> FAILED = Set.of("failed", "error", "unsuccessful");
    private static final Set<String> IN_FLIGHT = Set.of(
            "transitioning",
            "pending-install",
            "pending-upgrade",
            "pending-rollback",
            "installing",
            "upgrading",
            "uninstalling");

    enum Progress {
        READY,
        FAILED,
        WAITING
    }

    private HelmAppStates() {
    }

    static long pollIntervalMs() {
        String raw = System.getProperty(POLL_INTERVAL_MS_PROP);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_POLL_INTERVAL_MS;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value < 1L ? 1L : value;
        } catch (NumberFormatException e) {
            return DEFAULT_POLL_INTERVAL_MS;
        }
    }

    static int parseTimeoutSeconds(String configured) {
        String raw = configured == null || configured.isBlank()
                ? String.valueOf(DEFAULT_TIMEOUT_SECONDS)
                : configured.trim();
        int seconds;
        try {
            seconds = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Wait timeout must be a positive number of seconds.");
        }
        if (seconds <= 0) {
            throw new IllegalArgumentException("Wait timeout must be a positive number of seconds.");
        }
        return seconds;
    }

    static Progress classify(JsonNode app) {
        return classify(app, null);
    }

    /**
     * {@code before} is the app from GET before catalog install/upgrade. The same snapshot
     * after POST is still in-flight, not the new operation's result.
     */
    static Progress classify(JsonNode app, JsonNode before) {
        if (app == null || app.isNull() || app.isMissingNode()) {
            return Progress.WAITING;
        }
        if (inFlight(app, before)) {
            return Progress.WAITING;
        }
        JsonNode summary = app.path("status").path("summary");
        String state = stateOf(app);
        if (summary.path("error").asBoolean(false) || FAILED.contains(state)) {
            return Progress.FAILED;
        }
        if (READY.contains(state)) {
            return Progress.READY;
        }
        return Progress.WAITING;
    }

    static boolean thisOperation(JsonNode app, JsonNode before) {
        if (app == null || app.isNull() || app.isMissingNode()) {
            return true;
        }
        return !unchangedSince(app, before);
    }

    static String displayState(JsonNode app) {
        if (app == null || app.isNull() || app.isMissingNode()) {
            return "missing";
        }
        String state = stateOf(app);
        return state.isBlank() ? "unknown" : state;
    }

    static String failureDetail(JsonNode app) {
        if (app == null) {
            return "";
        }
        JsonNode summary = app.path("status").path("summary");
        return RancherClient.firstNonBlank(
                RancherClient.text(summary, "message"),
                RancherClient.text(app.path("status"), "message"),
                RancherClient.text(summary, "state"),
                RancherClient.text(app.path("status"), "state"));
    }

    private static boolean inFlight(JsonNode app, JsonNode before) {
        JsonNode summary = app.path("status").path("summary");
        if (summary.path("transitioning").asBoolean(false)) {
            return true;
        }
        if (IN_FLIGHT.contains(stateOf(app))) {
            return true;
        }
        long generation = app.path("metadata").path("generation").asLong(0L);
        if (generation > 0L && app.path("status").path("observedGeneration").asLong(0L) < generation) {
            return true;
        }
        return unchangedSince(app, before);
    }

    private static boolean unchangedSince(JsonNode app, JsonNode before) {
        if (before == null || before.isNull() || before.isMissingNode()) {
            return false;
        }
        String rv = RancherClient.text(app.path("metadata"), "resourceVersion");
        String rvBefore = RancherClient.text(before.path("metadata"), "resourceVersion");
        if (!rv.isBlank() && !rvBefore.isBlank()) {
            return rv.equals(rvBefore);
        }
        return fingerprint(app).equals(fingerprint(before));
    }

    private static String fingerprint(JsonNode app) {
        JsonNode summary = app.path("status").path("summary");
        return stateOf(app)
                + "|"
                + summary.path("error").asBoolean(false)
                + "|"
                + summary.path("transitioning").asBoolean(false)
                + "|"
                + app.path("status").path("observedGeneration").asLong(0L);
    }

    private static String stateOf(JsonNode app) {
        return RancherClient.firstNonBlank(
                        RancherClient.text(app.path("status").path("summary"), "state"),
                        RancherClient.text(app.path("status"), "state"))
                .toLowerCase(Locale.ROOT);
    }
}
