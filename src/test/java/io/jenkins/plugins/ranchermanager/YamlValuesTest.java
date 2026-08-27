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
}
