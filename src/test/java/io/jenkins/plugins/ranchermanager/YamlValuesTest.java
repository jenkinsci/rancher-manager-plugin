package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlValuesTest {

    @Test
    void parseToMap_readsMapping() {
        Map<String, Object> map = YamlValues.parseToMap("replicaCount: 2\nimage:\n  tag: alpine\n");
        assertEquals(2, map.get("replicaCount"));
        assertTrue(map.get("image") instanceof Map);
        assertThrows(UnsupportedOperationException.class, () -> map.put("x", 1));
    }

    @Test
    void parseToMap_rejectsBlankAndNonMapping() {
        assertThrows(IllegalArgumentException.class, () -> YamlValues.parseToMap(null));
        assertThrows(IllegalArgumentException.class, () -> YamlValues.parseToMap("  "));
        IllegalArgumentException list = assertThrows(
                IllegalArgumentException.class, () -> YamlValues.parseToMap("- a\n- b\n"));
        assertTrue(list.getMessage().contains("mapping"));
        assertThrows(IllegalArgumentException.class, () -> YamlValues.parseToMap("just-a-scalar"));
    }

    @Test
    void parseToMap_rejectsInvalidYaml() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> YamlValues.parseToMap("foo: [unterminated"));
        assertTrue(ex.getMessage().startsWith("Invalid values YAML:"));
    }

    @Test
    void parseToMap_skipsNullKeys() {
        Map<String, Object> map = YamlValues.parseToMap("a: 1\n~: ignored\nb: 2\n");
        assertEquals(1, map.get("a"));
        assertEquals(2, map.get("b"));
    }

    @Test
    void parseToMap_commentOnlyIsEmpty() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> YamlValues.parseToMap("# comment only\n"));
        assertTrue(ex.getMessage().contains("empty"));
    }

    @Test
    void toJsonNode_roundTrip() {
        JsonNode node = YamlValues.toJsonNode("replicaCount: 1\n");
        assertEquals(1, node.path("replicaCount").asInt());
    }

    @Test
    void merge_overlayWinsNestedKey_keepsBaseSiblings() {
        JsonNode base = YamlValues.toJsonNode(
                "replicaCount: 2\nimage:\n  repository: nginx\n  tag: old\n");
        JsonNode overlay = YamlValues.toJsonNode("image:\n  tag: new\n");
        JsonNode merged = YamlValues.merge(base, overlay);
        assertEquals(2, merged.path("replicaCount").asInt());
        assertEquals("nginx", merged.path("image").path("repository").asText());
        assertEquals("new", merged.path("image").path("tag").asText());
    }

    @Test
    void merge_emptyOverlay_returnsBase() {
        JsonNode base = YamlValues.toJsonNode("replicaCount: 1\n");
        assertEquals(base, YamlValues.merge(base, null));
        assertEquals(1, YamlValues.merge(base, null).path("replicaCount").asInt());
    }

    @Test
    void merge_nullBase_returnsOverlay() {
        JsonNode overlay = YamlValues.toJsonNode("image:\n  tag: v1\n");
        JsonNode merged = YamlValues.merge(null, overlay);
        assertEquals("v1", merged.path("image").path("tag").asText());
    }

    @Test
    void merge_listReplacesEntirely() {
        JsonNode base = YamlValues.toJsonNode("args:\n  - a\n  - b\n");
        JsonNode overlay = YamlValues.toJsonNode("args:\n  - c\n");
        JsonNode merged = YamlValues.merge(base, overlay);
        assertEquals(1, merged.path("args").size());
        assertEquals("c", merged.path("args").get(0).asText());
    }
}
