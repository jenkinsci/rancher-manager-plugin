package io.jenkins.plugins.ranchermanager;

import hudson.EnvVars;

import java.util.Locale;
import java.util.regex.Pattern;

/** Rancher project field for Helm (name or {@code p-xxxxx}). Cluster stays on {@code clusterId}. */
final class RancherProjects {

    private static final Pattern PROJECT_ID = Pattern.compile("^p-[a-z0-9]+$");

    private RancherProjects() {
    }

    static String resolve(String configured, EnvVars buildEnv) {
        String raw = configured == null ? "" : configured.trim();
        String expanded = buildEnv == null ? raw : buildEnv.expand(raw).trim();
        String err = validate(expanded);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        return expanded;
    }

    static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Project is required.";
        }
        String project = raw.trim();
        if (project.indexOf(':') >= 0) {
            return "Project must be the Rancher project name or id (p-xxxxx)."
                    + " Cluster belongs in Cluster ID, not Project.";
        }
        return null;
    }

    static boolean looksLikeProjectId(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return PROJECT_ID.matcher(value.trim().toLowerCase(Locale.ROOT)).matches();
    }
}
