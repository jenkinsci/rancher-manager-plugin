package io.jenkins.plugins.ranchermanager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelmValuesSourceTest {

    @Test
    void resolve_defaultsToNoneUnlessBareValues() {
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.resolve(null, null));
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.resolve("", "  "));
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.resolve("none", "replicaCount: 1"));
        assertEquals(HelmValuesSource.YAML, HelmValuesSource.resolve(null, "replicaCount: 1\n"));
        assertEquals(HelmValuesSource.YAML, HelmValuesSource.resolve("  ", "a: 1"));
        assertEquals(HelmValuesSource.REPOSITORY, HelmValuesSource.resolve("git", null));
    }

    @Test
    void normalize_aliasesAndUnknown() {
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.normalize("off"));
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.normalize("disabled"));
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.normalize("empty"));
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.normalize("chart"));
        assertEquals(HelmValuesSource.REPOSITORY, HelmValuesSource.normalize("git"));
        assertEquals(HelmValuesSource.REPOSITORY, HelmValuesSource.normalize("REPO"));
        assertEquals(HelmValuesSource.YAML, HelmValuesSource.normalize("manual"));
        assertEquals(HelmValuesSource.YAML, HelmValuesSource.normalize("string"));
        assertEquals(HelmValuesSource.YAML, HelmValuesSource.normalize("file"));
        assertEquals(HelmValuesSource.YAML, HelmValuesSource.normalize("inline"));
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.normalize("other"));
        assertEquals("fallback", HelmValuesSource.normalize("nope", "fallback"));
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.normalize(null));
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.normalize("  "));
    }

    @Test
    void predicates() {
        assertTrue(HelmValuesSource.isNone("none"));
        assertTrue(HelmValuesSource.isNone("off"));
        assertTrue(HelmValuesSource.isRepository("repository"));
        assertTrue(HelmValuesSource.isYaml("yaml"));
        assertFalse(HelmValuesSource.isYaml("none"));
        assertFalse(HelmValuesSource.isRepository("yaml"));
    }
}
