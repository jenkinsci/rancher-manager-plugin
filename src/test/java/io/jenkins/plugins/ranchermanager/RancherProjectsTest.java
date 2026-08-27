package io.jenkins.plugins.ranchermanager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RancherProjectsTest {

    @Test
    public void validate_blank() {
        assertEquals("Project is required.", RancherProjects.validate(null));
        assertEquals("Project is required.", RancherProjects.validate("  "));
    }

    @Test
    public void validate_rejectsClusterPrefixedId() {
        String err = RancherProjects.validate("local:p-abc12");
        assertTrue(err.contains("Cluster belongs in Cluster ID"));
    }

    @Test
    public void validate_acceptsNameAndId() {
        assertNull(RancherProjects.validate("mnp"));
        assertNull(RancherProjects.validate("Default"));
        assertNull(RancherProjects.validate("p-abc12"));
    }

    @Test
    public void looksLikeProjectId() {
        assertTrue(RancherProjects.looksLikeProjectId("p-abc12"));
        assertTrue(RancherProjects.looksLikeProjectId("P-ABC12"));
        assertFalse(RancherProjects.looksLikeProjectId("mnp"));
        assertFalse(RancherProjects.looksLikeProjectId("local:p-abc12"));
    }

    @Test
    public void resolve_trims() {
        assertEquals("mnp", RancherProjects.resolve("  mnp  ", null));
        hudson.EnvVars env = new hudson.EnvVars();
        env.put("PROJECT", "p-abc12");
        assertEquals("p-abc12", RancherProjects.resolve("${PROJECT}", env));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> RancherProjects.resolve("local:p-abc12", null));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> RancherProjects.resolve("  ", null));
        assertFalse(RancherProjects.looksLikeProjectId(null));
        assertFalse(RancherProjects.looksLikeProjectId(" "));
    }
}
