package io.jenkins.plugins.ranchermanager;

import java.net.URI;
import java.util.Locale;

/**
 * Chart repository URL for {@code rancherHelm} {@code repo} — maps to Rancher ClusterRepo
 * {@code spec.url}. HTTP Helm index ({@code http}/{@code https}) or OCI registry ({@code oci}).
 * Not Git clone URLs (those stay {@link RancherManifestBuilder#requireHttpRepoUrl}).
 */
final class ChartRepositoryUrls {

    private ChartRepositoryUrls() {}

    /**
     * Normalize for ClusterRepo matching: trim, strip trailing slashes.
     */
    static String normalize(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /**
     * Validate chart repository URL. Schemes: {@code http}, {@code https}, {@code oci}.
     * No userinfo. Host required.
     */
    static void require(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Chart repository URL is required.");
        }
        String u = url.trim();
        if (u.indexOf('@') >= 0) {
            throw new IllegalArgumentException("Chart repository URL must not contain userinfo.");
        }
        boolean http = startsWithIgnoreCase(u, "http://");
        boolean https = startsWithIgnoreCase(u, "https://");
        boolean oci = startsWithIgnoreCase(u, "oci://");
        if (!http && !https && !oci) {
            throw new IllegalArgumentException(
                    "Chart repository URL must start with http://, https://, or oci://.");
        }
        try {
            URI uri = URI.create(normalize(u));
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Chart repository URL must include a host.");
            }
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Chart repository")) {
                throw e;
            }
            throw new IllegalArgumentException("Chart repository URL is not a valid URI.", e);
        }
    }

    /**
     * Host (+ non-default port) + path for fuzzy ClusterRepo match when schemes differ slightly.
     */
    static String hostPath(String url) {
        try {
            URI uri = URI.create(normalize(url));
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.isBlank()) {
                return "";
            }
            int port = uri.getPort();
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (port > 0) {
                return host + ":" + port + path;
            }
            return host + path;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
