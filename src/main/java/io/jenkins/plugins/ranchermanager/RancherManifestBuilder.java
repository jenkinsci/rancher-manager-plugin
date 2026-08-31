package io.jenkins.plugins.ranchermanager;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Freestyle / Pipeline build step: Rancher Manager manifest deploy
 * ({@code @Symbol("rancherManifest")}).
 * <p>
 * Kubernetes namespace comes from the manifest. This step does not create namespaces
 * and does not rewrite YAML. Git source is fetched on the agent via shallow clone.
 */
public class RancherManifestBuilder extends Builder implements SimpleBuildStep {

    private static final Logger LOGGER = Logger.getLogger(RancherManifestBuilder.class.getName());

    public static final String MODE_INHERIT = ConnectionMode.INHERIT;
    public static final String MODE_MANUAL = ConnectionMode.MANUAL;
    public static final String SOURCE_REPOSITORY = ManifestSource.REPOSITORY;
    public static final String SOURCE_YAML = ManifestSource.YAML;
    public static final String DEFAULT_MANIFEST_FILE = "manifest.yaml";
    public static final String DEFAULT_REPOSITORY_REFERENCE = "refs/heads/main";

    private final String clusterId;

    private String manifestSource;
    private String repositoryUrl = "";
    private String manifestFilePath = DEFAULT_MANIFEST_FILE;
    private String repositoryReferenceName;
    private String gitCredentialsId;
    private String manifestYaml;

    private String rancherConnectionMode;
    private String rancherUrl;
    private String rancherCredentialsId;
    private String waitTimeoutSeconds;
    private boolean validateOnly;
    private boolean verboseLogging;

    @DataBoundConstructor
    public RancherManifestBuilder(String clusterId) {
        this.clusterId = clusterId == null ? "" : clusterId.trim();
    }

    public String getClusterId() {
        return clusterId;
    }

    public String getManifestSource() {
        return ManifestSource.normalize(manifestSource);
    }

    @DataBoundSetter
    public void setManifestSource(String manifestSource) {
        this.manifestSource = ManifestSource.normalize(manifestSource);
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    @DataBoundSetter
    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl == null ? "" : repositoryUrl.trim();
    }

    public String getManifestFilePath() {
        return manifestFilePath == null || manifestFilePath.isBlank()
                ? DEFAULT_MANIFEST_FILE
                : manifestFilePath.trim();
    }

    @DataBoundSetter
    public void setManifestFilePath(String manifestFilePath) {
        this.manifestFilePath = manifestFilePath == null || manifestFilePath.isBlank()
                ? DEFAULT_MANIFEST_FILE
                : manifestFilePath.trim();
    }

    public String getRepositoryReferenceName() {
        return repositoryReferenceName == null || repositoryReferenceName.isBlank()
                ? DEFAULT_REPOSITORY_REFERENCE
                : repositoryReferenceName;
    }

    @DataBoundSetter
    public void setRepositoryReferenceName(String repositoryReferenceName) {
        this.repositoryReferenceName =
                repositoryReferenceName == null || repositoryReferenceName.isBlank()
                        ? null
                        : repositoryReferenceName.trim();
    }

    public String getGitCredentialsId() {
        return gitCredentialsId;
    }

    @DataBoundSetter
    public void setGitCredentialsId(String gitCredentialsId) {
        this.gitCredentialsId =
                gitCredentialsId == null || gitCredentialsId.isBlank() ? null : gitCredentialsId.trim();
    }

    public String getManifestYaml() {
        return manifestYaml;
    }

    @DataBoundSetter
    public void setManifestYaml(String manifestYaml) {
        this.manifestYaml = manifestYaml;
    }

    public String getRancherConnectionMode() {
        return ConnectionMode.normalize(rancherConnectionMode, MODE_INHERIT);
    }

    @DataBoundSetter
    public void setRancherConnectionMode(String rancherConnectionMode) {
        this.rancherConnectionMode = rancherConnectionMode;
    }

