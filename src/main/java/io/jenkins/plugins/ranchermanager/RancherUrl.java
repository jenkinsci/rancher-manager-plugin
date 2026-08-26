package io.jenkins.plugins.ranchermanager;

/**
 * Parses and validates a Rancher Manager base URL (scheme, no userinfo, host allowlist).
 */
final class RancherUrl {

    private RancherUrl() {
    }

    /**
     * Syntax-only normalize (scheme/host/userinfo). Does <strong>not</strong> resolve DNS
     * or run the SSRF host allowlist — use for form checks so System stays responsive.
     *
     * @return normalized base URL without trailing slash (e.g. {@code https://rancher.example})
     */
    static String normalizeBaseUrlSyntaxOnly(String rancherUrl) {
        if (rancherUrl == null || rancherUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Rancher URL is required (http:// or https://rancher.example).");
        }
        String clean = stripTrailingSlashes(rancherUrl.trim());
        boolean https = clean.regionMatches(true, 0, "https://", 0, 8);
        boolean http = clean.regionMatches(true, 0, "http://", 0, 7);
        if (!https && !http) {
            throw new IllegalArgumentException(
                    "Rancher URL must start with http:// or https://.");
        }
        int schemeEnd = clean.indexOf("://");
        int authStart = schemeEnd + 3;
        if (authStart >= clean.length()) {
            throw new IllegalArgumentException("Rancher URL host is missing.");
        }
        int at = clean.indexOf('@', authStart);
        int pathStart = clean.indexOf('/', authStart);
        if (at >= 0 && (pathStart < 0 || at < pathStart)) {
            throw new IllegalArgumentException(
                    "Rancher URL must not contain userinfo (user:pass@host); use Credentials for the API token.");
        }
        if (pathStart == authStart) {
            throw new IllegalArgumentException("Rancher URL host is missing.");
        }
        return pathStart > 0 ? clean.substring(0, pathStart) : clean;
    }

    /**
     * Full normalize: syntax plus SSRF host allowlist / DNS (for runtime probe / preflight).
     *
     * @return normalized base URL without trailing slash (e.g. {@code https://rancher.example})
     */
    static String normalizeBaseUrl(String rancherUrl) {
        String base = normalizeBaseUrlSyntaxOnly(rancherUrl);
        ConnectionTester.assertHostAllowed(base);
        return base;
    }

    static String stripTrailingSlashes(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        int scheme = url.indexOf("://");
        int min = scheme >= 0 ? scheme + 3 : 0;
        int end = url.length();
        while (end > min && url.charAt(end - 1) == '/') {
            end--;
        }
        return end == url.length() ? url : url.substring(0, end);
    }
}
