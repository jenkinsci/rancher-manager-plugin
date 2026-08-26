package io.jenkins.plugins.ranchermanager;

/**
 * Resolved Rancher API base URL + credentials id for a build step
 * ({@link ConnectionMode#INHERIT} or {@link ConnectionMode#MANUAL}).
 */
final class ResolvedConnection {

    final String displayName;
    final String mode;
    final String baseUrl;
    final String credentialsId;
    final int connectTimeoutMs;
    final int readTimeoutMs;

    ResolvedConnection(
            String displayName,
            String mode,
            String baseUrl,
            String credentialsId,
            int connectTimeoutMs,
            int readTimeoutMs) {
        this.displayName = displayName;
        this.mode = mode == null ? ConnectionMode.INHERIT : mode;
        this.baseUrl = baseUrl;
        this.credentialsId = credentialsId;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }
}
