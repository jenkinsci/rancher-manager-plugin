package io.jenkins.plugins.ranchermanager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RancherUrlTest {

    @Test
    public void syntaxOnly_stripsPathAndSlash() {
        assertEquals(
                "https://rancher.example",
                RancherUrl.normalizeBaseUrlSyntaxOnly("https://rancher.example/dashboard/"));
        assertEquals(
                "https://rancher.example:443",
                RancherUrl.normalizeBaseUrlSyntaxOnly("https://rancher.example:443"));
    }

    @Test
    public void syntaxOnly_rejectsBadSchemeAndUserinfo() {
        IllegalArgumentException scheme = assertThrows(
                IllegalArgumentException.class,
                () -> RancherUrl.normalizeBaseUrlSyntaxOnly("ftp://rancher.example"));
        assertTrue(scheme.getMessage().toLowerCase().contains("http"));

        IllegalArgumentException userinfo = assertThrows(
                IllegalArgumentException.class,
                () -> RancherUrl.normalizeBaseUrlSyntaxOnly("https://u:p@rancher.example"));
        assertTrue(userinfo.getMessage().toLowerCase().contains("userinfo"));
    }

    @Test
    public void syntaxOnly_rejectsBlankAndMissingHost() {
        IllegalArgumentException blank = assertThrows(
                IllegalArgumentException.class, () -> RancherUrl.normalizeBaseUrlSyntaxOnly(" "));
        assertTrue(blank.getMessage().toLowerCase().contains("required"));

        IllegalArgumentException missingSlash = assertThrows(
                IllegalArgumentException.class,
                () -> RancherUrl.normalizeBaseUrlSyntaxOnly("https:///"));
        assertTrue(missingSlash.getMessage().toLowerCase().contains("host"));

        IllegalArgumentException missingBare = assertThrows(
                IllegalArgumentException.class,
                () -> RancherUrl.normalizeBaseUrlSyntaxOnly("https://"));
        assertTrue(missingBare.getMessage().toLowerCase().contains("host"));
    }

    @Test
    void stripTrailingSlashes_andNormalizeWithLoopback() {
        assertEquals(null, RancherUrl.stripTrailingSlashes(null));
        assertEquals("", RancherUrl.stripTrailingSlashes(""));
        assertEquals("https://rancher.example", RancherUrl.stripTrailingSlashes("https://rancher.example///"));
        assertEquals("https://", RancherUrl.stripTrailingSlashes("https://"));
        System.setProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP, "true");
        try {
            assertEquals("http://127.0.0.1:8080", RancherUrl.normalizeBaseUrl("http://127.0.0.1:8080/dashboard/"));
        } finally {
            System.clearProperty(ConnectionTester.ALLOW_LOOPBACK_FOR_TESTS_PROP);
        }
    }
}
