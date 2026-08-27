package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Helm app {@code relationships} (Steve catalog app), not chart YAML. */
final class HelmRelationships {

    enum Gate {
        PASSED,
        NOT_READY
    }

    private static final Set<String> WORKLOAD_TYPES = Set.of(
            "apps.deployment",
            "apps.statefulset",
            "apps.daemonset",
            "batch.job");
    private static final String HELM_RELEASE_SECRET = "sh.helm.release.v1.";
    private static final String TO_TYPE = "toType";

    private HelmRelationships() {
    }

    static Gate gate(JsonNode app) {
        for (JsonNode rel : helmResources(app)) {
            if (isWorkload(rel) && !isActive(rel)) {
                return Gate.NOT_READY;
            }
        }
        return Gate.PASSED;
    }

    static boolean hasInactiveWorkload(JsonNode app) {
        return gate(app) == Gate.NOT_READY;
    }

    static List<String> boardLines(JsonNode app) {
        List<String> lines = new ArrayList<>();
        for (JsonNode rel : helmResources(app)) {
            lines.add(formatLine("Helm ", rel));
        }
        return lines;
    }

    static String fingerprint(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines);
    }

    static String inactiveWorkloads(JsonNode app) {
        List<String> parts = new ArrayList<>();
        for (JsonNode rel : helmResources(app)) {
            if (isWorkload(rel) && !isActive(rel)) {
                parts.add(formatLine("", rel));
            }
        }
        return String.join("; ", parts);
    }

    private static List<JsonNode> helmResources(JsonNode app) {
        List<JsonNode> out = new ArrayList<>();
        if (K8sJson.missing(app)) {
            return out;
        }
        JsonNode rels = app.path("relationships");
        if (!rels.isArray()) {
            return out;
        }
        for (JsonNode rel : rels) {
            if (isTrackedHelmResource(rel)) {
                out.add(rel);
            }
        }
        return out;
    }

    private static boolean isTrackedHelmResource(JsonNode rel) {
        return "helmresource".equalsIgnoreCase(RancherClient.text(rel, "rel")) && !isHelmReleaseSecret(rel);
    }

    private static boolean isHelmReleaseSecret(JsonNode rel) {
        String toId = RancherClient.text(rel, "toId");
        return "secret".equals(toType(rel)) && toId.contains(HELM_RELEASE_SECRET);
    }

    private static boolean isWorkload(JsonNode rel) {
        return WORKLOAD_TYPES.contains(toType(rel));
    }

    private static String toType(JsonNode rel) {
        return RancherClient.text(rel, TO_TYPE).toLowerCase(Locale.ROOT);
    }

    private static boolean isActive(JsonNode rel) {
        return "active".equalsIgnoreCase(RancherClient.text(rel, K8sJson.STATE));
    }

    private static String formatLine(String prefix, JsonNode rel) {
        String type = shortType(RancherClient.text(rel, TO_TYPE));
        String name = resourceName(RancherClient.text(rel, "toId"));
        String state = RancherClient.firstNonBlank(RancherClient.text(rel, K8sJson.STATE), "unknown");
        StringBuilder sb = new StringBuilder(prefix).append(type).append(" ").append(name).append(": ").append(state);
        String message = RancherClient.truncateDetail(
                RancherClient.sanitizeErrorDetail(
                        RancherClient.text(rel, K8sJson.MESSAGE).replaceAll("\\s+", " ").trim()));
        if (!message.isBlank()) {
            sb.append(" (").append(message).append(")");
        }
        return sb.toString();
    }

    private static String shortType(String toType) {
        if (toType == null || toType.isBlank()) {
            return "resource";
        }
        int dot = toType.lastIndexOf('.');
        return dot < 0 ? toType : toType.substring(dot + 1);
    }

    private static String resourceName(String toId) {
        if (toId == null || toId.isBlank()) {
            return "unnamed";
        }
        int slash = toId.lastIndexOf('/');
        return slash < 0 ? toId : toId.substring(slash + 1);
    }
}
