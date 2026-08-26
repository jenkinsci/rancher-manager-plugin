package io.jenkins.plugins.ranchermanager;

import hudson.AbortException;

/**
 * Step already wrote {@code [ERROR]} and closed its log banner. Freestyle catches this and returns
 * false so Jenkins does not print a second {@code ERROR:} line. Pipeline still fails the step.
 */
final class RancherLoggedAbort extends AbortException {

    RancherLoggedAbort(String message) {
        super(message == null || message.isBlank() ? "failed" : message);
    }
}
