package io.jenkins.plugins.ranchermanager;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.FreeStyleProject;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
public class RancherCredentialsTest {

    @Test
    public void resolveApiToken_secretText(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "rancher-token",
                        "token",
                        Secret.fromString("token-VALUE")));
        SystemCredentialsProvider.getInstance().save();

        assertEquals("token-VALUE", RancherCredentials.resolveApiToken("rancher-token", null));

        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "rancher-bearer-header",
                        "token",
                        Secret.fromString("Bearer token-abc:secret\n")));
        SystemCredentialsProvider.getInstance().save();
        assertEquals("token-abc:secret", RancherCredentials.resolveApiToken("rancher-bearer-header", null));
    }

    @Test
    public void normalizeApiToken_stripsBearerPrefixAndQuotes() {
        assertEquals("token-abc:secret", RancherCredentials.normalizeApiToken("  token-abc:secret\n"));
        assertEquals("token-abc:secret", RancherCredentials.normalizeApiToken("Bearer token-abc:secret"));
        assertEquals("token-abc:secret", RancherCredentials.normalizeApiToken("bearer token-abc:secret"));
        assertEquals("token-abc:secret", RancherCredentials.normalizeApiToken("\"token-abc:secret\""));
        assertEquals("", RancherCredentials.normalizeApiToken("Bearer "));
        assertEquals("", RancherCredentials.normalizeApiToken(null));
        assertEquals("token-abc:secret", RancherCredentials.normalizeApiToken("\uFEFFBearer token-abc:secret"));
    }

    @Test
    public void resolveApiToken_missing_doesNotLeak(JenkinsRule jenkins) {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> RancherCredentials.resolveApiToken("missing-id", null));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    public void fillSecretText_systemRequiresManage(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("reader"));

        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "rancher-token",
                        "token",
                        Secret.fromString("token-VALUE")));
        SystemCredentialsProvider.getInstance().save();

        try (ACLContext ignored = ACL.as(User.getById("reader", true))) {
            ListBoxModel model = RancherCredentials.fillSecretText(null, "");
            assertTrue(model.stream().noneMatch(o -> "rancher-token".equals(o.value)));
        }
    }

    @Test
    public void fillSecretText_jobConfigureIncludesValue(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "rancher-token",
                        "token",
                        Secret.fromString("token-VALUE")));
        SystemCredentialsProvider.getInstance().save();

        FreeStyleProject project = jenkins.createFreeStyleProject();
        ListBoxModel model = RancherCredentials.fillSecretText(project, "rancher-token");
        assertTrue(model.stream().anyMatch(o -> "rancher-token".equals(o.value)));
    }

    @Test
    public void resolveGitAuth_blankReturnsNull(JenkinsRule jenkins) {
        assertNull(RancherCredentials.resolveGitAuth(null, null));
        assertNull(RancherCredentials.resolveGitAuth("  ", null));
    }

    @Test
    public void resolveGitAuth_usernamePassword(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "git-up",
                        "git",
                        "clone-user",
                        "clone-pass"));
        SystemCredentialsProvider.getInstance().save();

        RancherCredentials.GitAuth auth = RancherCredentials.resolveGitAuth("git-up", null);
        assertEquals("clone-user", auth.username);
        assertEquals("clone-pass", auth.password);
    }

    @Test
    public void resolveGitAuth_secretTextAsOauth2(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "git-pat",
                        "PAT",
                        Secret.fromString("glpat-TOKEN")));
        SystemCredentialsProvider.getInstance().save();

        RancherCredentials.GitAuth auth = RancherCredentials.resolveGitAuth("git-pat", null);
        assertEquals("oauth2", auth.username);
        assertEquals("glpat-TOKEN", auth.password);
    }

    @Test
    public void fillSecretOrUsernamePassword_listsBoth(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "git-up-listed",
                        "git",
                        "u",
                        "p"));
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "git-pat-listed",
                        "PAT",
                        Secret.fromString("tok")));
        SystemCredentialsProvider.getInstance().save();

        ListBoxModel model = RancherCredentials.fillSecretOrUsernamePassword(null, null);
        assertTrue(model.stream().anyMatch(o -> "git-up-listed".equals(o.value)));
        assertTrue(model.stream().anyMatch(o -> "git-pat-listed".equals(o.value)));
    }
}
