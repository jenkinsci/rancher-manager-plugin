package io.jenkins.plugins.ranchermanager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChartRepositoryUrlsTest {

    @Test
    void require_acceptsHttpHttpsAndOci() {
        assertDoesNotThrow(() -> ChartRepositoryUrls.require("https://charts.example/helm"));
        assertDoesNotThrow(() -> ChartRepositoryUrls.require("http://charts.example/helm/"));
        assertDoesNotThrow(() -> ChartRepositoryUrls.require("oci://localhost:5000/charts"));
        assertDoesNotThrow(() -> ChartRepositoryUrls.require("OCI://ghcr.example/org/charts"));
    }

    @Test
    void require_rejectsGitOnlySchemesAndUserinfo() {
        assertThrows(IllegalArgumentException.class, () -> ChartRepositoryUrls.require("git@host:repo.git"));
        assertThrows(IllegalArgumentException.class, () -> ChartRepositoryUrls.require("ssh://git@host/repo"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChartRepositoryUrls.require("https://user:pass@charts.example/helm"));
        assertThrows(IllegalArgumentException.class, () -> ChartRepositoryUrls.require("oci://"));
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class, () -> ChartRepositoryUrls.require(""));
        assertEquals("Chart repository URL is required.", blank.getMessage());
    }

    @Test
    void normalize_stripsTrailingSlashes() {
        assertEquals("oci://localhost:5000/charts", ChartRepositoryUrls.normalize("oci://localhost:5000/charts/"));
        assertEquals(
                "https://charts.example/helm", ChartRepositoryUrls.normalize("https://charts.example/helm///"));
    }

    @Test
    void hostPath_includesNonDefaultPort() {
        assertEquals("localhost:5000/charts", ChartRepositoryUrls.hostPath("oci://localhost:5000/charts/"));
        assertEquals("charts.example/helm", ChartRepositoryUrls.hostPath("https://charts.example/helm"));
        assertEquals(
                "charts.example/helm",
                ChartRepositoryUrls.hostPath("https://charts.example/helm/"));
        assertEquals("", ChartRepositoryUrls.hostPath("oci://"));
        assertEquals("", ChartRepositoryUrls.hostPath("http://["));
    }

    @Test
    void require_rejectsMissingHost() {
        assertThrows(IllegalArgumentException.class, () -> ChartRepositoryUrls.require("https://"));
        assertThrows(IllegalArgumentException.class, () -> ChartRepositoryUrls.require(null));
    }
}
