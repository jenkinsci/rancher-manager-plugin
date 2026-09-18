package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/** Terminal vs in-flight Helm catalog app (Steve {@code catalog.cattle.io.apps}). */
final class HelmAppStates {

    static final int DEFAULT_TIMEOUT_SECONDS = 300;
    static final String POLL_INTERVAL_MS_PROP = "rancher.helm.pollIntervalMs";
    static final long DEFAULT_POLL_INTERVAL_MS = 2000L;
    static final String SETTLE_TIMEOUT = "Settle timeout";
    static final String HELM_TIMEOUT = "Helm timeout";

    private static final Set<String> READY = Set.of("deployed", "active", "installed");
    private static final Set<String> FAILED = Set.of("failed", K8sJson.ERROR, "unsuccessful");
    private static final Set<String> IN_FLIGHT = Set.of(
            K8sJson.TRANSITIONING,
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
        return parsePositiveSeconds(raw, SETTLE_TIMEOUT);
    }

    static int parsePositiveSeconds(String configured, String controlName) {
        String name = controlName == null || controlName.isBlank() ? SETTLE_TIMEOUT : controlName.trim();
        int seconds;
        try {
            seconds = Integer.parseInt(configured == null ? "" : configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive number of seconds.");
        }
        if (seconds <= 0) {
            throw new IllegalArgumentException(name + " must be a positive number of seconds.");
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
        if (K8sJson.missing(app)) {
            return Progress.WAITING;
        }
        if (SteveCatalog.inFlight(app, before, IN_FLIGHT, HelmAppStates::fingerprint)) {
            return Progress.WAITING;
        }
        String state = SteveCatalog.stateOf(app);
        if (SteveCatalog.summaryError(app) || FAILED.contains(state)) {
            return Progress.FAILED;
        }
        if (READY.contains(state)) {
            return Progress.READY;
        }
        return Progress.WAITING;
    }

    static boolean thisOperation(JsonNode app, JsonNode before) {
        if (K8sJson.missing(app)) {
            return true;
        }
        return !SteveCatalog.unchangedSince(app, before, HelmAppStates::fingerprint);
    }

    static String displayState(JsonNode app) {
        if (K8sJson.missing(app)) {
            return "missing";
        }
        String state = SteveCatalog.stateOf(app);
        return state.isBlank() ? "unknown" : state;
    }

    static String failureDetail(JsonNode app) {
        return SteveCatalog.failureDetail(app);
    }

    private static String fingerprint(JsonNode app) {
        return SteveCatalog.fingerprint(
                app, String.valueOf(K8sJson.statusNode(app).path("observedGeneration").asLong(0L)));
    }
}
