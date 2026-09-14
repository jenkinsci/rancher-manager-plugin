package io.jenkins.plugins.ranchermanager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RancherHelmBuilderLegacyAtomicTest {

    @Test
    void applyLegacyAtomicBundle_oldAtomicTrue() {
        RancherHelmBuilder step = new RancherHelmBuilder(
                "local", "demo", "nginx", "https://charts.example/helm");
        step.setAtomic(true);
        step.setWaitTimeoutSeconds("120");
        step.applyLegacyAtomicBundle();
        assertEquals(Boolean.TRUE, step.getHelmWait());
        assertEquals(Boolean.TRUE, step.getCleanupOnFail());
        assertEquals("120", step.getHelmTimeoutSeconds());
        assertFalse(step.isAtomic());
    }

    @Test
    void applyLegacyAtomicBundle_skipsWhenHelmWaitSet() {
        RancherHelmBuilder step = new RancherHelmBuilder(
                "local", "demo", "nginx", "https://charts.example/helm");
        step.setHelmWait(false);
        step.setAtomic(true);
        step.applyLegacyAtomicBundle();
        assertEquals(Boolean.FALSE, step.getHelmWait());
        assertTrue(step.isAtomic());
        assertEquals(Boolean.FALSE, step.getCleanupOnFail());
    }

    @Test
    void applyLegacyAtomicBundle_defaultsUnsetBooleans() {
        RancherHelmBuilder step = new RancherHelmBuilder(
                "local", "demo", "nginx", "https://charts.example/helm");
        step.applyLegacyAtomicBundle();
        assertEquals(Boolean.FALSE, step.getHelmWait());
        assertEquals(Boolean.FALSE, step.getCleanupOnFail());
        assertFalse(step.isAtomic());
    }
}
