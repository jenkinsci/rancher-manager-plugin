package io.jenkins.plugins.ranchermanager;

import hudson.AbortException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectionModeTest {

    @Test
    public void normalize_defaultsToInherit() {
        assertEquals(ConnectionMode.INHERIT, ConnectionMode.normalize(null, ConnectionMode.INHERIT));
        assertEquals(ConnectionMode.INHERIT, ConnectionMode.normalize(" ", ConnectionMode.INHERIT));
        assertEquals(ConnectionMode.INHERIT, ConnectionMode.normalize("INHERIT", ConnectionMode.INHERIT));
        assertEquals(ConnectionMode.MANUAL, ConnectionMode.normalize("manual", ConnectionMode.INHERIT));
        assertEquals(ConnectionMode.INHERIT, ConnectionMode.normalize("other", ConnectionMode.INHERIT));
    }

    @Test
    public void setters_acceptPipelineString() {
        RancherManifestBuilder step = new RancherManifestBuilder("local");
        step.setRancherConnectionMode(RancherManifestBuilder.MODE_MANUAL);
        step.setRancherUrl("https://rancher.example");
        step.setRancherCredentialsId("rancher-token");
        assertEquals(RancherManifestBuilder.MODE_MANUAL, step.getRancherConnectionMode());
        assertEquals("https://rancher.example", step.getRancherUrl());
        assertEquals("rancher-token", step.getRancherCredentialsId());

        step.setRancherConnectionMode(RancherManifestBuilder.MODE_INHERIT);
        assertEquals(RancherManifestBuilder.MODE_INHERIT, step.getRancherConnectionMode());
        assertEquals("https://rancher.example", step.getRancherUrl());
    }

    @Test
    public void manifestSource_normalize() {
        assertEquals(ManifestSource.REPOSITORY, ManifestSource.normalize(null));
        assertEquals(ManifestSource.YAML, ManifestSource.normalize("yaml"));
        assertEquals(ManifestSource.REPOSITORY, ManifestSource.normalize("git"));
        assertEquals(ManifestSource.YAML, ManifestSource.normalize("manual"));
        assertTrue(ManifestSource.isYaml("yaml"));
        assertTrue(ManifestSource.isRepository("repository"));
    }

    @Test
    public void helmValuesSource_normalize() {
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.normalize(null));
        assertEquals(HelmValuesSource.YAML, HelmValuesSource.normalize("yaml"));
        assertEquals(HelmValuesSource.REPOSITORY, HelmValuesSource.normalize("git"));
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.normalize("off"));
        assertTrue(HelmValuesSource.isNone("none"));
        assertTrue(HelmValuesSource.isRepository("repository"));
        assertTrue(HelmValuesSource.isYaml("yaml"));
    }

    @Test
    public void resolve_inherit_failsWhenSystemEmpty() {
        AbortException ex = assertThrows(
                AbortException.class,
                () -> RancherConnections.resolve(null, ConnectionMode.INHERIT, null, null));
        assertTrue(ex.getMessage().contains("Rancher Manager is not configured"));
        assertTrue(ex.getMessage().contains("Manual"));
    }

    @Test
    public void resolve_manual_requiresUrlAndCredentials() {
        AbortException ex = assertThrows(
                AbortException.class,
                () -> RancherConnections.resolve(
                        null, ConnectionMode.MANUAL, "https://rancher.example", null));
        assertTrue(ex.getMessage().contains("Manual"));
        assertTrue(ex.getMessage().contains("Inherit"));
    }

    @Test
    public void parseClusterId_rejectsPathMeta() throws Exception {
        AbortException slash = assertThrows(
                AbortException.class, () -> RancherConnections.parseClusterId("a/b"));
        assertTrue(slash.getMessage().contains("Cluster ID"));
        assertEquals("local", RancherConnections.parseClusterId("local"));
    }

    @Test
    public void isInherit_and_isManual() {
        assertTrue(ConnectionMode.isInherit(null));
        assertTrue(ConnectionMode.isManual(ConnectionMode.MANUAL));
        assertFalse(ConnectionMode.isManual(ConnectionMode.INHERIT));
    }
}
