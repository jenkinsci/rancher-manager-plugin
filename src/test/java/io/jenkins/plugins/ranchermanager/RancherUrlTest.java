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
}
