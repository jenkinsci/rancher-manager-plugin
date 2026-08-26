package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pod problem lines for manifest wait abort (no Helm release label filter).
 */
final class ManifestPods {

    private ManifestPods() {}

    static String problemLine(JsonNode pod) {
        if (pod == null || pod.isNull() || pod.isMissingNode()) {
            return "";
        }
        String name = RancherClient.firstNonBlank(
                RancherClient.text(pod.path("metadata"), "name"), "pod");
        JsonNode status = pod.path("status");
        String waiting = firstWaiting(status.path("initContainerStatuses"), name);
        if (!waiting.isBlank()) {
            return waiting;
        }
        waiting = firstWaiting(status.path("containerStatuses"), name);
        if (!waiting.isBlank()) {
            return waiting;
        }
        String condition = firstFalseCondition(status.path("conditions"), name);
        if (!condition.isBlank()) {
            return condition;
        }
        String phase = RancherClient.text(status, "phase");
        if ("Pending".equalsIgnoreCase(phase) || "Failed".equalsIgnoreCase(phase)) {
            return "pod " + name + ": " + phase;
        }
        return "";
    }

    private static String firstWaiting(JsonNode statuses, String podName) {
        if (!statuses.isArray()) {
            return "";
        }
        for (JsonNode cs : statuses) {
            JsonNode waiting = cs.path("state").path("waiting");
            if (waiting.isMissingNode() || waiting.isNull() || !waiting.isObject()) {
                continue;
            }
            String reason = clean(RancherClient.text(waiting, "reason"));
            String message = clean(RancherClient.text(waiting, "message"));
            if (reason.isBlank() && message.isBlank()) {
                continue;
            }
            String container = RancherClient.firstNonBlank(RancherClient.text(cs, "name"), "container");
            StringBuilder sb = new StringBuilder("pod ").append(podName).append(" container ").append(container);
            if (!reason.isBlank()) {
                sb.append(": ").append(reason);
            }
            if (!message.isBlank()) {
                sb.append(": ").append(message);
            }
            return sb.toString();
        }
        return "";
    }

    private static String firstFalseCondition(JsonNode conditions, String podName) {
        if (!conditions.isArray()) {
            return "";
        }
        for (JsonNode condition : conditions) {
            if (!"False".equalsIgnoreCase(RancherClient.text(condition, "status"))) {
                continue;
            }
            String type = clean(RancherClient.text(condition, "type"));
            String reason = clean(RancherClient.text(condition, "reason"));
            String message = clean(RancherClient.text(condition, "message"));
            if (type.isBlank() && reason.isBlank() && message.isBlank()) {
                continue;
            }
            StringBuilder sb = new StringBuilder("pod ").append(podName);
            String detail = RancherClient.firstNonBlank(reason, type);
            if (!detail.isBlank()) {
                sb.append(": ").append(detail);
            }
            if (!message.isBlank()) {
                sb.append(": ").append(message);
            }
            return sb.toString();
        }
        return "";
    }

    private static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return RancherClient.truncateDetail(
                RancherClient.sanitizeErrorDetail(raw.replaceAll("\\s+", " ").trim()));
    }
}
