package com.colvenna.lake.operator.catalog;

import com.colvenna.lake.operator.table.TableJobs;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A declaration's publication: table-level READ for its readers, on its governed tables (LLD
 * "治理与个人数据删除"). The grants carry the declaration's mark as their approval, so they are told from
 * grants the readers got elsewhere, which are not touched.
 */
public final class Publication {
    private final CatalogClient catalog;

    public Publication(CatalogClient catalog) {
        this.catalog = catalog;
    }

    /** The approval a declaration's grants carry: "publish:" and the declaration's owner mark. */
    public static String mark(String owner) {
        return "publish:" + owner;
    }

    /** What a sync did: the readers now published to, and those the catalog does not know. */
    record Result(List<String> published, List<String> missing) {}

    /** The declaration's Published condition. */
    public record Condition(boolean status, String reason, String message) {
        public static Condition withheld(String reason, String message) {
            return new Condition(false, reason, message);
        }
    }

    /** The readers published to after a publication, and the condition it leaves. */
    public record Outcome(List<String> published, Condition condition) {}

    /**
     * Publishes {@code tables} to {@code readers}, or nothing when {@code withheld} says why not,
     * and revokes what {@code previous} readers no longer get. When the catalog fails, the readers
     * stay as they were. A reader that reads the tables through a grant the declaration does not
     * manage (on their database, or the catalog) is named in the condition: withholding does not
     * stop it.
     */
    public Outcome publish(
            String mark,
            String purpose,
            Set<String> readers,
            List<String> tables,
            Collection<String> previous,
            Condition withheld,
            Instant now) {
        try {
            Result result =
                    sync(
                            mark,
                            purpose,
                            withheld == null ? readers : Set.of(),
                            withheld == null ? tables : List.of(),
                            new TreeSet<>(previous),
                            now);
            List<String> wider = wider(mark, readers, tables, now);
            String also =
                    wider.isEmpty()
                            ? ""
                            : "; readable all the same through grants no declaration manages: "
                                    + wider;
            Condition condition;
            if (readers.isEmpty()) {
                condition = Condition.withheld("NoReaders", "no readers declared");
            } else if (withheld != null) {
                condition = Condition.withheld(withheld.reason(), withheld.message() + also);
            } else if (!result.missing().isEmpty()) {
                condition =
                        Condition.withheld(
                                "ReaderMissing",
                                "colvenna-catalog has no principal " + result.missing());
            } else {
                condition =
                        new Condition(
                                true,
                                "Published",
                                "READ on " + tables + " for " + result.published() + also);
            }
            return new Outcome(result.published(), condition);
        } catch (CatalogException e) {
            return new Outcome(
                    List.copyOf(previous),
                    Condition.withheld("CatalogUnavailable", e.getMessage()));
        }
    }

    /**
     * The readers' grants beyond the declaration's that read the tables' databases (every level
     * includes READ), as "reader (scope database)".
     */
    private List<String> wider(String mark, Set<String> readers, List<String> tables, Instant now) {
        List<String> out = new ArrayList<>();
        for (String reader : readers) {
            if (catalog.principal(reader).isEmpty()) {
                continue;
            }
            for (Grant g : catalog.grants(reader)) {
                if (mark.equals(g.approval())) {
                    continue;
                }
                for (String table : tables) {
                    if (g.readsDatabase(TableJobs.split(table)[0], now)) {
                        out.add(
                                reader
                                        + " ("
                                        + g.scope()
                                        + (g.database() == null ? "" : " " + g.database())
                                        + ")");
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Revokes what the declaration published, when it goes. */
    public void withdraw(String mark, Collection<String> previous, Instant now) {
        if (!previous.isEmpty()) {
            sync(mark, "", Set.of(), List.of(), new TreeSet<>(previous), now);
        }
    }

    /**
     * Makes the marked grants exactly READ on {@code tables} (database.table) for {@code readers};
     * the marked grants of {@code previous} readers no longer wanted are revoked.
     */
    Result sync(
            String mark,
            String purpose,
            Set<String> readers,
            List<String> tables,
            Set<String> previous,
            Instant now) {
        Set<String> everyone = new TreeSet<>(readers);
        everyone.addAll(previous);
        List<String> published = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String reader : everyone) {
            boolean wanted = readers.contains(reader) && !tables.isEmpty();
            if (catalog.principal(reader).isEmpty()) {
                if (wanted) {
                    missing.add(reader);
                }
                continue;
            }
            List<Grant> held =
                    catalog.grants(reader).stream().filter(g -> mark.equals(g.approval())).toList();
            List<Grant> want = new ArrayList<>();
            if (wanted) {
                for (String table : tables) {
                    String[] parts = TableJobs.split(table);
                    want.add(Grant.table(reader, parts[0], parts[1], "READ", mark));
                }
            }
            for (Grant w : want) {
                boolean kept = false;
                for (Grant h : held) {
                    if (h.sameAccess(w)) {
                        if (h.expiresAt().isAfter(now.plus(CatalogIdentities.GRANT_RENEW_BEFORE))) {
                            kept = true;
                        } else {
                            catalog.revokeGrant(h.id());
                        }
                    }
                }
                if (!kept) {
                    catalog.grant(
                            new Grant(
                                    null,
                                    reader,
                                    "TABLE",
                                    w.database(),
                                    w.object(),
                                    "READ",
                                    "ALLOW",
                                    purpose,
                                    "colvenna-operator",
                                    mark,
                                    now.plus(CatalogIdentities.GRANT_TTL)));
                }
            }
            for (Grant h : held) {
                if (want.stream().noneMatch(h::sameAccess)) {
                    catalog.revokeGrant(h.id());
                }
            }
            if (wanted) {
                published.add(reader);
            }
        }
        return new Result(published, missing);
    }
}
