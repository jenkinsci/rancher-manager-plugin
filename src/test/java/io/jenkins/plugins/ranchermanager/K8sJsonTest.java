package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void missing_andPaths() throws Exception {
        assertTrue(K8sJson.missing(null));
        assertTrue(K8sJson.missing(MAPPER.nullNode()));
        JsonNode obj = MAPPER.readTree(
                "{\"metadata\":{\"name\":\"n\"},\"status\":{\"summary\":{\"state\":\"active\"},\"data\":[]}}");
        assertFalse(K8sJson.missing(obj));
        assertEquals("n", K8sJson.metadata(obj).path("name").asText());
        assertEquals("active", K8sJson.summary(obj).path("state").asText());
        assertEquals("default", K8sJson.namespaceOrDefault(null));
        assertEquals("apps", K8sJson.namespaceOrDefault(" apps "));
    }

    @Test
    void collectionItems_dataThenItems() throws Exception {
        JsonNode data = MAPPER.readTree("{\"data\":[{\"n\":1}]}");
        assertEquals(1, K8sJson.collectionItems(data).size());
        JsonNode items = MAPPER.readTree("{\"items\":[{\"n\":1},{\"n\":2}]}");
        assertEquals(2, K8sJson.collectionItems(items).size());
        assertEquals(0, K8sJson.collectionItems(null).size());
        assertEquals(0, K8sJson.collectionItems(MAPPER.readTree("{}")).size());
    }
}
