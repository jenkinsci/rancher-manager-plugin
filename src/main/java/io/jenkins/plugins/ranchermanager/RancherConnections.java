package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.model.Item;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Shared Rancher connection resolve, cluster parse, preflight, and abort helpers.
 */
final class RancherConnections {

    private RancherConnections() {
    }

    static ResolvedConnection resolve(
            RancherGlobalConfiguration cfg,
            String mode,
            String manualUrl,
            String manualCredentialsId) throws AbortException {
        String normalizedMode = ConnectionMode.normalize(mode, ConnectionMode.INHERIT);
        if (ConnectionMode.isManual(normalizedMode)) {
            return resolveManual(cfg, manualUrl, manualCredentialsId);
        }
        return resolveInherit(cfg);
    }

    private static ResolvedConnection resolveManual(
            RancherGlobalConfiguration cfg, String manualUrl, String manualCredentialsId)
            throws AbortException {
        if (manualUrl == null || manualUrl.isBlank()
                || manualCredentialsId == null || manualCredentialsId.isBlank()) {
            throw new AbortException(
                    "Rancher Manual requires Rancher URL and API token credentials on this step "
                            + "(or set Rancher connection to Inherit and configure Manage Jenkins → System).");
        }
        final String baseUrl;
        try {
            baseUrl = RancherUrl.normalizeBaseUrl(manualUrl);
        } catch (IllegalArgumentException e) {
            throw new AbortException(e.getMessage());
        }
        int connectMs = RancherGlobalConfiguration.DEFAULT_CONNECT_TIMEOUT_MS;
        int readMs = RancherGlobalConfiguration.DEFAULT_READ_TIMEOUT_MS;
        if (cfg != null) {
            connectMs = cfg.getConnectTimeoutMs();
            readMs = cfg.getReadTimeoutMs();
        }
        return new ResolvedConnection(
                "manual",
                ConnectionMode.MANUAL,
                baseUrl,
                manualCredentialsId.trim(),
                connectMs,
                readMs);
    }

    private static ResolvedConnection resolveInherit(RancherGlobalConfiguration cfg) throws AbortException {
        if (cfg == null || !cfg.isConfigured()) {
            throw new AbortException(
                    "Rancher Manager is not configured. Set Rancher URL and API token credentials under "
                            + "Manage Jenkins → System → Rancher Manager "
                            + "(or set Rancher connection to Manual on this step).");
        }
        String displayName = cfg.getName() == null ? "" : cfg.getName().trim();
        if (displayName.isEmpty()) {
            displayName = "default";
        }
        final String baseUrl;
        try {
            baseUrl = RancherUrl.normalizeBaseUrl(cfg.getRancherUrl());
        } catch (IllegalArgumentException e) {
            throw new AbortException(e.getMessage());
        }
        return new ResolvedConnection(
                displayName,
                ConnectionMode.INHERIT,
                baseUrl,
                cfg.getCredentialsId().trim(),
                cfg.getConnectTimeoutMs(),
                cfg.getReadTimeoutMs());
    }

    static String parseClusterId(String raw) throws AbortException {
        if (raw == null || raw.isBlank()) {
            throw new AbortException("Cluster ID is required (Rancher cluster id, e.g. local).");
        }
        String id = raw.trim();
        if (id.indexOf('/') >= 0 || id.indexOf('?') >= 0 || id.indexOf('#') >= 0 || id.contains("..")) {
            throw new AbortException("Cluster ID must not contain '/', '?', '#' or '..'.");
        }
        return id;
    }

    static String resolveClusterId(String raw, EnvVars buildEnv) throws AbortException {
        String source = raw == null ? "" : raw.trim();
        String expanded = buildEnv == null ? source : buildEnv.expand(source);
        return parseClusterId(expanded);
    }

    static void runPreflight(
            RancherClient client,
            ResolvedConnection connection,
            String apiToken,
            String clusterId,
            RancherBuildLogger log) throws AbortException {
        try {
            // Cluster GET is the auth check. Cluster-scoped API keys cannot call
            // /v3/users?me=true (Rancher returns 401 must authenticate).
            JsonNode cluster = client.getCluster(connection.baseUrl, apiToken, clusterId);
            String clusterName = RancherClient.firstNonBlank(
                    RancherClient.text(cluster, "name"),
                    RancherClient.text(cluster, "Name"));
            log.info(formatPreflightCluster(clusterId, clusterName));
        } catch (AbortException e) {
            throw e;
        } catch (IOException e) {
            throw abort(log, "Preflight failed: " + truncateMessage(e), e);
        }
    }

    static String formatPreflightCluster(String clusterId, String clusterName) {
        String name = clusterName == null ? "" : clusterName.trim();
        if (name.isEmpty()) {
            return "Preflight check of cluster " + clusterId;
        }
        return "Preflight check of cluster " + clusterId + " (" + name + ")";
    }

