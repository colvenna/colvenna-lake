package com.colvenna.lake.operator.flink;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FlinkRestTest {
    /** A JobManager answering {@code json} (null: not at all) for job j's checkpoints. */
    static FlinkRest answering(String json) {
        return new FlinkRest() {
            @Override
            protected Optional<JsonNode> get(String namespace, String deployment, String path) {
                assertThat(path).isEqualTo("/jobs/j/checkpoints");
                try {
                    return json == null
                            ? Optional.empty()
                            : Optional.of(new ObjectMapper().readTree(json));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    static Optional<Long> lastId(String json) {
        return answering(json).checkpoints("ns", "d", "j").map(FlinkRest.Checkpoints::lastId);
    }

    @Test
    void theLastCheckpointIdIsTheHighestTheJobHasUsed() {
        // Completed 41; 42 failed and 43 is in progress, both only in the history.
        assertThat(
                        lastId(
                                "{\"counts\":{\"completed\":5,\"failed\":1,\"in_progress\":1},"
                                        + "\"latest\":{\"completed\":{\"id\":41},\"savepoint\":"
                                        + "{\"id\":30},\"failed\":{\"id\":42},\"restored\":"
                                        + "{\"id\":12}},\"history\":[{\"id\":43,\"status\":"
                                        + "\"IN_PROGRESS\"},{\"id\":42,\"status\":\"FAILED\"},"
                                        + "{\"id\":41,\"status\":\"COMPLETED\"}]}"))
                .contains(43L);
        // Restored from savepoint 12, no checkpoint of its own yet: the trigger that failed
        // before the tasks ran took no id (#279).
        assertThat(
                        lastId(
                                "{\"counts\":{\"restored\":1,\"completed\":0,\"failed\":1},"
                                        + "\"latest\":{\"completed\":null,\"savepoint\":null,"
                                        + "\"failed\":null,\"restored\":{\"id\":12,"
                                        + "\"is_savepoint\":true}},\"history\":[]}"))
                .contains(12L);
        assertThat(
                        answering(
                                        "{\"counts\":{\"restored\":1,\"completed\":0},\"latest\":"
                                                + "{\"restored\":{\"id\":12,\"external_path\":"
                                                + "\"s3://b/sp-12\"}}}")
                                .checkpoints("ns", "d", "j"))
                .get()
                .satisfies(
                        c -> {
                            assertThat(c.completed()).isZero();
                            assertThat(c.restored()).isEqualTo("s3://b/sp-12");
                            assertThat(c.latestAt()).isNull();
                        });
        // A fresh start: its first checkpoint will be 1.
        assertThat(
                        lastId(
                                "{\"counts\":{\"completed\":0,\"failed\":0},\"latest\":"
                                        + "{\"completed\":null,\"restored\":null},\"history\":[]}"))
                .contains(0L);
        // No answer, or not the statistics: nothing known.
        assertThat(lastId(null)).isEmpty();
        assertThat(lastId("{\"errors\":[\"Job not found\"]}")).isEmpty();
    }
}
