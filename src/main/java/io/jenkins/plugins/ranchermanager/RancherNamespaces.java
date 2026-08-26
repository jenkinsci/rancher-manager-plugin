package io.jenkins.plugins.ranchermanager;

import hudson.EnvVars;

import java.io.IOException;
import java.util.regex.Pattern;

/** Kubernetes namespace helpers for Helm (Manifest does not take a namespace field). */
final class RancherNamespaces {

    static final String DEFAULT = "default";
    private static final Pattern NAMESPACE_PATTERN =
            Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

    private RancherNamespaces() {
    }

    static void requireInProject(
            RancherClient client,
            String baseUrl,
            String apiToken,
            String clusterId,
            String namespace,
            RancherClient.ResolvedProject project,
            boolean createIfMissing,
            RancherBuildLogger log) throws IOException {
        log.info(
                "Namespace="
                        + namespace
                        + " project="
                        + project.name()
                        + " createIfMissing="
                        + createIfMissing);
        String result = client.prepareNamespaceInProject(
                baseUrl, apiToken, clusterId, namespace, project, createIfMissing);
        if ("created".equals(result)) {
            log.info(
                    "Namespace ready name="
                            + namespace
                            + " result="
                            + result
                            + " (ResourceQuota "
                            + RancherClient.RESOURCE_QUOTA_NAME
                            + " and LimitRange "
                            + RancherClient.LIMIT_RANGE_NAME
                            + " applied)");
        } else {
            log.info("Namespace ready name=" + namespace + " result=" + result);
        }
    }

    static String resolve(String configured, EnvVars buildEnv) {
        String raw = configured == null || configured.isBlank() ? DEFAULT : configured.trim();
        String expanded = buildEnv == null ? raw : buildEnv.expand(raw).trim();
        if (expanded.isBlank()) {
            expanded = DEFAULT;
        }
        String err = validate(expanded);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        return expanded;
    }

    static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String ns = raw.trim();
        if (ns.length() > 63 || !NAMESPACE_PATTERN.matcher(ns).matches()) {
            return "Namespace must be DNS-1123 (lowercase alphanumeric and '-').";
        }
        return null;
    }
}
