package io.jenkins.plugins.ranchermanager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ChartRepositoryUrlsTest {

    @Test
    public void require_acceptsHttpHttpsAndOci() {
        ChartRepositoryUrls.require("https://charts.example/helm");
        ChartRepositoryUrls.require("http://charts.example/helm/");
        ChartRepositoryUrls.require("oci://localhost:5000/charts");
        ChartRepositoryUrls.require("OCI://ghcr.example/org/charts");
    }

    @Test
    public void require_rejectsGitOnlySchemesAndUserinfo() {
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
    public void normalize_stripsTrailingSlashes() {
        assertEquals("oci://localhost:5000/charts", ChartRepositoryUrls.normalize("oci://localhost:5000/charts/"));
        assertEquals(
                "https://charts.example/helm", ChartRepositoryUrls.normalize("https://charts.example/helm///"));
    }

    @Test
    public void hostPath_includesNonDefaultPort() {
        assertEquals("localhost:5000/charts", ChartRepositoryUrls.hostPath("oci://localhost:5000/charts/"));
        assertEquals("charts.example/helm", ChartRepositoryUrls.hostPath("https://charts.example/helm"));
        assertEquals(
                "charts.example/helm",
                ChartRepositoryUrls.hostPath("https://charts.example/helm/"));
    }
}
