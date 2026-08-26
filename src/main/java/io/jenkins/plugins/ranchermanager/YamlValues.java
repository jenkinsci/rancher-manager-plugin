package io.jenkins.plugins.ranchermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parse Helm values YAML into a JSON-serializable map. Never log the input. */
final class YamlValues {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private YamlValues() {
    }

    static Map<String, Object> parseToMap(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new IllegalArgumentException("Values YAML is empty.");
        }
        final Object loaded;
        try {
            LoaderOptions options = new LoaderOptions();
            Yaml yaml = new Yaml(new SafeConstructor(options));
            loaded = yaml.load(yamlContent);
        } catch (YAMLException e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = e.getClass().getSimpleName();
            }
            throw new IllegalArgumentException("Invalid values YAML: " + msg.replaceAll("\\s+", " ").trim());
        }
        if (loaded == null) {
            throw new IllegalArgumentException("Values YAML is empty.");
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Values YAML must be a mapping (key: value).");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return Collections.unmodifiableMap(out);
    }

    static JsonNode toJsonNode(String yamlContent) {
        return MAPPER.valueToTree(parseToMap(yamlContent));
    }
}
