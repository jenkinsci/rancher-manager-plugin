package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RancherClientErrorsTest {

    private static final URI RANCHER = URI.create("https://rancher.example/v3");

    @Test
    void httpError_statusAndHtml() {
        assertTrue(RancherClient.httpError(401, new byte[0]).getMessage().contains("HTTP 401"));
        assertTrue(RancherClient.httpError(403, "{\"message\":\"no\"}".getBytes(StandardCharsets.UTF_8))
                .getMessage()
                .contains("HTTP 403"));
        assertTrue(RancherClient.httpError(404, new byte[0]).getMessage().contains("HTTP 404"));
        assertTrue(RancherClient.httpError(500, new byte[0]).getMessage().startsWith("HTTP 500"));
        assertTrue(RancherClient.httpError(502, "<!DOCTYPE html><html>".getBytes(StandardCharsets.UTF_8))
                .getMessage()
                .contains("HTML"));
        assertFalse(RancherClient.looksLikeHtml(null));
        assertFalse(RancherClient.looksLikeHtml(new byte[0]));
        assertTrue(RancherClient.looksLikeHtml("<html>x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void extractErrorDetail_jsonAndRaw() {
        assertEquals("", RancherClient.extractErrorDetail(null));
        assertEquals("", RancherClient.extractErrorDetail(new byte[0]));
        assertEquals(
                "boom",
                RancherClient.extractErrorDetail("{\"message\":\"boom\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                "plain",
                RancherClient.extractErrorDetail("plain".getBytes(StandardCharsets.UTF_8)));
        assertEquals("", RancherClient.sanitizeErrorDetail(null));
        assertEquals("", RancherClient.sanitizeErrorDetail("  "));
        assertTrue(RancherClient.sanitizeErrorDetail("token=abc password=x").contains("[redacted]"));
        assertEquals("", RancherClient.truncateDetail(null));
        String longDetail = "x".repeat(RancherClient.MAX_ERROR_DETAIL_CHARS + 5);
        assertTrue(RancherClient.truncateDetail(longDetail).endsWith("…"));
    }

    @Test
    void textAndFirstNonBlank() throws Exception {
        assertEquals("", RancherClient.text(null, "n"));
        assertEquals("", RancherClient.text(new ObjectMapper().readTree("{}"), null));
        assertEquals("", RancherClient.text(new ObjectMapper().readTree("{\"n\":null}"), "n"));
        assertEquals("a", RancherClient.text(new ObjectMapper().readTree("{\"n\":\" a \"}"), "n"));
        assertEquals("", RancherClient.firstNonBlank((String[]) null));
        assertEquals("", RancherClient.firstNonBlank(null, "  ", ""));
        assertEquals("x", RancherClient.firstNonBlank("  ", "x", "y"));
    }

    @Test
    void mapTransportError_timeoutUnknownHostConnect() {
        IOException timeout = RancherClient.mapTransportError(RANCHER, new HttpTimeoutException("t"));
        assertTrue(timeout.getMessage().contains("timed out"));
        assertTrue(timeout.getMessage().contains("rancher.example"));

        IOException unknown = RancherClient.mapTransportError(
                RANCHER, new IOException(new UnknownHostException("nope")));
        assertTrue(unknown.getMessage().contains("could not be resolved"));

        IOException refused = RancherClient.mapTransportError(RANCHER, new ConnectException("Connection refused"));
        assertTrue(refused.getMessage().contains("Cannot connect"));

        IOException nested = RancherClient.mapTransportError(
                RANCHER, new IOException("wrapper", new ConnectException("connect timed out")));
        assertTrue(nested.getMessage().contains("Cannot connect"));

        IOException connectivityText = RancherClient.mapTransportError(
                RANCHER, new IOException("Network is unreachable"));
        assertTrue(connectivityText.getMessage().contains("Cannot connect"));

        IOException other = new IOException("other");
        assertSame(other, RancherClient.mapTransportError(RANCHER, other));

        IOException noHost = RancherClient.mapTransportError(null, new HttpTimeoutException("t"));
        assertEquals("Rancher request timed out.", noHost.getMessage());
        IOException noHostUnknown = RancherClient.mapTransportError(null, new IOException(new UnknownHostException("x")));
        assertEquals("Rancher host could not be resolved.", noHostUnknown.getMessage());
        IOException noHostConnect = RancherClient.mapTransportError(null, new ConnectException("Connection refused"));
        assertTrue(noHostConnect.getMessage().startsWith("Cannot connect to Rancher host/port"));
    }

    @Test
    void isConnectivityAndHttpStatus() {
        assertFalse(RancherClient.isConnectivityMessage(null));
        assertFalse(RancherClient.isConnectivityMessage("  "));
        assertTrue(RancherClient.isConnectivityMessage("No route to host"));
        assertFalse(RancherClient.isHttpStatus(null, 404));
        assertFalse(RancherClient.isHttpStatus(new IOException(), 404));
        assertTrue(RancherClient.isHttpStatus(new IOException("HTTP 404 - missing"), 404));
        assertEquals(-1, RancherClient.httpStatusCode(null));
        assertEquals(-1, RancherClient.httpStatusCode(new IOException()));
        assertEquals(-1, RancherClient.httpStatusCode(new IOException("HTTP ")));
    }

    @Test
    void namespaceProjectAnnotation_andProjectIds() throws Exception {
        assertEquals("", RancherClient.namespaceProjectAnnotation(null));
        assertEquals(
                "local:p-abc12",
                RancherClient.namespaceProjectAnnotation(
                        new ObjectMapper().readTree(
                                "{\"metadata\":{\"annotations\":{\""
                                        + RancherClient.PROJECT_ID_FIELD
                                        + "\":\"local:p-abc12\"}}}")));
        assertEquals("p-abc12", RancherClient.shortProjectId("local:p-abc12"));
        assertEquals("", RancherClient.shortProjectId(null));
        assertEquals("p-abc12", RancherClient.shortProjectId(" p-abc12 "));
    }

    @Test
    void mapMissingChartVersion_blankVersion() {
        RancherClient.HelmChartRequest request = new RancherClient.HelmChartRequest(
                "charts", "rel", "nginx", "default", "local:p", null, null, false, null, false, false);
        IOException miss = new IOException("HTTP 500 - no chart version found for nginx");
        String msg = RancherClient.mapMissingChartVersion(miss, "charts", request).getMessage();
        assertTrue(msg.contains("has no chart \"nginx\""));
        assertFalse(msg.contains("version"));
        assertFalse(RancherClient.isMissingChartVersion(null));
    }
}