    public String getRancherUrl() {
        return rancherUrl;
    }

    @DataBoundSetter
    public void setRancherUrl(String rancherUrl) {
        this.rancherUrl = rancherUrl == null ? "" : rancherUrl.trim();
    }

    public String getRancherCredentialsId() {
        return rancherCredentialsId;
    }

    @DataBoundSetter
    public void setRancherCredentialsId(String rancherCredentialsId) {
        this.rancherCredentialsId = rancherCredentialsId == null ? "" : rancherCredentialsId.trim();
    }

    public String getWaitTimeoutSeconds() {
        return waitTimeoutSeconds == null || waitTimeoutSeconds.isBlank()
                ? String.valueOf(HelmAppStates.DEFAULT_TIMEOUT_SECONDS)
                : waitTimeoutSeconds.trim();
    }

    @DataBoundSetter
    public void setWaitTimeoutSeconds(String waitTimeoutSeconds) {
        this.waitTimeoutSeconds = waitTimeoutSeconds == null || waitTimeoutSeconds.isBlank()
                ? null
                : waitTimeoutSeconds.trim();
    }

    public boolean isValidateOnly() {
        return validateOnly;
    }

    @DataBoundSetter
    public void setValidateOnly(boolean validateOnly) {
        this.validateOnly = validateOnly;
    }

    public boolean isVerboseLogging() {
        return verboseLogging;
    }

    @DataBoundSetter
    public void setVerboseLogging(boolean verboseLogging) {
        this.verboseLogging = verboseLogging;
    }

    /**
     * Workspace required when manifest source is Git (Jenkins fetch on agent).
     */
    @Override
    public boolean requiresWorkspace() {
        return ManifestSource.isRepository(getManifestSource());
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull EnvVars buildEnv,
            @NonNull TaskListener listener) throws InterruptedException, IOException {
        performManifest(run, buildEnv, null, null, listener);
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull EnvVars env,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener) throws InterruptedException, IOException {
        performManifest(run, env, workspace, launcher, listener);
    }

    private void performManifest(
            Run<?, ?> run,
            EnvVars buildEnv,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener) throws InterruptedException, IOException {
        try (RancherBuildLogger log = new RancherBuildLogger(LOGGER, listener, verboseLogging)) {
            log.open(RancherBuildLogger.TITLE_MANIFEST);
            performBody(run, buildEnv, workspace, launcher, listener, log);
        }
    }

    private void performBody(
            Run<?, ?> run,
            EnvVars buildEnv,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener,
            RancherBuildLogger log) throws AbortException, InterruptedException {
        long startedNs = System.nanoTime();

        RancherConnections.Authenticated auth = RancherConnections.resolveAuthenticated(
                RancherGlobalConfiguration.get(),
                getRancherConnectionMode(),
                rancherUrl,
                rancherCredentialsId,
                run.getParent(),
                log);
        ResolvedConnection connection = auth.connection;
        ManifestInputs inputs = parseInputs(buildEnv, log);

        log.debug(RancherBuildLogger.formatConnection(connection));
        log.info("source=" + inputs.sourceLabel);

        RancherCredentials.GitAuth gitAuth = null;
        if (!inputs.yamlMode) {
            gitAuth = RancherConnections.resolveOptionalGitAuth(gitCredentialsId, run.getParent(), log);
        }

        try (RancherClient client = new RancherClient(
                connection.connectTimeoutMs, connection.readTimeoutMs, log)) {
            RancherConnections.runPreflight(client, connection, auth.apiToken, inputs.clusterId, log);

            if (validateOnly) {
                finishValidateOnly(log, startedNs, inputs);
                return;
            }

            String yamlContent = resolveYamlContent(inputs, gitAuth, workspace, launcher, listener, log);
            log.debug("yamlLength=" + yamlContent.length()
                    + " yamlHash=" + RancherConnections.shortContentHash(yamlContent));
            java.util.List<ManifestWorkloads.Workload> workloads =
                    RancherConnections.abortOn(log, () -> ManifestWorkloads.parse(yamlContent));
            log.info("Applying manifest YAML");
            client.applyYaml(connection.baseUrl, auth.apiToken, inputs.clusterId, yamlContent);

            if (!workloads.isEmpty()) {
                log.info("Waiting for manifest workloads count=" + workloads.size()
                        + " timeoutSeconds=" + inputs.waitTimeoutSeconds);
            }
            client.waitUntilManifestWorkloadsReady(
                    connection.baseUrl,
                    auth.apiToken,
                    inputs.clusterId,
                    workloads,
                    inputs.waitTimeoutSeconds * 1000L,
                    HelmAppStates.pollIntervalMs());

            var fields = RancherBuildLogger.summaryFields();
            fields.put("outcome", "applied");
            fields.put("clusterId", inputs.clusterId);
            fields.put("manifestSource", inputs.sourceLabel);
            fields.put("mode", connection.mode);
            log.summaryWithDuration(startedNs, fields);
        } catch (AbortException e) {
            throw e;
        } catch (IOException e) {
            throw RancherConnections.abort(
                    log, "Manifest operation failed: " + RancherConnections.truncateMessage(e), e);
        }
    }

