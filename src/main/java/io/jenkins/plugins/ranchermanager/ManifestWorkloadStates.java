package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/** Readiness of a Steve/k8s workload GET after manifest apply. */
final class ManifestWorkloadStates {

    enum Progress {
        READY,
        WAITING
    }

    private ManifestWorkloadStates() {}

    static Progress classify(ManifestWorkloads.Kind kind, JsonNode resource) {
        if (resource == null || resource.isNull() || resource.isMissingNode()) {
            return Progress.WAITING;
        }
        if (steveActive(resource)) {
            return Progress.READY;
        }
        if (steveErrorOrTransitioning(resource)) {
            return Progress.WAITING;
        }
        return switch (kind) {
            case DEPLOYMENT, STATEFULSET -> replicasReady(resource);
            case DAEMONSET -> daemonSetReady(resource);
            case JOB -> jobReady(resource);
        };
    }

    static String displayState(ManifestWorkloads.Kind kind, JsonNode resource) {
        if (resource == null || resource.isNull() || resource.isMissingNode()) {
            return "missing";
        }
        String steve = steveStateName(resource);
        if (!steve.isBlank()) {
            return steve;
        }
        return switch (kind) {
            case DEPLOYMENT, STATEFULSET ->
                    "ready=" + readyReplicas(resource) + "/" + desiredReplicas(resource);
            case DAEMONSET ->
                    "ready=" + intPath(resource, "status", "numberReady")
                            + "/"
                            + intPath(resource, "status", "desiredNumberScheduled");
            case JOB ->
                    "succeeded=" + intPath(resource, "status", "succeeded")
                            + "/"
                            + Math.max(1, intPath(resource, "spec", "completions"));
        };
    }

    private static boolean steveActive(JsonNode resource) {
        return "active".equalsIgnoreCase(steveStateName(resource));
    }

    private static boolean steveErrorOrTransitioning(JsonNode resource) {
        JsonNode state = resource.path("metadata").path("state");
        if (state.path("transitioning").asBoolean(false)) {
            return true;
        }
        if (state.path("error").asBoolean(false)) {
            return true;
        }
        String name = steveStateName(resource).toLowerCase(Locale.ROOT);
        return "updating".equals(name)
                || "in-progress".equals(name)
                || "pending".equals(name)
                || "error".equals(name)
                || "failed".equals(name);
    }

    private static String steveStateName(JsonNode resource) {
        return RancherClient.firstNonBlank(
                RancherClient.text(resource.path("metadata").path("state"), "name"),
                RancherClient.text(resource.path("metadata").path("state"), "message"));
    }

    private static Progress replicasReady(JsonNode resource) {
        int desired = desiredReplicas(resource);
        if (desired <= 0) {
            return Progress.READY;
        }
        return readyReplicas(resource) >= desired ? Progress.READY : Progress.WAITING;
    }

    private static Progress daemonSetReady(JsonNode resource) {
        int desired = intPath(resource, "status", "desiredNumberScheduled");
        if (desired <= 0) {
            return Progress.WAITING;
        }
        int ready = intPath(resource, "status", "numberReady");
        return ready >= desired ? Progress.READY : Progress.WAITING;
    }

    private static Progress jobReady(JsonNode resource) {
        int completions = Math.max(1, intPath(resource, "spec", "completions"));
        int succeeded = intPath(resource, "status", "succeeded");
        if (succeeded >= completions) {
            return Progress.READY;
        }
        JsonNode conditions = resource.path("status").path("conditions");
        if (conditions.isArray()) {
            for (JsonNode c : conditions) {
                if ("Failed".equalsIgnoreCase(RancherClient.text(c, "type"))
                        && "True".equalsIgnoreCase(RancherClient.text(c, "status"))) {
                    return Progress.WAITING;
                }
            }
        }
        return Progress.WAITING;
    }

    private static int desiredReplicas(JsonNode resource) {
        int spec = intPath(resource, "spec", "replicas");
        return spec < 0 ? 1 : spec;
    }

    private static int readyReplicas(JsonNode resource) {
        int ready = intPath(resource, "status", "readyReplicas");
        if (ready > 0) {
            return ready;
        }
        return intPath(resource, "status", "availableReplicas");
    }

    private static int intPath(JsonNode root, String a, String b) {
        JsonNode n = root.path(a).path(b);
        if (n.isMissingNode() || n.isNull() || !n.isNumber()) {
            return 0;
        }
        return n.asInt();
    }
}
