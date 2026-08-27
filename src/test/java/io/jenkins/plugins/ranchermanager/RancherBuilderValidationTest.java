package io.jenkins.plugins.ranchermanager;

import hudson.model.FreeStyleProject;
import hudson.util.FormValidation;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class RancherBuilderValidationTest {

    @Test
    void helmStaticValidationAndDefaults() {
        RancherHelmBuilder.requireLooksLikeYaml("replicaCount: 1\n");
        RancherHelmBuilder.requireLooksLikeYaml("---\nfoo: 1\n");
        assertThrows(IllegalArgumentException.class, () -> RancherHelmBuilder.requireLooksLikeYaml(""));
        assertThrows(IllegalArgumentException.class, () -> RancherHelmBuilder.requireLooksLikeYaml("plain"));
        RancherHelmBuilder.requireValidReleaseName("demo-nginx");
        assertThrows(IllegalArgumentException.class, () -> RancherHelmBuilder.requireValidReleaseName(""));
        assertThrows(IllegalArgumentException.class, () -> RancherHelmBuilder.requireValidReleaseName("BAD"));
        assertEquals("Chart", RancherHelmBuilder.requireNonBlank(" Chart ", "Chart"));
        assertThrows(IllegalArgumentException.class, () -> RancherHelmBuilder.requireNonBlank(" ", "Chart"));

        RancherHelmBuilder step = new RancherHelmBuilder(null, null, null, null);
        assertEquals("", step.getClusterId());
        assertEquals(RancherNamespaces.DEFAULT, step.getNamespace());
        step.setNamespace("  ");
        assertEquals(RancherNamespaces.DEFAULT, step.getNamespace());
        step.setVersion("  ");
        assertEquals(null, step.getVersion());
        step.setVersion("1.2.3");
        assertEquals("1.2.3", step.getVersion());
        assertEquals(RancherHelmBuilder.DEFAULT_VALUES_FILE, step.getValuesFilePath());
        step.setValuesFilePath("  ");
        assertEquals(RancherHelmBuilder.DEFAULT_VALUES_FILE, step.getValuesFilePath());
        step.setValuesGitCredentialsId("  ");
        assertEquals(null, step.getValuesGitCredentialsId());
        assertEquals(
                RancherHelmBuilder.DEFAULT_REPOSITORY_REFERENCE, step.getValuesRepositoryReferenceName());
        step.setValuesRepositoryReferenceName(" refs/heads/dev ");
        assertEquals("refs/heads/dev", step.getValuesRepositoryReferenceName());
        step.setWaitTimeoutSeconds("  ");
        assertEquals("300", step.getWaitTimeoutSeconds());
        assertFalse(step.requiresWorkspace());
        step.setValuesSource(RancherHelmBuilder.VALUES_REPOSITORY);
        assertTrue(step.requiresWorkspace());
        assertTrue(step.isEnsureNamespace());
    }

    @Test
    void manifestStaticValidation(JenkinsRule jenkins) {
        RancherManifestBuilder.requireLooksLikeYaml("apiVersion: v1\nkind: ConfigMap\n");
        RancherManifestBuilder.requireLooksLikeYaml("---\nkind: ConfigMap\n");
        assertThrows(IllegalArgumentException.class, () -> RancherManifestBuilder.requireLooksLikeYaml(""));
        assertThrows(
                IllegalArgumentException.class, () -> RancherManifestBuilder.requireLooksLikeYaml("plain"));
        RancherManifestBuilder.requireHttpRepoUrl("https://gitlab.example/r.git");
        assertThrows(IllegalArgumentException.class, () -> RancherManifestBuilder.requireHttpRepoUrl(" "));
        assertThrows(
                IllegalArgumentException.class,
                () -> RancherManifestBuilder.requireHttpRepoUrl("git@host:repo.git"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RancherManifestBuilder.requireHttpRepoUrl("https://u:p@gitlab.example/r.git"));
        RancherManifestBuilder step = new RancherManifestBuilder("local");
        assertEquals("300", step.getWaitTimeoutSeconds());
        step.setWaitTimeoutSeconds("  ");
        assertEquals("300", step.getWaitTimeoutSeconds());
    }

    @Test
    void helmDescriptorFormChecks(JenkinsRule jenkins) {
        RancherHelmBuilder.DescriptorImpl d =
                jenkins.jenkins.getDescriptorByType(RancherHelmBuilder.DescriptorImpl.class);
        assertEquals("Rancher Helm Deployment", d.getDisplayName());
        assertTrue(d.isApplicable(FreeStyleProject.class));
        assertTrue(d.getRancherConnectionSummary().contains("not configured"));
        assertEquals(FormValidation.Kind.ERROR, d.doCheckReleaseName("", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckReleaseName("${REL}", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckReleaseName("demo-nginx", null).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckReleaseName("BAD", null).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckProject("", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckProject("${P}", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckProject("mnp", null).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckProject("local:p-abc", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckNamespace("", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckNamespace("${NS}", null).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckNamespace("Bad", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckWaitTimeoutSeconds("", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckWaitTimeoutSeconds("${T}", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckWaitTimeoutSeconds("30", null).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckWaitTimeoutSeconds("0", null).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckRepo("", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckRepo("${R}", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckRepo("https://charts.example/helm", null).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckRepo("git@host:repo", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckClusterId("local", ConnectionMode.MANUAL, null).kind);
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckRancherUrl("https://rancher.example", ConnectionMode.MANUAL, null).kind);
    }

    @Test
    void helmDescriptorValuesYamlChecks(JenkinsRule jenkins) {
        RancherHelmBuilder.DescriptorImpl d =
                jenkins.jenkins.getDescriptorByType(RancherHelmBuilder.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.OK, d.doCheckValues("", HelmValuesSource.NONE, null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckValues("", HelmValuesSource.REPOSITORY, null).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckValues("", HelmValuesSource.YAML, null).kind);
        assertEquals(
                FormValidation.Kind.OK, d.doCheckValues("replicaCount: 1\n", HelmValuesSource.YAML, null).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckValues("plain", HelmValuesSource.YAML, null).kind);
    }

    @Test
    void helmDescriptorFillCredentials(JenkinsRule jenkins) {
        RancherHelmBuilder.DescriptorImpl d =
                jenkins.jenkins.getDescriptorByType(RancherHelmBuilder.DescriptorImpl.class);
        assertFalse(d.doFillRancherCredentialsIdItems(null, "").isEmpty());
        assertFalse(d.doFillValuesGitCredentialsIdItems(null, "").isEmpty());
    }

    @Test
    void manifestDescriptorFormChecks(JenkinsRule jenkins) {
        RancherManifestBuilder.DescriptorImpl d =
                jenkins.jenkins.getDescriptorByType(RancherManifestBuilder.DescriptorImpl.class);
        assertEquals("Rancher Manifest Deployment", d.getDisplayName());
        assertTrue(d.isApplicable(FreeStyleProject.class));
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckRepositoryUrl("", ManifestSource.YAML, null).kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckRepositoryUrl("", ManifestSource.REPOSITORY, null).kind);
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckRepositoryUrl("https://gitlab.example/r.git", ManifestSource.REPOSITORY, null)
                        .kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckRepositoryUrl("git@host:repo", ManifestSource.REPOSITORY, null).kind);
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckManifestYaml("", ManifestSource.REPOSITORY, null).kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckManifestYaml("", ManifestSource.YAML, null).kind);
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckManifestYaml("apiVersion: v1\nkind: ConfigMap\n", ManifestSource.YAML, null)
                        .kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                d.doCheckManifestYaml("plain", ManifestSource.YAML, null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckWaitTimeoutSeconds("", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckWaitTimeoutSeconds("${T}", null).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckWaitTimeoutSeconds("-1", null).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckClusterId("local", ConnectionMode.MANUAL, null).kind);
        assertFalse(d.doFillRancherCredentialsIdItems(null, "").isEmpty());
        assertFalse(d.doFillGitCredentialsIdItems(null, "").isEmpty());
    }
}
