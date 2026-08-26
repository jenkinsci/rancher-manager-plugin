package io.jenkins.plugins.ranchermanager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectionTesterTest {

    @BeforeEach
    public void clearLoopbackAllow() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
    }

    @AfterEach
    public void restoreLoopbackAllow() {
        System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
    }

    @Test
    public void extractHost_basicAndIpv6() {
        assertEquals("rancher.example", ConnectionTester.extractHost("https://rancher.example:443"));
        assertEquals("2001:db8::1", ConnectionTester.extractHost("https://[2001:db8::1]:443/"));
    }

    @Test
    public void extractHost_supportsUnderscoreHostname() {
        assertEquals("ran_cher.example",
                ConnectionTester.extractHost("https://ran_cher.example:443"));
    }

    @Test
    public void extractHost_rejectsNullAndBlank() {
        IllegalArgumentException nullEx = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost(null));
        assertTrue(nullEx.getMessage().toLowerCase().contains("missing"));

        IllegalArgumentException blankEx = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("   "));
        assertTrue(blankEx.getMessage().toLowerCase().contains("missing"));
    }

    @Test
    public void extractHost_manualAuthority_stripsUserInfo() {
        assertEquals("my_host.example",
                ConnectionTester.extractHost("https://user:pass@my_host.example/v3"));
    }

    @Test
    public void extractHost_rejectsMissingScheme() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("rancher.example"));
        assertTrue(ex.getMessage().toLowerCase().contains("invalid"));
    }

    @Test
    public void extractHost_rejectsIpv6AuthorityMissingClosingBracket() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("https://[::1"));
        assertTrue(ex.getMessage().toLowerCase().contains("invalid"));
    }

    @Test
    public void extractHost_rejectsBlankHostAfterParse() {
        IllegalArgumentException emptyAuthority = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("https://"));
        assertTrue(emptyAuthority.getMessage().toLowerCase().contains("missing"));

        IllegalArgumentException slashOnly = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.extractHost("http:///"));
        assertTrue(slashOnly.getMessage().toLowerCase().contains("missing"));
    }

    @Test
    public void extractHost_rejectsWhitespaceAndBackslashInHost() {
        IllegalArgumentException space = assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.extractHost("https://bad host.example"));
        assertTrue(space.getMessage().toLowerCase().contains("invalid"));

        IllegalArgumentException backslash = assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.extractHost("https://host\\evil.example"));
        assertTrue(backslash.getMessage().toLowerCase().contains("invalid"));
    }

    @Test
    public void assertUriHostAllowed_rejectsLoopback() {
        IllegalArgumentException ex = assertUriRejected("http://127.0.0.1:1/v3");
        assertTrue(ex.getMessage().toLowerCase().contains("not allowed"));
    }

    @Test
    public void assertUriHostAllowed_rejectsNull() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ConnectionTester.assertUriHostAllowed(null));
        assertTrue(ex.getMessage().toLowerCase().contains("missing"));
    }

    @Test
    public void assertUriHostAllowed_rejectsBadScheme() {
        IllegalArgumentException ex = assertUriRejected("ftp://rancher.example/");
        assertTrue(ex.getMessage().toLowerCase().contains("scheme"));
    }

    @Test
    public void assertUriHostAllowed_hostWithPort_stillBlocksLoopback() {
        IllegalArgumentException ex = assertUriRejected("https://127.0.0.1:443/");
        assertTrue(ex.getMessage().toLowerCase().contains("not allowed"));
    }

    @Test
    public void assertUriHostAllowed_ipv6Host_stillBlocksLoopback() {
        IllegalArgumentException ex = assertUriRejected("http://[::1]:8443/");
        assertTrue(ex.getMessage().toLowerCase().contains("not allowed"));
    }

    @Test
    public void assertUriHostAllowed_blankHost_fallsBackToAssertHostAllowed() {
        IllegalArgumentException ex = assertUriRejected("http:///");
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("missing")
                || msg.contains("invalid")
                || msg.contains("resolv")
                || msg.contains("not allowed"));
    }

    @Test
    public void deferUnknownHost_doesNotThrowForUnresolvable() {
        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed(
                "https://no-such-host-rancher-test.invalid/"));
    }

    @Test
    public void requireResolved_rejectsUnresolvable() {
        assertBlocked(
                "https://no-such-host-rancher-test.invalid/",
                ConnectionTester.DnsPolicy.REQUIRE_RESOLVED,
                "resolv");
    }

    @Test
    public void requireResolved_rejectsMetadata() {
        assertBlocked(
                "http://169.254.169.254/",
                ConnectionTester.DnsPolicy.REQUIRE_RESOLVED,
                "not allowed");
    }

    @Test
    public void assertHostAllowed_defaultDefer_blocksLocalhostAndLoopbackLiterals() {
        assertBlocked("http://localhost/");
        assertBlocked("http://foo.localhost/");
        assertBlocked("http://127.0.0.1/");
        assertBlocked("http://[::1]/");
        assertBlocked("http://0.0.0.0/");
    }

    @Test
    public void assertHostAllowed_defaultDefer_blocksMetadataHostnames() {
        assertBlocked("http://metadata/");
        assertBlocked("http://metadata.google.internal/");
        assertBlocked("http://metadata.google/");
    }

    @Test
    public void allowLoopbackForTests_allows127ButStillBlocksMetadata() {
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");

        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed("http://127.0.0.1/"));
        assertBlocked("http://metadata.google.internal/");
    }

    @Test
    public void isIpv4LoopbackLiteral_ignoresInvalidPartCountsAndValues() {
        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed(
                "http://127.0.0.256/", ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST));
        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed(
                "http://127.a.0.1/", ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST));
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        assertDoesNotThrow(() -> ConnectionTester.assertHostAllowed(
                "http://127.1/", ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST));
    }

    private static IllegalArgumentException assertUriRejected(String spec) {
        URI uri = URI.create(spec);
        return assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.assertUriHostAllowed(uri));
    }

    private static void assertBlocked(String url) {
        assertBlocked(url, ConnectionTester.DnsPolicy.DEFER_UNKNOWN_HOST, "not allowed");
    }

    private static void assertBlocked(String url, ConnectionTester.DnsPolicy policy, String needle) {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ConnectionTester.assertHostAllowed(url, policy));
        assertTrue(ex.getMessage().toLowerCase().contains(needle), ex.getMessage());
    }
}
