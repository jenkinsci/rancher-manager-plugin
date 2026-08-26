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

    private HelmRelationships() {
    }

    static Gate gate(JsonNode app) {
        for (JsonNode rel : helmResources(app)) {
            if (!isWorkload(rel)) {
                continue;
            }
            if (!isActive(rel)) {
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
            if (!isWorkload(rel) || isActive(rel)) {
                continue;
            }
            parts.add(formatLine("", rel));
        }
        return String.join("; ", parts);
    }

    private static List<JsonNode> helmResources(JsonNode app) {
        List<JsonNode> out = new ArrayList<>();
        if (app == null || app.isNull() || app.isMissingNode()) {
            return out;
        }
        JsonNode rels = app.path("relationships");
        if (!rels.isArray()) {
            return out;
        }
        for (JsonNode rel : rels) {
            if (!"helmresource".equalsIgnoreCase(RancherClient.text(rel, "rel"))) {
                continue;
            }
            if (isHelmReleaseSecret(rel)) {
                continue;
            }
            out.add(rel);
        }
        return out;
    }

    private static boolean isHelmReleaseSecret(JsonNode rel) {
        String toType = RancherClient.text(rel, "toType").toLowerCase(Locale.ROOT);
        String toId = RancherClient.text(rel, "toId");
        return "secret".equals(toType) && toId.contains(HELM_RELEASE_SECRET);
    }

    private static boolean isWorkload(JsonNode rel) {
        return WORKLOAD_TYPES.contains(RancherClient.text(rel, "toType").toLowerCase(Locale.ROOT));
    }

    private static boolean isActive(JsonNode rel) {
        return "active".equalsIgnoreCase(RancherClient.text(rel, "state"));
    }

    private static String formatLine(String prefix, JsonNode rel) {
        String type = shortType(RancherClient.text(rel, "toType"));
        String name = resourceName(RancherClient.text(rel, "toId"));
        String state = RancherClient.firstNonBlank(RancherClient.text(rel, "state"), "unknown");
        StringBuilder sb = new StringBuilder(prefix).append(type).append(" ").append(name).append(": ").append(state);
        String message = RancherClient.truncateDetail(
                RancherClient.sanitizeErrorDetail(RancherClient.text(rel, "message").replaceAll("\\s+", " ").trim()));
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