    static AbortException abort(RancherBuildLogger log, String message) {
        return abort(log, message, null);
    }

    static AbortException abort(RancherBuildLogger log, String message, Throwable thrown) {
        if (thrown instanceof RancherLoggedAbort already) {
            return already;
        }
        String body = RancherBuildLogger.stripPrefix(message);
        if (body.isEmpty()) {
            body = "failed";
        }
        if (log != null && !log.hasLoggedError()) {
            if (thrown != null) {
                log.errorJul(body, thrown);
                log.error(body);
            } else {
                log.error(body);
            }
        }
        return new RancherLoggedAbort(body);
    }

    static String truncateMessage(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        int max = RancherClient.MAX_ERROR_DETAIL_CHARS;
        if (msg.length() <= max) {
            return msg;
        }
        // Keep kstatus near the start and helm Error near the end; drop the middle.
        int head = Math.min(512, max / 4);
        int tail = max - head - 1;
        return msg.substring(0, head) + "…" + msg.substring(msg.length() - tail);
    }

    /** SHA-256 hex truncated for logs — never the YAML body. */
    static String shortContentHash(String content) {
        if (content == null || content.isEmpty()) {
            return "-";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < dig.length; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", dig[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    static RancherCredentials.GitAuth resolveOptionalGitAuth(String credentialsId, Item item) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        return RancherCredentials.resolveGitAuth(credentialsId, item);
    }

    static RancherCredentials.GitAuth resolveOptionalGitAuth(
            String credentialsId, Item item, RancherBuildLogger log) throws AbortException {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        return abortOn(log, () -> RancherCredentials.resolveGitAuth(credentialsId, item));
    }

    static String connectionSummary() {
        RancherGlobalConfiguration cfg = RancherGlobalConfiguration.get();
        if (cfg == null || !cfg.isConfigured()) {
            return "System Rancher Manager is not configured.";
        }
        return "System Rancher Manager is configured.";
    }

    static FormValidation checkRancherSource(String rancherConnectionMode) {
        if (ConnectionMode.isManual(rancherConnectionMode)) {
            return FormValidation.ok();
        }
        RancherGlobalConfiguration cfg = RancherGlobalConfiguration.get();
        if (cfg == null || !cfg.isConfigured()) {
            return FormValidation.error("System Rancher Manager is not configured.");
        }
        return FormValidation.ok();
    }

    static void checkConfigure(Item item) {
        if (item == null) {
            Jenkins.get().checkPermission(Jenkins.READ);
        } else {
            item.checkPermission(Item.CONFIGURE);
        }
    }

    static FormValidation checkClusterId(String value, String rancherConnectionMode) {
        FormValidation connection = checkRancherSource(rancherConnectionMode);
        if (connection.kind == FormValidation.Kind.ERROR) {
            return connection;
        }
        if (value == null || value.isBlank()) {
            return FormValidation.error("Cluster ID is required.");
        }
        String trimmed = value.trim();
        if (trimmed.indexOf('$') >= 0) {
            return FormValidation.ok();
        }
        try {
            parseClusterId(trimmed);
            return FormValidation.ok();
        } catch (AbortException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    static FormValidation checkRancherUrl(String value, String rancherConnectionMode) {
        if (!ConnectionMode.isManual(rancherConnectionMode)) {
            return FormValidation.ok();
        }
        if (value == null || value.isBlank()) {
            return FormValidation.error("Rancher URL is required for Manual connection.");
        }
        try {
            RancherUrl.normalizeBaseUrlSyntaxOnly(value);
            return FormValidation.ok();
        } catch (IllegalArgumentException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    static <T> T abortOn(RancherBuildLogger log, AbortingSupplier<T> supplier)
            throws AbortException {
        try {
            return supplier.get();
        } catch (AbortException e) {
            throw abort(log, e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw abort(log, e.getMessage());
        }
    }

    static Authenticated resolveAuthenticated(
            RancherGlobalConfiguration cfg,
            String mode,
            String manualUrl,
            String manualCredentialsId,
            Item item,
            RancherBuildLogger log) throws AbortException {
        ResolvedConnection connection = abortOn(
                log, () -> resolve(cfg, mode, manualUrl, manualCredentialsId));
        String apiToken = abortOn(
                log, () -> RancherCredentials.resolveApiToken(connection.credentialsId, item));
        return new Authenticated(connection, apiToken);
    }

    static final class Authenticated {
        final ResolvedConnection connection;
        @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
        final String apiToken;

        Authenticated(ResolvedConnection connection, String apiToken) {
            this.connection = connection;
            this.apiToken = apiToken;
        }
    }

    @FunctionalInterface
    interface AbortingSupplier<T> {
        T get() throws AbortException;
    }
}
