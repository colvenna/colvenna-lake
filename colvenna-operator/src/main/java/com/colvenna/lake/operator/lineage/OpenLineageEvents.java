package com.colvenna.lake.operator.lineage;

import com.colvenna.lake.operator.lineage.LineageGraph.Column;
import com.colvenna.lake.operator.lineage.LineageGraph.Dataset;
import com.colvenna.lake.operator.lineage.LineageGraph.Run;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;

/**
 * OpenLineage 2-0-2 run events (facets as of OpenLineage 1.54.0) for the graph's runs: the job with
 * its type, the version it runs and its SQL; the run with what ran it; the datasets with their
 * columns and catalog.
 */
public final class OpenLineageEvents {
    private OpenLineageEvents() {}

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PRODUCER =
            "https://github.com/colvenna/colvenna-lake/tree/main/colvenna-operator";
    static final String RUN_EVENT =
            "https://openlineage.io/spec/2-0-2/OpenLineage.json#/$defs/RunEvent";
    static final String FACETS = "https://openlineage.io/spec/facets/";
    static final String COLVENNA_LAKE_FACET =
            "https://github.com/colvenna/colvenna-lake/blob/main/colvenna-operator/src/main/resources/"
                    + "openlineage/ColvennaLakeRunFacet.json#/$defs/ColvennaLakeRunFacet";

    /** A run's event of the given type (START, RUNNING, COMPLETE, FAIL) at {@code at}. */
    public static ObjectNode event(String namespace, Run run, String type, Instant at) {
        ObjectNode e = base(type, at);
        ObjectNode r = e.putObject("run");
        r.put("runId", run.id());
        ObjectNode runFacets = r.putObject("facets");
        if (run.engineVersion() != null) {
            facet(runFacets, "processing_engine", "1-1-1", "ProcessingEngineRunFacet")
                    .put("version", run.engineVersion())
                    .put("name", run.engine());
        }
        String flinkJobId = run.facts().get("flinkJobId");
        if (flinkJobId != null) {
            facet(runFacets, "externalQuery", "1-0-2", "ExternalQueryRunFacet")
                    .put("externalQueryId", flinkJobId)
                    .put("source", "flink");
        }
        ObjectNode colvenna = runFacets.putObject("colvennaLake");
        colvenna.put("_producer", PRODUCER).put("_schemaURL", COLVENNA_LAKE_FACET);
        run.facts().forEach(colvenna::put);

        ObjectNode jobFacets =
                (ObjectNode) job(e, namespace, run.job().name(), run.job().kind()).get("facets");
        facet(jobFacets, "sourceCodeLocation", "1-1-0", "SourceCodeLocationJobFacet")
                .put("type", "kubernetes")
                .put("url", run.job().location())
                .put("version", run.job().version());
        if (run.job().sql() != null) {
            facet(jobFacets, "sql", "1-1-0", "SQLJobFacet")
                    .put("query", run.job().sql())
                    .put("dialect", run.job().dialect());
        }
        datasets(e.putArray("inputs"), run.inputs());
        datasets(e.putArray("outputs"), run.outputs());
        return e;
    }

    /**
     * The end of a run known only by what was reported of it: its job is gone or runs another
     * version. A streaming job's end carries no datasets, so the catalog keeps the job's current
     * inputs and outputs.
     */
    public static ObjectNode end(
            String namespace,
            String job,
            String runId,
            LineageGraph.JobType kind,
            String type,
            Instant at) {
        ObjectNode e = base(type, at);
        e.putObject("run").put("runId", runId);
        job(e, namespace, job, kind);
        e.putArray("inputs");
        e.putArray("outputs");
        return e;
    }

    private static ObjectNode base(String type, Instant at) {
        ObjectNode e = JSON.createObjectNode();
        e.put("eventType", type);
        e.put("eventTime", at.toString());
        e.put("producer", PRODUCER);
        e.put("schemaURL", RUN_EVENT);
        return e;
    }

    /** The job, with its jobType facet (Marquez requires its jobType, the spec does not). */
    private static ObjectNode job(
            ObjectNode e, String namespace, String name, LineageGraph.JobType kind) {
        ObjectNode job = e.putObject("job");
        job.put("namespace", namespace);
        job.put("name", name);
        ObjectNode facets = job.putObject("facets");
        facet(facets, "jobType", "2-0-4", "JobTypeJobFacet")
                .put("processingType", kind.streaming() ? "STREAMING" : "BATCH")
                .put("integration", kind.integration())
                .put("jobType", kind.type());
        return job;
    }

    /** A facet under its key, with its schema: OpenLineage's {@code <version>/<name>.json}. */
    private static ObjectNode facet(ObjectNode facets, String key, String version, String name) {
        ObjectNode f = facets.putObject(key);
        f.put("_producer", PRODUCER);
        f.put("_schemaURL", FACETS + version + "/" + name + ".json#/$defs/" + name);
        return f;
    }

    private static void datasets(ArrayNode out, List<Dataset> datasets) {
        for (Dataset d : datasets) {
            ObjectNode n = out.addObject();
            n.put("namespace", d.namespace());
            n.put("name", d.name());
            ObjectNode facets = n.putObject("facets");
            ObjectNode type =
                    facet(facets, "datasetType", "1-0-1", "DatasetTypeDatasetFacet")
                            .put("datasetType", d.type());
            if (d.subType() != null) {
                type.put("subType", d.subType());
            }
            if (!d.columns().isEmpty()) {
                ArrayNode fields =
                        facet(facets, "schema", "1-2-0", "SchemaDatasetFacet").putArray("fields");
                int position = 1;
                for (Column c : d.columns()) {
                    ObjectNode f = fields.addObject().put("name", c.name()).put("type", c.type());
                    if (c.description() != null) {
                        f.put("description", c.description());
                    }
                    f.put("ordinal_position", position++);
                }
            }
            if (d.catalogUri() != null) {
                facet(facets, "catalog", "1-1-0", "CatalogDatasetFacet")
                        .put("framework", "paimon")
                        .put("type", "rest")
                        .put("name", "colvenna")
                        .put("metadataUri", d.catalogUri());
            }
        }
    }

    /** What tells one report of a run from the next: everything but its type and time. */
    static String fingerprint(String namespace, Run run) {
        return com.colvenna.lake.operator.flink.FlinkJobs.sha256(
                event(namespace, run, "RUNNING", Instant.EPOCH).toString());
    }
}
