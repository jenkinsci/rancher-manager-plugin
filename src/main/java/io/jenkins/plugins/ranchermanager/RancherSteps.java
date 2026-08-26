package io.jenkins.plugins.ranchermanager;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import jenkins.tasks.SimpleBuildStep;

import java.io.IOException;

/**
 * Freestyle adapter: a {@link RancherLoggedAbort} is already on the console, so return {@code false}
 * instead of letting Jenkins print {@code ERROR: …}.
 */
final class RancherSteps {

    private RancherSteps() {
    }

    static boolean performFreestyle(
            AbstractBuild<?, ?> build,
            Launcher launcher,
            BuildListener listener,
            SimpleBuildStep step) throws InterruptedException, IOException {
        try {
            if (!step.requiresWorkspace()) {
                EnvVars env = build.getEnvironment(listener);
                step.perform(build, env, listener);
                return true;
            }
            FilePath workspace = build.getWorkspace();
            if (workspace == null) {
                throw new AbortException("no workspace for " + build);
            }
            step.perform(build, workspace, launcher, listener);
            return true;
        } catch (RancherLoggedAbort e) {
            return false;
        }
    }
}
