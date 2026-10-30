package com.colvenna.lake.operator.lineage;

import static com.colvenna.lake.operator.lineage.Fixtures.NAMING;
import static com.colvenna.lake.operator.lineage.Fixtures.NS;
import static org.assertj.core.api.Assertions.assertThat;

import com.colvenna.lake.operator.crd.LakeSource;
import com.colvenna.lake.operator.crd.LakeTable;
import com.colvenna.lake.operator.lineage.LineageGraph.Run;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The events are OpenLineage 2-0-2 run events, every facet valid against its own schema. */
class OpenLineageEventsTest {
    private static final JsonSchemaFactory SCHEMAS =
            JsonSchemaFactory.getInstance(
                    SpecVersion.VersionFlag.V202012,
                    b ->
                            b.schemaMappers(
                                    m ->
                                            m.mapPrefix(
                                                            "https://openlineage.io/spec/",
                                                            "classpath:openlineage/spec/")
                                                    .mapPrefix(
                                                            "https://github.com/colvenna/colvenna-lake/blob/main/colvenna-operator/src/main/resources/openlineage/",
                                                            "classpath:openlineage/")));
    private static final SchemaValidatorsConfig CONFIG =
            SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();

    /**
     * What the schema and every facet's own schema find wrong with an event, and what Marquez
     * refuses besides (a jobType facet without its jobType: 422).
     */
    static List<String> problems(JsonNode event) {
        List<String> out = new ArrayList<>();
        if (!event.path("job").path("facets").path("jobType").path("jobType").isTextual()) {
            out.add("Marquez: job.facets.jobType.jobType must not be null");
        }
        check(out, OpenLineageEvents.RUN_EVENT, event);
        List<JsonNode> facets = new ArrayList<>();
        event.path("run").path("facets").forEach(facets::add);
        event.path("job").path("facets").forEach(facets::add);
        for (String side : List.of("inputs", "outputs")) {
            event.path(side).forEach(d -> d.path("facets").forEach(facets::add));
        }
        for (JsonNode facet : facets) {
            check(out, facet.path("_schemaURL").asText(), facet);
        }
        return out;
    }

    private static void check(List<String> out, String schema, JsonNode node) {
        Set<ValidationMessage> errors =
                SCHEMAS.getSchema(SchemaLocation.of(schema), CONFIG).validate(node);
        errors.forEach(e -> out.add(schema + ": " + e.getMessage()));
    }

    private static List<Run> runs() {
        Fixtures.Catalog catalog = new Fixtures.Catalog();
        LakeSource src = Fixtures.source();
        LakeTable dwd = Fixtures.dwd("h-dwd");
        return LineageGraph.of(
                        new LineageGraph.Namespace(
                                NS,
                                List.of(src),
                                List.of(dwd),
                                List.of(Fixtures.backfill("bf", "Running", 2, "Failed")),
                                List.of(Fixtures.dashboard()),
                                List.of(
                                        Fixtures.cdc(src, "h-src", "j-src"),
                                        Fixtures.tableJob(dwd, "j-dwd", true))),
                        catalog::schema,
                        NAMING)
                .runs();
    }

    @Test
    void everyEventIsValidOpenLineage() {
        Instant at = Instant.parse("2026-10-17T03:00:00Z");
        List<Run> runs = runs();
        assertThat(runs).hasSize(5);
        for (Run run : runs) {
            for (String type : List.of("START", "RUNNING", "COMPLETE", "FAIL")) {
                assertThat(
                                problems(
                                        OpenLineageEvents.event(
                                                "colvenna-lake.colvenna-data", run, type, at)))
                        .as(run.job().name() + " " + type)
                        .isEmpty();
            }
        }
        assertThat(
                        problems(
                                OpenLineageEvents.end(
                                        "colvenna-lake.colvenna-data",
                                        "dwd-orders-table",
                                        LineageGraph.runId("x"),
                                        new LineageGraph.JobType(true, "FLINK", "JOB"),
                                        "COMPLETE",
                                        at)))
                .isEmpty();
    }

    @Test
    void theEventCarriesTheVersionTheJobIdAndTheCatalogsSchema() {
        Run dwd =
                runs().stream()
                        .filter(r -> r.job().name().equals("dwd-orders-table"))
                        .findFirst()
                        .orElseThrow();
        JsonNode e =
                OpenLineageEvents.event(
                        "colvenna-lake.colvenna-data",
                        dwd,
                        "START",
                        Instant.parse("2026-10-17T03:00:00Z"));
        assertThat(e.path("job").path("namespace").asText())
                .isEqualTo("colvenna-lake.colvenna-data");
        assertThat(e.path("job").path("facets").path("jobType").path("processingType").asText())
                .isEqualTo("STREAMING");
        // Marquez versions a job by its location (an https URL): the spec hash is in it.
        assertThat(e.path("job").path("facets").path("sourceCodeLocation").path("url").asText())
                .isEqualTo(
                        "https://kubernetes.default.svc/apis/flink.apache.org/v1beta1/namespaces/"
                                + "colvenna-data/flinkdeployments/dwd-orders-table?version=h-dwd");
        assertThat(e.path("job").path("facets").path("sql").path("dialect").asText())
                .isEqualTo("flink");
        assertThat(
                        e.path("run")
                                .path("facets")
                                .path("externalQuery")
                                .path("externalQueryId")
                                .asText())
                .isEqualTo("j-dwd");
        assertThat(e.path("run").path("facets").path("colvennaLake").path("specHash").asText())
                .isEqualTo("h-dwd");
        JsonNode orders = e.path("inputs").get(0);
        assertThat(orders.path("name").asText()).isEqualTo("colvenna.ods.orders");
        assertThat(orders.path("facets").path("schema").path("fields").get(0).path("type").asText())
                .isEqualTo("BIGINT NOT NULL");
        assertThat(orders.path("facets").path("catalog").path("framework").asText())
                .isEqualTo("paimon");
        // No S3 symlink: Marquez would show the table's location as another dataset.
        assertThat(orders.path("facets").has("symlinks")).isFalse();
    }
}
