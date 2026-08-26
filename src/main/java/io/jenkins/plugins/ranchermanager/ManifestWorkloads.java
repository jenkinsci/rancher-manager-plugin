package io.jenkins.plugins.ranchermanager;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Workloads declared in applied multi-doc YAML (client-side parse, never mutates YAML).
 * Kinds: Deployment, StatefulSet, DaemonSet, Job.
 */
final class ManifestWorkloads {

    static final String DEFAULT_NAMESPACE = "default";

    enum Kind {
        DEPLOYMENT("Deployment", "apps.deployments"),
        STATEFULSET("StatefulSet", "apps.statefulsets"),
        DAEMONSET("DaemonSet", "apps.daemonsets"),
        JOB("Job", "batch.jobs");

        private final String kind;
        private final String steveType;

        Kind(String kind, String steveType) {
            this.kind = kind;
            this.steveType = steveType;
        }

        String kind() {
            return kind;
        }

        String steveType() {
            return steveType;
        }

        static Kind of(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String k = raw.trim();
            for (Kind value : values()) {
                if (value.kind.equalsIgnoreCase(k)) {
                    return value;
                }
            }
            return null;
        }
    }

    record Workload(Kind kind, String namespace, String name) {
        Workload {
            Objects.requireNonNull(kind, "kind");
            namespace = namespace == null || namespace.isBlank() ? DEFAULT_NAMESPACE : namespace.trim();
            name = name == null ? "" : name.trim();
        }

        String display() {
            return kind.kind() + " " + namespace + "/" + name;
        }

        String stevePath() {
            return kind.steveType() + "/" + namespace + "/" + name;
        }
    }

    private ManifestWorkloads() {}

    /**
     * Parse multi-doc Kubernetes YAML; skip non-workload kinds and docs without a name.
     */
    static List<Workload> parse(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            return List.of();
        }
        final Iterable<Object> docs;
        try {
            LoaderOptions options = new LoaderOptions();
            Yaml yaml = new Yaml(new SafeConstructor(options));
            docs = yaml.loadAll(yamlContent);
        } catch (YAMLException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IllegalArgumentException(
                    "Cannot parse manifest YAML for readiness wait: "
                            + msg.replaceAll("\\s+", " ").trim());
        }
        Set<Workload> unique = new LinkedHashSet<>();
        for (Object doc : docs) {
            Workload workload = fromDoc(doc);
            if (workload != null && !workload.name().isBlank()) {
                unique.add(workload);
            }
        }
        return List.copyOf(unique);
    }

    static Set<String> namespaces(List<Workload> workloads) {
        if (workloads == null || workloads.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Workload w : workloads) {
            out.add(w.namespace());
        }
        return Collections.unmodifiableSet(out);
    }

    private static Workload fromDoc(Object doc) {
        if (!(doc instanceof Map<?, ?> map)) {
            return null;
        }
        Kind kind = Kind.of(stringValue(map.get("kind")));
        if (kind == null) {
            return null;
        }
        Object metaObj = map.get("metadata");
        if (!(metaObj instanceof Map<?, ?> meta)) {
            return null;
        }
        String name = stringValue(meta.get("name"));
        String namespace = stringValue(meta.get("namespace"));
        if (name == null || name.isBlank()) {
            return null;
        }
        return new Workload(kind, namespace, name);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value).trim();
    }
}
