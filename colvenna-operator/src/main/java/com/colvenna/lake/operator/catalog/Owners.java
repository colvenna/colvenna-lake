package com.colvenna.lake.operator.catalog;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * The {@code colvenna-lake.owner} option a database or table carries: which declaration it belongs
 * to, so no other declaration takes it over and a layered table reads only its namespace's data.
 */
public final class Owners {
    public static final String OPTION = "colvenna-lake.owner";

    private Owners() {}

    /** A LakeSource's target database. */
    public static String source(HasMetadata source) {
        return "LakeSource/" + path(source);
    }

    /** A LakeTable's target table. */
    public static String table(HasMetadata table) {
        return "LakeTable/" + path(table);
    }

    /** A database of a namespace's layered tables. */
    public static String tables(String namespace) {
        return "LakeTables/" + namespace;
    }

    public static boolean isSourceOf(String owner, String namespace) {
        return owner != null && owner.startsWith("LakeSource/" + namespace + "/");
    }

    public static boolean isTableOf(String owner, String namespace) {
        return owner != null && owner.startsWith("LakeTable/" + namespace + "/");
    }

    private static String path(HasMetadata owner) {
        return owner.getMetadata().getNamespace()
                + "/"
                + owner.getMetadata().getName()
                + "/"
                + owner.getMetadata().getUid();
    }
}
