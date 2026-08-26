package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

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
        for (JsonNode pod : collectionItems(list)) {
            if (!belongsToRelease(pod, rel)) {
                continue;
            }
            String line = problemLine(pod);
            if (!line.isBlank()) {
                out.add(line);
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

    private static String problemLine(JsonNode pod) {
        String name = RancherClient.firstNonBlank(
                RancherClient.text(pod.path("metadata"), "name"), "pod");
        String waiting = firstWaiting(pod, name);
        if (!waiting.isBlank()) {
            return waiting;
        }
        String condition = firstFalseCondition(pod, name);
        if (!condition.isBlank()) {
            return condition;
        }
        String phase = RancherClient.text(pod.path("status"), "phase");
        if ("Pending".equalsIgnoreCase(phase) || "Failed".equalsIgnoreCase(phase)) {
            return "pod " + name + ": " + phase;
        }
        return "";
    }

    private static String firstWaiting(JsonNode pod, String podName) {
        JsonNode status = pod.path("status");
        String line = firstWaitingIn(status.path("initContainerStatuses"), podName);
        if (!line.isBlank()) {
            return line;
        }
        return firstWaitingIn(status.path("containerStatuses"), podName);
    }

    private static String firstWaitingIn(JsonNode statuses, String podName) {
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

    private static String firstFalseCondition(JsonNode pod, String podName) {
        JsonNode conditions = pod.path("status").path("conditions");
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

    static boolean belongsToRelease(JsonNode workload, String releaseName) {
        if (workload == null || releaseName == null || releaseName.isBlank()) {
            return false;
        }
        JsonNode meta = workload.path("metadata");
        String instance = RancherClient.text(meta.path("labels"), INSTANCE_LABEL);
        if (releaseName.equals(instance)) {
            return true;
        }
        return releaseName.equals(RancherClient.text(meta.path("annotations"), RELEASE_ANNOTATION));
    }

    static ArrayNode collectionItems(JsonNode list) {
        if (list == null || list.isNull() || list.isMissingNode()) {
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
