package io.jenkins.plugins.ranchermanager;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import java.util.Collections;
import java.util.List;

/**
 * Resolves Rancher API tokens and optional Git clone credentials. Never logs secrets.
 */
final class RancherCredentials {

    private RancherCredentials() {
    }

    static String resolveApiToken(String credentialsId, Item item) {
        if (credentialsId == null || credentialsId.isBlank()) {
            throw new IllegalStateException(
                    "Credentials ID is required — create a Secret text credential with the Rancher API token.");
        }
        Credentials creds = findCredential(credentialsId, item, StringCredentials.class);
        if (!(creds instanceof StringCredentials sc)) {
            throw new IllegalStateException(
                    "Credentials '" + credentialsId
                            + "' type is not supported (use Secret text for the Rancher API token).");
        }
        String secret = normalizeApiToken(sc.getSecret().getPlainText());
        if (secret.isBlank()) {
            throw new IllegalStateException("Credentials '" + credentialsId + "' has an empty secret.");
        }
        return secret;
    }

    /**
     * Username/password for Git clone, or Secret text as password with username {@code oauth2} (PAT).
     *
     * @return {@code null} when credentialsId is blank
     */
    static GitAuth resolveGitAuth(String credentialsId, Item item) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        Credentials creds = findGitCredential(credentialsId, item);
        if (creds instanceof StandardUsernamePasswordCredentials up) {
            String user = up.getUsername();
            String pass = up.getPassword().getPlainText();
            if (pass.isBlank()) {
                throw new IllegalStateException("Git credentials '" + credentialsId + "' has an empty password.");
            }
            return new GitAuth(user, pass);
        }
        if (creds instanceof StringCredentials sc) {
            String secret = sc.getSecret().getPlainText();
            if (secret.isBlank()) {
                throw new IllegalStateException("Git credentials '" + credentialsId + "' has an empty secret.");
            }
            return new GitAuth("oauth2", secret);
        }
        throw new IllegalStateException(
                "Git credentials '" + credentialsId
                        + "' type is not supported (use Username/Password or Secret text).");
    }

    /**
     * Trim, strip wrapping quotes, and drop a leading {@code Bearer } so operators can paste
     * the Rancher UI Bearer Token or a curl header into Secret text.
     */
    static String normalizeApiToken(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1).trim();
        }
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        if (s.regionMatches(true, 0, "Bearer", 0, 6)
                && (s.length() == 6 || Character.isWhitespace(s.charAt(6)))) {
            s = s.substring(6).trim();
        }
        return s;
    }

    /**
     * Dropdown for Rancher API token / Secret text credentials.
     * Job: {@link Item#CONFIGURE}; System ({@code item == null}): {@link Jenkins#MANAGE}.
     */
    static ListBoxModel fillSecretText(Item item, String currentValue) {
        return fillMatching(item, currentValue, CredentialsMatchers.instanceOf(StringCredentials.class));
    }

    /** Dropdown for Git credentials (Username/Password or Secret text). */
    static ListBoxModel fillSecretOrUsernamePassword(Item item, String currentValue) {
        return fillMatching(
                item,
                currentValue,
                CredentialsMatchers.anyOf(
                        CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                        CredentialsMatchers.instanceOf(StringCredentials.class)));
    }

    private static ListBoxModel fillMatching(Item item, String currentValue, CredentialsMatcher matcher) {
        StandardListBoxModel model = new StandardListBoxModel();
        model.includeEmptyValue();
        if (item == null) {
            if (!Jenkins.get().hasPermission(Jenkins.MANAGE)) {
                model.includeCurrentValue(currentValue);
                return model;
            }
            model.includeMatchingAs(
                    ACL.SYSTEM2,
                    Jenkins.get(),
                    StandardCredentials.class,
                    Collections.emptyList(),
                    matcher);
        } else if (!item.hasPermission(Item.CONFIGURE)) {
            model.includeCurrentValue(currentValue);
            return model;
        } else {
            model.includeMatchingAs(
                    ACL.SYSTEM2,
                    item,
                    StandardCredentials.class,
                    Collections.emptyList(),
                    matcher);
        }
        model.includeCurrentValue(currentValue);
        return model;
    }

    private static Credentials findCredential(
            String credentialsId, Item item, Class<? extends Credentials> type) {
        List<StandardCredentials> candidates = lookup(item);
        Credentials creds = CredentialsMatchers.firstOrNull(
                candidates,
                CredentialsMatchers.allOf(
                        CredentialsMatchers.withId(credentialsId),
                        CredentialsMatchers.instanceOf(type)));
        if (creds == null) {
            throw new IllegalStateException(
                    "Credentials '" + credentialsId + "' not found, not accessible, "
                            + "or not of the expected type.");
        }
        return creds;
    }

    private static Credentials findGitCredential(String credentialsId, Item item) {
        List<StandardCredentials> candidates = lookup(item);
        Credentials creds = CredentialsMatchers.firstOrNull(
                candidates,
                CredentialsMatchers.allOf(
                        CredentialsMatchers.withId(credentialsId),
                        CredentialsMatchers.anyOf(
                                CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                CredentialsMatchers.instanceOf(StringCredentials.class))));
        if (creds == null) {
            throw new IllegalStateException(
                    "Git credentials '" + credentialsId + "' not found, not accessible, "
                            + "or not Username/Password / Secret text.");
        }
        return creds;
    }

    private static List<StandardCredentials> lookup(Item item) {
        if (item != null) {
            return CredentialsProvider.lookupCredentialsInItem(
                    StandardCredentials.class,
                    item,
                    ACL.SYSTEM2,
                    Collections.emptyList());
        }
        return CredentialsProvider.lookupCredentialsInItemGroup(
                StandardCredentials.class,
                Jenkins.get(),
                ACL.SYSTEM2,
                Collections.emptyList());
    }

    /**
     * Ephemeral Git username/password resolved from Credentials at runtime.
     * Job XML stores only the credentials id.
     */
    static final class GitAuth {
        final String username;
        /** Resolved credential secret; never persisted as a config field. */
        @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
        final String password;

        GitAuth(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }
}
