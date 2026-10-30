package com.colvenna.lake.operator.source;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL replication slots of removed jobs. A slot the job still holds cannot be dropped, and a
 * slot nobody drops keeps WAL on the source forever, so the caller retries until the slot is
 * dropped or gone.
 */
final class ReplicationSlots {
    private ReplicationSlots() {}

    enum Outcome {
        DROPPED,
        ABSENT,
        /** A job still holds it; try again once the job is gone. */
        ACTIVE,
        FAILED
    }

    static Outcome drop(SourceProbe.Target target, String slot) {
        try (Connection c = PostgresProbe.connect(target, target.replication())) {
            try (PreparedStatement p =
                    c.prepareStatement(
                            "SELECT active FROM pg_replication_slots WHERE slot_name = ?")) {
                p.setString(1, slot);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) {
                        return Outcome.ABSENT;
                    }
                    if (r.getBoolean(1)) {
                        return Outcome.ACTIVE;
                    }
                }
            }
            try (PreparedStatement p = c.prepareStatement("SELECT pg_drop_replication_slot(?)")) {
                p.setString(1, slot);
                p.execute();
            }
            return Outcome.DROPPED;
        } catch (SQLException e) {
            return Outcome.FAILED;
        }
    }
}
