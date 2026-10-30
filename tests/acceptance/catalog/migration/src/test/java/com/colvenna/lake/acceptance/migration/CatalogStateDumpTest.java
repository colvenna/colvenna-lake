package com.colvenna.lake.acceptance.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class CatalogStateDumpTest {

    @Test
    void theRowChecksumDoesNotDependOnTheScanOrder() {
        String[] rows = {"1\u0001a\u0001", "2\u0001b\u0001", "3\u0001c\u0001"};
        long forward = 0;
        long backward = 0;
        for (int i = 0; i < rows.length; i++) {
            forward += CatalogStateDump.fnv1a(rows[i]);
            backward += CatalogStateDump.fnv1a(rows[rows.length - 1 - i]);
        }
        assertEquals(forward, backward);
    }

    @Test
    void theRowChecksumSeesAChangedValue() {
        assertNotEquals(
                CatalogStateDump.fnv1a("1\u0001a\u0001"), CatalogStateDump.fnv1a("1\u0001b\u0001"));
        // The field separator keeps "1|23" and "12|3" apart.
        assertNotEquals(
                CatalogStateDump.fnv1a("1\u000123\u0001"),
                CatalogStateDump.fnv1a("12\u00013\u0001"));
    }
}
