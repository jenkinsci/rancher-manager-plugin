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
        if (K8sJson.missing(resource)) {
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
        if (K8sJson.missing(resource)) {
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
                    "ready=" + intStatus(resource, "numberReady")
                            + "/"
                            + intStatus(resource, "desiredNumberScheduled");
            case JOB ->
                    "succeeded=" + intStatus(resource, "succeeded")
                            + "/"
                            + Math.max(1, intPath(resource, "spec", "completions"));
        };
    }

    private static boolean steveActive(JsonNode resource) {
        return "active".equalsIgnoreCase(steveStateName(resource));
    }

    private static boolean steveErrorOrTransitioning(JsonNode resource) {
        JsonNode state = K8sJson.metadataState(resource);
        if (state.path(K8sJson.TRANSITIONING).asBoolean(false) || state.path(K8sJson.ERROR).asBoolean(false)) {
            return true;
        }
        String name = steveStateName(resource).toLowerCase(Locale.ROOT);
        return "updating".equals(name)
                || "in-progress".equals(name)
                || "pending".equals(name)
                || K8sJson.ERROR.equals(name)
                || "failed".equals(name);
    }

    private static String steveStateName(JsonNode resource) {
        JsonNode state = K8sJson.metadataState(resource);
        return RancherClient.firstNonBlank(
                RancherClient.text(state, K8sJson.NAME),
                RancherClient.text(state, K8sJson.MESSAGE));
    }

    private static Progress replicasReady(JsonNode resource) {
        int desired = desiredReplicas(resource);
        if (desired <= 0) {
            return Progress.READY;
        }
        return readyReplicas(resource) >= desired ? Progress.READY : Progress.WAITING;
    }

    private static Progress daemonSetReady(JsonNode resource) {
        int desired = intStatus(resource, "desiredNumberScheduled");
        if (desired <= 0) {
            return Progress.WAITING;
        }
        return intStatus(resource, "numberReady") >= desired ? Progress.READY : Progress.WAITING;
    }

    private static Progress jobReady(JsonNode resource) {
        int completions = Math.max(1, intPath(resource, "spec", "completions"));
        int succeeded = intStatus(resource, "succeeded");
        if (succeeded >= completions) {
            return Progress.READY;
        }
        JsonNode conditions = K8sJson.status(resource).path("conditions");
        if (conditions.isArray()) {
            for (JsonNode c : conditions) {
                if ("Failed".equalsIgnoreCase(RancherClient.text(c, "type"))
                        && "True".equalsIgnoreCase(RancherClient.text(c, K8sJson.STATUS))) {
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
        int ready = intStatus(resource, "readyReplicas");
        if (ready > 0) {
            return ready;
        }
        return intStatus(resource, "availableReplicas");
    }

    private static int intStatus(JsonNode root, String field) {
        return intPath(root, K8sJson.STATUS, field);
    }

    private static int intPath(JsonNode root, String a, String b) {
        JsonNode n = root.path(a).path(b);
        if (n.isMissingNode() || n.isNull() || !n.isNumber()) {
            return 0;
        }
        return n.asInt();
    }
}
