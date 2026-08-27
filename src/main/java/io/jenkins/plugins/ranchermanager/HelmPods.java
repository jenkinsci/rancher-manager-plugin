package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Waiting / not-Ready Helm pods (labels, not chart YAML). */
final class HelmPods {

    static final String INSTANCE_LABEL = "app.kubernetes.io/instance";
    static final String RELEASE_ANNOTATION = "meta.helm.sh/release-name";

    private HelmPods() {
    }

    static List<String> problems(JsonNode list, String releaseName) {
        String rel = releaseName == null ? "" : releaseName.trim();
        List<String> out = new ArrayList<>();
        if (rel.isEmpty()) {
            return out;
        }
        for (JsonNode pod : K8sJson.collectionItems(list)) {
            if (belongsToRelease(pod, rel)) {
                String line = problemLine(pod);
                if (!line.isBlank()) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    static String fingerprint(List<String> problems) {
        if (problems == null || problems.isEmpty()) {
            return "";
        }
        return String.join("\n", problems);
    }

    static String joined(List<String> problems) {
        if (problems == null || problems.isEmpty()) {
            return "";
        }
        return String.join("; ", problems);
    }

    static String problemLine(JsonNode pod) {
        if (K8sJson.missing(pod)) {
            return "";
        }
        String name = RancherClient.firstNonBlank(
                RancherClient.text(K8sJson.metadataNode(pod), K8sJson.NAME), "pod");
        JsonNode status = K8sJson.statusNode(pod);
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
            String line = waitingLine(cs, podName);
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
    }

    private static String waitingLine(JsonNode cs, String podName) {
        JsonNode waiting = cs.path(K8sJson.STATE).path("waiting");
        if (waiting.isMissingNode() || waiting.isNull() || !waiting.isObject()) {
            return "";
        }
        String reason = clean(RancherClient.text(waiting, "reason"));
        String message = clean(RancherClient.text(waiting, K8sJson.MESSAGE));
        if (reason.isBlank() && message.isBlank()) {
            return "";
        }
        String container = RancherClient.firstNonBlank(RancherClient.text(cs, K8sJson.NAME), "container");
        StringBuilder sb = new StringBuilder("pod ").append(podName).append(" container ").append(container);
        if (!reason.isBlank()) {
            sb.append(": ").append(reason);
        }
        if (!message.isBlank()) {
            sb.append(": ").append(message);
        }
        return sb.toString();
    }

    private static String firstFalseCondition(JsonNode conditions, String podName) {
        if (!conditions.isArray()) {
            return "";
        }
        for (JsonNode condition : conditions) {
            String line = falseConditionLine(condition, podName);
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
    }

    private static String falseConditionLine(JsonNode condition, String podName) {
        if (!"False".equalsIgnoreCase(RancherClient.text(condition, K8sJson.STATUS))) {
            return "";
        }
        String type = clean(RancherClient.text(condition, "type"));
        String reason = clean(RancherClient.text(condition, "reason"));
        String message = clean(RancherClient.text(condition, K8sJson.MESSAGE));
        if (type.isBlank() && reason.isBlank() && message.isBlank()) {
            return "";
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

    private static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return RancherClient.truncateDetail(
                RancherClient.sanitizeErrorDetail(raw.replaceAll("\\s+", " ").trim()));
    }

    static boolean belongsToRelease(JsonNode workload, String releaseName) {
        if (workload == null || releaseName == null || releaseName.isBlank()) {
            return false;
        }
        JsonNode meta = K8sJson.metadataNode(workload);
        String instance = RancherClient.text(meta.path("labels"), INSTANCE_LABEL);
        if (releaseName.equals(instance)) {
            return true;
        }
        return releaseName.equals(RancherClient.text(meta.path(K8sJson.ANNOTATIONS), RELEASE_ANNOTATION));
    }
}
