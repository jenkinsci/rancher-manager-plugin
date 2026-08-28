package io.jenkins.plugins.ranchermanager;

import hudson.AbortException;

/**
 * Step already wrote {@code [ERROR]} and closed its log banner. Distinct from a raw
 * {@link AbortException} so {@code abort()} does not wrap the same failure twice.
 */
final class RancherLoggedAbort extends AbortException {

    RancherLoggedAbort(String message) {
        super(message == null || message.isBlank() ? "failed" : message);
    }
}
