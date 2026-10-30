package com.colvenna.lake.operator.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdmissionTest {

    private static SourceModel.Column col(String name, String type, boolean nullable) {
        return new SourceModel.Column(name, type, type, nullable);
    }

    /** A MySQL table that keeps the whole source contract. */
    private static SourceModel.Table mysql(String name, List<SourceModel.Column> extra) {
        List<SourceModel.Column> columns = new ArrayList<>();
        columns.add(col("id", "bigint", false));
        columns.add(col("version_no", "bigint", false));
        columns.addAll(extra);
        return new SourceModel.Table(
                name,
                columns,
                List.of("id"),
                List.of("(`version_no` > 0)"),
                List.of(
                        new SourceModel.Trigger(
                                "BEFORE",
                                "UPDATE",
                                "BEGIN IF NEW.version_no <= OLD.version_no THEN SIGNAL SQLSTATE"
                                        + " '45000'; END IF; END"),
                        new SourceModel.Trigger(
                                "BEFORE",
                                "UPDATE",
                                "BEGIN IF NEW.id <> OLD.id THEN SIGNAL SQLSTATE '45000'; END IF;"
                                        + " END"),
                        new SourceModel.Trigger(
                                "BEFORE",
                                "INSERT",
                                "BEGIN INSERT INTO guard.used_keys VALUES ('t', NEW.id); END")),
                true,
                null);
    }

    private static SourceModel.Table without(SourceModel.Table t, String event, String text) {
        return new SourceModel.Table(
                t.name(),
                t.columns(),
                t.primaryKey(),
                t.checkClauses(),
                t.triggers().stream()
                        .filter(x -> !(x.event().equals(event) && x.body().contains(text)))
                        .toList(),
                t.triggersVisible(),
                t.postgres());
    }

    @Test
    void aContractTableIsAdmitted() {
        assertThat(Admission.check("mysql", mysql("orders", List.of()), "version_no").admitted())
                .isTrue();
    }

    @Test
    void eachBrokenRuleRejectsWithItsOwnReason() {
        SourceModel.Table good = mysql("orders", List.of());
        assertThat(Admission.check("mysql", mysql("Orders", List.of()), "version_no").reason())
                .isEqualTo("UnsupportedName");
        assertThat(
                        Admission.check(
                                        "mysql",
                                        new SourceModel.Table(
                                                "t",
                                                good.columns(),
                                                List.of(),
                                                good.checkClauses(),
                                                good.triggers(),
                                                true,
                                                null),
                                        "version_no")
                                .reason())
                .isEqualTo("NoPrimaryKey");
        assertThat(Admission.check("mysql", good, "revision").reason())
                .isEqualTo("VersionColumnMissing");
        assertThat(
                        Admission.check(
                                        "mysql",
                                        new SourceModel.Table(
                                                "t",
                                                List.of(
                                                        col("id", "bigint", false),
                                                        col("version_no", "int", false)),
                                                List.of("id"),
                                                good.checkClauses(),
                                                good.triggers(),
                                                true,
                                                null),
                                        "version_no")
                                .reason())
                .isEqualTo("VersionColumnType");
        assertThat(
                        Admission.check(
                                        "mysql",
                                        new SourceModel.Table(
                                                "t",
                                                good.columns(),
                                                good.primaryKey(),
                                                List.of(),
                                                good.triggers(),
                                                true,
                                                null),
                                        "version_no")
                                .reason())
                .isEqualTo("VersionCheckMissing");
        assertThat(
                        Admission.check(
                                        "mysql",
                                        new SourceModel.Table(
                                                "t",
                                                good.columns(),
                                                good.primaryKey(),
                                                good.checkClauses(),
                                                List.of(),
                                                false,
                                                null),
                                        "version_no")
                                .reason())
                .isEqualTo("SourceGuardsNotVisible");
        assertThat(
                        Admission.check(
                                        "mysql",
                                        without(good, "UPDATE", "version_no"),
                                        "version_no")
                                .reason())
                .isEqualTo("VersionGuardMissing");
        assertThat(
                        Admission.check("mysql", without(good, "UPDATE", "NEW.id"), "version_no")
                                .reason())
                .isEqualTo("KeyGuardMissing");
        assertThat(
                        Admission.check("mysql", without(good, "INSERT", "used_keys"), "version_no")
                                .reason())
                .isEqualTo("KeyReservationMissing");
    }

    @Test
    void binaryColumnsAreRejectedBecausePaimonCdcCannotCarryThem() {
        for (String type : List.of("binary", "varbinary", "blob", "longblob", "bit")) {
            Admission.Verdict v =
                    Admission.check(
                            "mysql", mysql("t", List.of(col("b", type, false))), "version_no");
            assertThat(v.reason()).as(type).isEqualTo("UnsupportedBinaryColumn");
        }
        assertThat(
                        Admission.check(
                                        "mysql",
                                        mysql("t", List.of(col("g", "geometry", true))),
                                        "version_no")
                                .reason())
                .isEqualTo("UnsupportedType");
    }

    private static SourceModel.Table postgres(
            List<SourceModel.Column> columns,
            Map<String, Character> identity,
            List<String> notPublished) {
        return new SourceModel.Table(
                "accounts",
                columns,
                List.of("id"),
                List.of("CHECK ((version_no > 0))"),
                List.of(
                        new SourceModel.Trigger(
                                "BEFORE",
                                "UPDATE",
                                "BEGIN IF NEW.version_no <= OLD.version_no THEN RAISE EXCEPTION"
                                        + " 'x'; END IF; RETURN NEW; END"),
                        new SourceModel.Trigger(
                                "BEFORE", "INSERT", "BEGIN INSERT INTO m3_guard.used_keys ...")),
                true,
                new SourceModel.Postgres(
                        false, false, List.of("accounts"), identity, notPublished));
    }

    @Test
    void postgresNeedsFullReplicaIdentityPublicationAndATypeStableKey() {
        List<SourceModel.Column> columns =
                List.of(col("id", "int8", false), col("version_no", "int8", false));
        assertThat(
                        Admission.check(
                                        "postgres",
                                        postgres(columns, Map.of("accounts", 'f'), List.of()),
                                        "version_no")
                                .admitted())
                .isTrue();
        assertThat(
                        Admission.check(
                                        "postgres",
                                        postgres(columns, Map.of("accounts", 'd'), List.of()),
                                        "version_no")
                                .reason())
                .isEqualTo("ReplicaIdentityNotFull");
        assertThat(
                        Admission.check(
                                        "postgres",
                                        postgres(
                                                columns,
                                                Map.of("accounts", 'f'),
                                                List.of("accounts")),
                                        "version_no")
                                .reason())
                .isEqualTo("NotInPublication");
        // A table the CDC user may not SELECT: its key shows in the catalog, its columns do not.
        assertThat(
                        Admission.check(
                                        "postgres",
                                        postgres(List.of(), Map.of("accounts", 'f'), List.of()),
                                        "version_no")
                                .reason())
                .isEqualTo("NotReadable");
        assertThat(
                        Admission.check(
                                        "postgres",
                                        postgres(
                                                List.of(
                                                        col("id", "varchar", false),
                                                        col("version_no", "int8", false)),
                                                Map.of("accounts", 'f'),
                                                List.of()),
                                        "version_no")
                                .reason())
                .isEqualTo("UnsupportedKeyType");
        assertThat(
                        Admission.check(
                                        "postgres",
                                        postgres(
                                                List.of(
                                                        col("id", "int8", false),
                                                        col("version_no", "int8", false),
                                                        col("raw", "bytea", true)),
                                                Map.of("accounts", 'f'),
                                                List.of()),
                                        "version_no")
                                .reason())
                .isEqualTo("UnsupportedBinaryColumn");
    }

    @Test
    void checkClausesAreMatchedLoosely() {
        assertThat(Admission.positiveCheck("(`version_no` > 0)", "version_no")).isTrue();
        assertThat(Admission.positiveCheck("CHECK ((version_no > 0))", "version_no")).isTrue();
        assertThat(Admission.positiveCheck("(0 < version_no)", "version_no")).isTrue();
        assertThat(Admission.positiveCheck("(`version_no` >= 0)", "version_no")).isFalse();
        // Another column whose name ends in the version column's does not count.
        assertThat(Admission.positiveCheck("(`prev_version_no` > 0)", "version_no")).isFalse();
        assertThat(Admission.positiveCheck("(`version_no` > 0.5)", "version_no")).isFalse();
        assertThat(Admission.positiveCheck("(`version_no` > 01)", "version_no")).isFalse();
    }

    @Test
    void guardsNeedWholeIdentifiersAndARaise() {
        SourceModel.Table good = mysql("t", List.of());
        assertThat(Admission.hasGuard(good, "UPDATE", List.of("version_no"))).isTrue();
        SourceModel.Table lookalike =
                new SourceModel.Table(
                        "t",
                        good.columns(),
                        good.primaryKey(),
                        good.checkClauses(),
                        List.of(
                                new SourceModel.Trigger(
                                        "BEFORE",
                                        "UPDATE",
                                        "BEGIN SET NEW.version_no_copy = OLD.version_no_copy; --"
                                                + " signal END")),
                        true,
                        null);
        assertThat(Admission.hasGuard(lookalike, "UPDATE", List.of("version_no"))).isFalse();
    }

    @Test
    void onlyVerifiedTypesAreAdmitted() {
        for (String type : List.of("timestamp", "time", "json", "enum", "year")) {
            assertThat(
                            Admission.check(
                                            "mysql",
                                            mysql("t", List.of(col("c", type, true))),
                                            "version_no")
                                    .reason())
                    .as(type)
                    .isEqualTo("UnsupportedType");
        }
        assertThat(
                        Admission.check(
                                        "mysql",
                                        mysql("t", List.of(col("c", "tinyint", true))),
                                        "version_no")
                                .admitted())
                .isTrue();
    }
}
