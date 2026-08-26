package io.jenkins.plugins.ranchermanager;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SSRF host guards for Rancher HTTP calls ({@code followRedirects=NEVER}).
 * Site-local (RFC1918) is allowed for on-prem Rancher; loopback / link-local / metadata blocked.
 */
final class ConnectionTester {

    private static final Logger LOGGER = Logger.getLogger(ConnectionTester.class.getName());

    /**
     * System property for unit tests that bind JDK {@link com.sun.net.httpserver.HttpServer} on loopback.
     * Never enable in production.
     */
    static final String ALLOW_LOOPBACK_FOR_TESTS_PROP = "rancher.api.allowLoopbackForTests";

    enum DnsPolicy {
        /** Format check: unknown host is deferred to connect / runtime preflight. */
        DEFER_UNKNOWN_HOST,
        /** Connect-time: host must resolve; every address must pass the blocklist. */
        REQUIRE_RESOLVED
    }

    private ConnectionTester() {
    }

    static String extractHost(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("URL host is missing.");
        }
        String uriHost = tryUriHost(baseUrl);
        if (uriHost != null) {
            return uriHost;
        }
        return hostFromAuthority(stripUserInfoAndPath(authorityAfterScheme(baseUrl.trim())));
    }

    private static String tryUriHost(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            if (host != null && !host.isBlank()) {
                return stripIpv6Brackets(host);
            }
        } catch (IllegalArgumentException ignored) {
            // URI.create wraps URISyntaxException; fall through to manual authority parse
        }
        return null;
    }

    private static String authorityAfterScheme(String s) {
        int scheme = s.indexOf("://");
        if (scheme < 0) {
            throw new IllegalArgumentException("URL host is invalid.");
        }
        return s.substring(scheme + 3);
    }

    private static String stripUserInfoAndPath(String authority) {
        int slash = authority.indexOf('/');
        if (slash >= 0) {
            authority = authority.substring(0, slash);
        }
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        return authority;
    }

    private static String hostFromAuthority(String authority) {
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0) {
                throw new IllegalArgumentException("URL host is invalid.");
            }
            return requireHost(authority.substring(1, end), false);
        }
        int colon = authority.lastIndexOf(':');
        String host = colon > 0 ? authority.substring(0, colon) : authority;
        return requireHost(host, true);
    }

    private static String requireHost(String host, boolean rejectWhitespace) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL host is missing.");
        }
        if (rejectWhitespace && (host.indexOf(' ') >= 0 || host.indexOf('\\') >= 0)) {
            throw new IllegalArgumentException("URL host is invalid.");
        }
        return stripIpv6Brackets(host);
    }

    private static String stripIpv6Brackets(String host) {
        if (host != null && host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    static void assertHostAllowed(String baseUrl) {
        assertHostAllowed(baseUrl, DnsPolicy.DEFER_UNKNOWN_HOST);
    }

    static void assertUriHostAllowed(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("HTTP URI is missing.");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("HTTP URI scheme is invalid.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            assertHostAllowed(uri.toString(), DnsPolicy.REQUIRE_RESOLVED);
            return;
        }
        host = stripIpv6Brackets(host);
        int port = uri.getPort();
        boolean ipv6 = host.indexOf(':') >= 0;
        String hostPart = ipv6 ? "[" + host + "]" : host;
        String authority = port > 0 ? hostPart + ":" + port : hostPart;
        assertHostAllowed(scheme + "://" + authority + "/", DnsPolicy.REQUIRE_RESOLVED);
    }

    static void assertHostAllowed(String baseUrl, DnsPolicy policy) {
        String host = extractHost(baseUrl);
        String h = host.toLowerCase(Locale.ROOT);
        boolean testsAllowLoopback = Boolean.getBoolean(ALLOW_LOOPBACK_FOR_TESTS_PROP);
        if (!testsAllowLoopback && isBlockedHostname(h)) {
            throw blocked(host);
        }
        if (testsAllowLoopback && isMetadataHostname(h)) {
            throw blocked(host);
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                if (policy == DnsPolicy.REQUIRE_RESOLVED) {
                    throw new IllegalArgumentException(
                            "URL host '" + host + "' could not be resolved.");
                }
                return;
            }
            for (InetAddress addr : addresses) {
                if (isBlockedAddress(addr, testsAllowLoopback)) {
                    throw blocked(host);
                }
            }
        } catch (UnknownHostException e) {
            if (policy == DnsPolicy.REQUIRE_RESOLVED) {
                throw new IllegalArgumentException(
                        "URL host '" + host + "' could not be resolved.", e);
            }
            LOGGER.log(Level.FINE, "URL host DNS lookup failed for {0}: {1}",
                    new Object[]{host, e.toString()});
        }
    }

    private static boolean isBlockedHostname(String h) {
        return h.equals("localhost")
                || h.endsWith(".localhost")
                || isMetadataHostname(h)
                || h.equals("0.0.0.0")
                || h.equals("::")
                || h.equals("::1")
                || isIpv4LoopbackLiteral(h);
    }

    private static boolean isIpv4LoopbackLiteral(String h) {
        if (!h.startsWith("127.")) {
            return false;
        }
        String[] parts = h.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            try {
                int n = Integer.parseInt(p);
                if (n < 0 || n > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMetadataHostname(String h) {
        return h.equals("metadata")
                || h.equals("metadata.google.internal")
                || h.equals("metadata.google");
    }

    private static boolean isBlockedAddress(InetAddress addr, boolean testsAllowLoopback) {
        if (testsAllowLoopback && addr.isLoopbackAddress()) {
            return false;
        }
        if (addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        return raw.length == 4 && (raw[0] & 0xff) == 0;
    }

    private static IllegalArgumentException blocked(String host) {
        return new IllegalArgumentException(
                "URL host '" + host + "' is not allowed (loopback/link-local/metadata).");
    }
}
