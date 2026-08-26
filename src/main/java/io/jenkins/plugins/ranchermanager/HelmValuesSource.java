package io.jenkins.plugins.ranchermanager;

import java.util.Locale;

/**
 * Helm values source for {@code rancherHelm}: none (default), Git repository file, or inline YAML.
 * Freestyle {@code f:radioBlock inline="true"} and Pipeline both bind a plain string.
 */
final class HelmValuesSource {

    static final String NONE = "none";
    static final String REPOSITORY = "repository";
    static final String YAML = "yaml";

    private HelmValuesSource() {
    }

    static String resolve(String valuesSource, String values) {
        if (valuesSource != null && !valuesSource.isBlank()) {
            return normalize(valuesSource, NONE);
        }
        if (values != null && !values.isBlank()) {
            return YAML;
        }
        return NONE;
    }

    static String normalize(String raw) {
        return normalize(raw, NONE);
    }

    static String normalize(String raw, String defaultMode) {
        if (raw == null || raw.isBlank()) {
            return defaultMode;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (NONE.equals(v) || REPOSITORY.equals(v) || YAML.equals(v)) {
            return v;
        }
        return aliasOrDefault(v, defaultMode);
    }

    private static String aliasOrDefault(String v, String defaultMode) {
        if ("off".equals(v) || "disabled".equals(v) || "empty".equals(v) || "chart".equals(v)) {
            return NONE;
        }
        if ("git".equals(v) || "repo".equals(v)) {
            return REPOSITORY;
        }
        if ("manual".equals(v) || "string".equals(v) || "file".equals(v) || "inline".equals(v)) {
            return YAML;
        }
        return defaultMode;
    }

    static boolean isNone(String mode) {
        return NONE.equals(normalize(mode));
    }

    static boolean isRepository(String mode) {
        return REPOSITORY.equals(normalize(mode));
    }

    static boolean isYaml(String mode) {
        return YAML.equals(normalize(mode));
    }
}
