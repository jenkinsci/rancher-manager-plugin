package io.jenkins.plugins.ranchermanager;

import java.util.Locale;

/**
 * Manifest source for {@code rancherManifest}: Git repository (default) or inline YAML.
 * Freestyle {@code f:radioBlock inline="true"} and Pipeline both bind a plain string.
 */
final class ManifestSource {

    static final String REPOSITORY = "repository";
    static final String YAML = "yaml";

    private ManifestSource() {
    }

    static String normalize(String raw) {
        return normalize(raw, REPOSITORY);
    }

    static String normalize(String raw, String defaultMode) {
        if (raw == null || raw.isBlank()) {
            return defaultMode;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (REPOSITORY.equals(v) || YAML.equals(v)) {
            return v;
        }
        if ("git".equals(v) || "repo".equals(v)) {
            return REPOSITORY;
        }
        if ("manual".equals(v) || "string".equals(v) || "file".equals(v)) {
            return YAML;
        }
        return defaultMode;
    }

    static boolean isYaml(String mode) {
        return YAML.equals(normalize(mode));
    }

    static boolean isRepository(String mode) {
        return !isYaml(mode);
    }
}