    private String resolveYamlContent(
            ManifestInputs inputs,
            RancherCredentials.GitAuth gitAuth,
            FilePath workspace,
            Launcher launcher,
            TaskListener listener,
            RancherBuildLogger log) throws IOException, InterruptedException, AbortException {
        if (inputs.yamlMode) {
            return inputs.yamlContent;
        }
        if (workspace == null || launcher == null) {
            throw RancherConnections.abort(
                    log,
                    "Repository manifest source requires an agent workspace (wrap the step in node { } / agent any).");
        }
        log.info("Fetching manifest from Git");
        log.debug("gitRef=" + inputs.gitRef + " path=" + inputs.manifestPath);
        return GitRepositoryFiles.readFile(
                inputs.repoUrl,
                inputs.gitRef,
                inputs.manifestPath,
                new GitRepositoryFiles.CloneContext(gitAuth, workspace, launcher, listener));
    }

    private ManifestInputs parseInputs(EnvVars buildEnv, RancherBuildLogger log) throws AbortException {
        final String cluster = RancherConnections.abortOn(
                log, () -> RancherConnections.resolveClusterId(clusterId, buildEnv));
        final int waitSeconds = RancherConnections.abortOn(log, () -> {
            String raw = waitTimeoutSeconds == null ? "" : buildEnv.expand(waitTimeoutSeconds).trim();
            return HelmAppStates.parseTimeoutSeconds(raw);
        });

        if (ManifestSource.isYaml(getManifestSource())) {
            String yaml = manifestYaml;
            if (yaml == null || yaml.isBlank()) {
                throw RancherConnections.abort(log, "Manifest YAML is required for Manual YAML source.");
            }
            RancherConnections.abortOn(log, () -> {
                requireLooksLikeYaml(yaml);
                return null;
            });
            return ManifestInputs.yaml(cluster, yaml, waitSeconds);
        }

        String repo = repositoryUrl == null ? "" : buildEnv.expand(repositoryUrl).trim();
        if (repo.isBlank()) {
            throw RancherConnections.abort(log, "Repository URL is required for Repository manifest source.");
        }
        RancherConnections.abortOn(log, () -> {
            requireHttpRepoUrl(repo);
            return null;
        });
        String path = buildEnv.expand(getManifestFilePath()).trim();
        String ref = buildEnv.expand(getRepositoryReferenceName()).trim();
        return ManifestInputs.git(cluster, repo, path, ref, waitSeconds);
    }

