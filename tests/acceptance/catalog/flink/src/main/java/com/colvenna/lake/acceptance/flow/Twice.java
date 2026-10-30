package com.colvenna.lake.acceptance.flow;

import org.apache.flink.table.functions.ScalarFunction;

/** The scalar function the Flink flow registers in the catalog: {@code twice(x) = 2 * x}. */
public final class Twice extends ScalarFunction {

    public Long eval(Long value) {
        return value == null ? null : 2 * value;
    }
}
