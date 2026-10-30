package com.colvenna.lake.jobs.sql;

import com.colvenna.lake.jobs.Catalogs;
import java.io.IOException;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogLoader;
import org.apache.paimon.catalog.Database;
import org.apache.paimon.catalog.DelegateCatalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.flink.FlinkCatalogFactory;
import org.apache.paimon.function.Function;
import org.apache.paimon.options.Options;
import org.apache.paimon.rest.exceptions.ForbiddenException;
import org.apache.paimon.table.Table;
import org.apache.paimon.view.View;

/**
 * Registers colvenna-catalog as catalog {@code colvenna} in a table environment, with the job's own
 * token and per-table data tokens. Registered in code, not in SQL, so the token never appears in
 * SQL text or plans.
 */
public final class ColvennaCatalog {
    static final String NAME = "colvenna";

    private ColvennaCatalog() {}

    /** Registers the catalog, makes it current, and returns the Paimon catalog behind it. */
    public static Catalog register(TableEnvironment tEnv) throws IOException {
        return register(tEnv, Options.fromMap(Catalogs.restOptions()));
    }

    static Catalog register(TableEnvironment tEnv, Options options) {
        options.set("disable-create-table-in-default-db", "true");
        Catalog paimon = new Resolving(FlinkCatalogFactory.createPaimonCatalog(options));
        tEnv.registerCatalog(NAME, FlinkCatalogFactory.createCatalog(NAME, paimon, options));
        tEnv.useCatalog(NAME);
        return paimon;
    }

    /**
     * Flink resolves {@code db.table} by first trying it as a table, then a view, of the current
     * database ({@code default}), and an unknown function name as a function there.
     * colvenna-catalog answers 403, not 404, for an object the identity may not read, which would
     * end the resolution there. A layered table never reads {@code default} (its upstream tables
     * belong to its namespace's sources and tables), so a refusal there means "not one of ours".
     */
    static final class Resolving extends DelegateCatalog {
        static final String CURRENT_DATABASE = "default";

        Resolving(Catalog wrapped) {
            super(wrapped);
        }

        /** Tasks load the catalog itself: only planning resolves names. */
        @Override
        public CatalogLoader catalogLoader() {
            return wrapped.catalogLoader();
        }

        @Override
        public Database getDatabase(String name) throws DatabaseNotExistException {
            try {
                return super.getDatabase(name);
            } catch (DatabaseNoPermissionException e) {
                throw new DatabaseNotExistException(name);
            }
        }

        @Override
        public Table getTable(Identifier identifier) throws TableNotExistException {
            try {
                return super.getTable(identifier);
            } catch (TableNoPermissionException e) {
                if (inCurrent(identifier)) {
                    throw new TableNotExistException(identifier);
                }
                throw e;
            }
        }

        @Override
        public View getView(Identifier identifier) throws ViewNotExistException {
            try {
                return super.getView(identifier);
            } catch (ViewNoPermissionException e) {
                if (inCurrent(identifier)) {
                    throw new ViewNotExistException(identifier);
                }
                throw e;
            }
        }

        @Override
        public Function getFunction(Identifier identifier) throws FunctionNotExistException {
            try {
                return super.getFunction(identifier);
            } catch (ForbiddenException e) {
                if (inCurrent(identifier)) {
                    throw new FunctionNotExistException(identifier);
                }
                throw e;
            }
        }

        private static boolean inCurrent(Identifier identifier) {
            return CURRENT_DATABASE.equals(identifier.getDatabaseName());
        }
    }
}