    private void finishValidateOnly(RancherBuildLogger log, long startedNs, ManifestInputs inputs) {
        log.info("Validate-only — skipping Rancher mutations");
        if (inputs.yamlMode) {
            log.debug("Would apply Manual YAML (length=" + inputs.yamlContent.length() + ")");
        } else {
            log.debug("Would fetch " + inputs.manifestPath + " from Git ref " + inputs.gitRef);
            log.debug("Would apply YAML via Steve action=apply");
        }
        log.debug("Would wait for manifest workloads timeoutSeconds=" + inputs.waitTimeoutSeconds);
        var fields = RancherBuildLogger.summaryFields();
        fields.put("outcome", "validated");
        fields.put("clusterId", inputs.clusterId);
        fields.put("manifestSource", inputs.sourceLabel);
        log.summaryWithDuration(startedNs, fields);
    }

    static void requireLooksLikeYaml(String content) {
        String trimmed = content.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Manifest YAML is empty.");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.contains("apiversion:") && !trimmed.contains("---")) {
            throw new IllegalArgumentException("Manifest YAML does not look like Kubernetes YAML.");
        }
    }

    static void requireHttpRepoUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Repository URL is required.");
        }
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new IllegalArgumentException("Repository URL must start with http:// or https://.");
        }
        if (u.indexOf('@') >= 0) {
            throw new IllegalArgumentException("Repository URL must not contain userinfo.");
        }
    }

    private record ManifestInputs(
            String clusterId,
            boolean yamlMode,
            String sourceLabel,
            String yamlContent,
            String repoUrl,
            String manifestPath,
            String gitRef,
            int waitTimeoutSeconds) {
        static ManifestInputs yaml(String clusterId, String content, int waitTimeoutSeconds) {
            return new ManifestInputs(
                    clusterId, true, SOURCE_YAML, content, null, null, null, waitTimeoutSeconds);
        }

        static ManifestInputs git(
                String clusterId, String repo, String path, String ref, int waitTimeoutSeconds) {
            return new ManifestInputs(
                    clusterId, false, SOURCE_REPOSITORY, null, repo, path, ref, waitTimeoutSeconds);
        }
    }

    @Symbol("rancherManifest")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Rancher Manifest Deployment";
        }

        public String getRancherConnectionSummary() {
            return RancherConnections.connectionSummary();
        }

        @POST
        public FormValidation doCheckClusterId(
                @QueryParameter String value,
                @QueryParameter String rancherConnectionMode,
                @AncestorInPath Item item) {
            RancherConnections.checkConfigure(item);
            return RancherConnections.checkClusterId(value, rancherConnectionMode);
        }

        @POST
        public FormValidation doCheckRancherUrl(
                @QueryParameter String value,
                @QueryParameter String rancherConnectionMode,
                @AncestorInPath Item item) {
            RancherConnections.checkConfigure(item);
            return RancherConnections.checkRancherUrl(value, rancherConnectionMode);
        }

        @POST
        public FormValidation doCheckRepositoryUrl(
                @QueryParameter String value,
                @QueryParameter String manifestSource,
                @AncestorInPath Item item) {
            RancherConnections.checkConfigure(item);
            if (ManifestSource.isYaml(manifestSource)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Repository URL is required.");
            }
            try {
                requireHttpRepoUrl(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckManifestYaml(
                @QueryParameter String value,
                @QueryParameter String manifestSource,
                @AncestorInPath Item item) {
            RancherConnections.checkConfigure(item);
            if (!ManifestSource.isYaml(manifestSource)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Manifest YAML is required for Manual YAML source.");
            }
            try {
                requireLooksLikeYaml(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckWaitTimeoutSeconds(@QueryParameter String value, @AncestorInPath Item item) {
            RancherConnections.checkConfigure(item);
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            if (value.trim().indexOf('$') >= 0) {
                return FormValidation.ok();
            }
            try {
                HelmAppStates.parseTimeoutSeconds(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public ListBoxModel doFillRancherCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String rancherCredentialsId) {
            return RancherCredentials.fillSecretText(item, rancherCredentialsId);
        }

        @POST
        public ListBoxModel doFillGitCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String gitCredentialsId) {
            return RancherCredentials.fillSecretOrUsernamePassword(item, gitCredentialsId);
        }
    }
}
