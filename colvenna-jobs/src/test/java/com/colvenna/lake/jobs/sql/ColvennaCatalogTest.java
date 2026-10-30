package com.colvenna.lake.jobs.sql;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.CatalogLoader;
import org.apache.paimon.catalog.DelegateCatalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.options.Options;
import org.apache.paimon.table.Table;
import org.apache.paimon.view.View;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ColvennaCatalogTest {
    @TempDir Path warehouse;

    /** colvenna-catalog's answer for every table this identity has no grant on. */
    static final class Refusing extends DelegateCatalog {
        Refusing(Catalog wrapped) {
            super(wrapped);
        }

        @Override
        public CatalogLoader catalogLoader() {
            return wrapped.catalogLoader();
        }

        @Override
        public Table getTable(Identifier identifier) {
            throw new TableNoPermissionException(identifier);
        }

        @Override
        public View getView(Identifier identifier) {
            throw new ViewNoPermissionException(identifier);
        }
    }

    @Test
    void aRefusalInTheCurrentDatabaseIsATableThatIsNotThere() throws Exception {
        Options options = new Options();
        options.set("warehouse", warehouse.toUri().toString());
        try (Catalog fs = CatalogFactory.createCatalog(CatalogContext.create(options))) {
            Catalog catalog = new ColvennaCatalog.Resolving(new Refusing(fs));
            // Flink trying "ods.orders" as table "ods" of the current database: keep resolving.
            assertThrows(
                    Catalog.TableNotExistException.class,
                    () -> catalog.getTable(Identifier.create("default", "ods")));
            // The table itself: still a refusal, which validation reports as undeclared.
            assertThrows(
                    Catalog.ViewNotExistException.class,
                    () -> catalog.getView(Identifier.create("default", "ods")));
            assertThrows(
                    Catalog.TableNoPermissionException.class,
                    () -> catalog.getTable(Identifier.create("ods", "orders")));
            assertThrows(
                    Catalog.ViewNoPermissionException.class,
                    () -> catalog.getView(Identifier.create("ods", "orders")));
        }
    }
}
