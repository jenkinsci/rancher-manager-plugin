package io.jenkins.plugins.ranchermanager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestSourceTest {

    @Test
    void normalize_aliasesAndUnknown() {
        assertEquals(ManifestSource.REPOSITORY, ManifestSource.normalize(null));
        assertEquals(ManifestSource.REPOSITORY, ManifestSource.normalize("  "));
        assertEquals(ManifestSource.YAML, ManifestSource.normalize("YAML"));
        assertEquals(ManifestSource.REPOSITORY, ManifestSource.normalize("git"));
        assertEquals(ManifestSource.REPOSITORY, ManifestSource.normalize("repo"));
        assertEquals(ManifestSource.YAML, ManifestSource.normalize("manual"));
        assertEquals(ManifestSource.YAML, ManifestSource.normalize("string"));
        assertEquals(ManifestSource.YAML, ManifestSource.normalize("file"));
        assertEquals(ManifestSource.REPOSITORY, ManifestSource.normalize("other"));
        assertEquals("fallback", ManifestSource.normalize("nope", "fallback"));
    }

    @Test
    void predicates_unknownFallsToRepository() {
        assertTrue(ManifestSource.isYaml("yaml"));
        assertFalse(ManifestSource.isYaml("repository"));
        assertTrue(ManifestSource.isRepository("repository"));
        assertTrue(ManifestSource.isRepository("unknown"));
        assertFalse(ManifestSource.isRepository("yaml"));
    }
}
