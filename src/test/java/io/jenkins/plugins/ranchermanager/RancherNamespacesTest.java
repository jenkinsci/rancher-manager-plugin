package io.jenkins.plugins.ranchermanager;

import hudson.EnvVars;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RancherNamespacesTest {

    @Test
    void resolve_expandsBuildEnv() {
        EnvVars env = new EnvVars();
        env.put("NAMESPACE", "apps");
        assertEquals("apps", RancherNamespaces.resolve("${NAMESPACE}", env));
    }

    @Test
    void resolve_defaultWhenBlank() {
        assertEquals(RancherNamespaces.DEFAULT, RancherNamespaces.resolve("  ", new EnvVars()));
        assertEquals(RancherNamespaces.DEFAULT, RancherNamespaces.resolve(null, null));
        EnvVars env = new EnvVars();
        env.put("NAMESPACE", "  ");
        assertEquals(RancherNamespaces.DEFAULT, RancherNamespaces.resolve("${NAMESPACE}", env));
    }

    @Test
    void validate_dns1123() {
        assertNull(RancherNamespaces.validate(null));
        assertNull(RancherNamespaces.validate("  "));
        assertNull(RancherNamespaces.validate("apps"));
        assertNull(RancherNamespaces.validate("a"));
        assertTrue(RancherNamespaces.validate("Bad_Name").contains("DNS-1123"));
        assertTrue(RancherNamespaces.validate("a".repeat(64)).contains("DNS-1123"));
        assertTrue(RancherNamespaces.validate("-leading").contains("DNS-1123"));
    }

    @Test
    void resolve_rejectsInvalidAfterExpand() {
        EnvVars env = new EnvVars();
        env.put("NAMESPACE", "Bad_Name");
        assertThrows(
                IllegalArgumentException.class, () -> RancherNamespaces.resolve("${NAMESPACE}", env));
    }
}
