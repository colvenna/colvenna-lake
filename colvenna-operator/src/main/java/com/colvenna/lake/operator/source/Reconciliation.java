package com.colvenna.lake.operator.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Whether a target table holds the source's rows, judged by key and version while the source keeps
 * changing and the target trails it by a checkpoint or two.
 *
 * <ol>
 *   <li>Read the target (T1), then the source (S). Keys where they agree are consistent; the others
 *       are candidates.
 *   <li>Once the target has committed snapshots well after the source read, read it again (T2). A
 *       working job has applied every change up to the source read by then, so each candidate must
 *       have caught up: present in T2 with S's version or a later one, or absent from T2 when S has
 *       no row (keys are never reused, so a deleted key stays deleted).
 * </ol>
 *
 * A target ahead of the source (a version the source never had) is never consistent. Anything else
 * is a missing, stale or extra row, and the check starts over.
 */
public final class Reconciliation {
    private Reconciliation() {}

    /**
     * The judgement. {@code deletedAtSource} are the unexplained keys the source no longer has and
     * the target still does after catching up: their deletes never reached the target. Kept out of
     * status (keys can be personal data).
     */
    public record Result(
            long sourceRows,
            long targetRows,
            int unexplained,
            List<String> sample,
            List<String> deletedAtSource,
            List<String> unexplainedKeys) {
        /** A result without the unexplained keys themselves (none, or not needed). */
        public Result(
                long sourceRows,
                long targetRows,
                int unexplained,
                List<String> sample,
                List<String> deletedAtSource) {
            this(sourceRows, targetRows, unexplained, sample, deletedAtSource, List.of());
        }

        public boolean matched() {
            return unexplained == 0;
        }
    }

    /** The keys where the target, read first, and the source, read after it, differ. */
    public static List<String> candidates(Map<String, Long> target, Map<String, Long> source) {
        List<String> out = new ArrayList<>();
        source.forEach(
                (key, version) -> {
                    if (!version.equals(target.get(key))) {
                        out.add(key);
                    }
                });
        for (String key : target.keySet()) {
            if (!source.containsKey(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /** Judges the candidates against the target read again after it caught up past the source. */
    public static Result judge(
            Map<String, Long> first,
            Map<String, Long> source,
            List<String> candidates,
            Map<String, Long> later) {
        int unexplained = 0;
        List<String> sample = new ArrayList<>();
        List<String> deletedAtSource = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (String key : candidates) {
            Long t1 = first.get(key);
            Long s = source.get(key);
            Long t2 = later.get(key);
            boolean consistent;
            if (t1 != null && s != null && t1 > s) {
                consistent = false;
            } else if (s == null) {
                consistent = t2 == null;
            } else {
                consistent = t2 != null && t2 >= s;
            }
            if (!consistent) {
                unexplained++;
                keys.add(key);
                if (s == null) {
                    deletedAtSource.add(key);
                }
                if (sample.size() < 5) {
                    sample.add(
                            describe(key)
                                    + " target="
                                    + text(t1)
                                    + " source="
                                    + text(s)
                                    + " target-later="
                                    + text(t2));
                }
            }
        }
        return new Result(source.size(), later.size(), unexplained, sample, deletedAtSource, keys);
    }

    private static String text(Long version) {
        return version == null ? "absent" : version.toString();
    }

    /**
     * A key in a status message, hashed: keys can be personal data, and anyone who may read the
     * source's status would see them.
     */
    public static String describe(String key) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(key.getBytes(StandardCharsets.UTF_8));
            return "key#" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
