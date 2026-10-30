package com.colvenna.lake.operator.lineage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What was reported, in a ConfigMap of the operator's namespace: one key per managed namespace, its
 * runs by id. Only the leader writes it.
 */
public final class ConfigMapLineageStore implements LineagePublisher.Store {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigMapLineageStore.class);
    // Fields a later operator added or dropped do not make the record unreadable.
    private static final ObjectMapper JSON =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<Map<String, LineagePublisher.Reported>> RUNS =
            new TypeReference<>() {};

    private final KubernetesClient kube;
    private final String namespace;
    private final String name;

    public ConfigMapLineageStore(KubernetesClient kube, String namespace, String name) {
        this.kube = kube;
        this.namespace = namespace;
        this.name = name;
    }

    @Override
    public Map<String, LineagePublisher.Reported> load(String ns) {
        ConfigMap cm = kube.configMaps().inNamespace(namespace).withName(name).get();
        String runs = cm == null || cm.getData() == null ? null : cm.getData().get(ns);
        if (runs == null) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, LineagePublisher.Reported> out = JSON.readValue(runs, RUNS);
            // A run recorded without what ending it needs is forgotten: it is reported again.
            out.values().removeIf(r -> r == null || r.job() == null || r.kind() == null);
            return new LinkedHashMap<>(out);
        } catch (JsonProcessingException e) {
            // An unreadable record must not stop the reports: they start over (each current run
            // is started again under its own id); the runs it held are not ended.
            LOG.warn("{} has unreadable runs for {}; starting over: {}", name, ns, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @Override
    public void save(String ns, Map<String, LineagePublisher.Reported> runs) {
        String data;
        try {
            data = JSON.writeValueAsString(runs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        var resource = kube.configMaps().inNamespace(namespace).withName(name);
        if (resource.get() == null) {
            resource.create(
                    new ConfigMapBuilder()
                            .withNewMetadata()
                            .withName(name)
                            .withNamespace(namespace)
                            .endMetadata()
                            .addToData(ns, data)
                            .build());
            return;
        }
        // This namespace's key alone: the others stay as they are.
        resource.edit(
                cm -> {
                    if (cm.getData() == null) {
                        cm.setData(new LinkedHashMap<>());
                    }
                    cm.getData().put(ns, data);
                    return cm;
                });
    }
}
