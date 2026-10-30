package com.colvenna.lake.operator.lake;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.Decimal;
import org.apache.paimon.data.GenericRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.Timestamp;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataTypeRoot;
import org.apache.paimon.types.DecimalType;
import org.apache.paimon.types.RowType;

/**
 * Rows of the operator's own small tables (the quality store) as column to Java value: strings,
 * numbers, booleans, and instants for timestamps.
 */
final class Rows {
    private Rows() {}

    static Map<String, Object> toJava(InternalRow row, RowType type) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<DataField> fields = type.getFields();
        for (int i = 0; i < fields.size(); i++) {
            Object v = InternalRow.createFieldGetter(fields.get(i).type(), i).getFieldOrNull(row);
            if (v instanceof BinaryString s) {
                v = s.toString();
            } else if (v instanceof Timestamp t) {
                v = t.toInstant();
            } else if (v instanceof Decimal d) {
                v = d.toBigDecimal();
            }
            out.put(fields.get(i).name(), v);
        }
        return out;
    }

    static GenericRow toInternal(Map<String, Object> values, RowType type) {
        List<DataField> fields = type.getFields();
        GenericRow row = new GenericRow(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            Object v = values.get(fields.get(i).name());
            DataTypeRoot root = fields.get(i).type().getTypeRoot();
            if (v == null) {
                row.setField(i, null);
            } else if (v instanceof String s) {
                row.setField(i, BinaryString.fromString(s));
            } else if (v instanceof Instant t) {
                row.setField(i, Timestamp.fromInstant(t));
            } else if (v instanceof BigDecimal d) {
                DecimalType dt = (DecimalType) fields.get(i).type();
                row.setField(i, Decimal.fromBigDecimal(d, dt.getPrecision(), dt.getScale()));
            } else if (root == DataTypeRoot.BIGINT && v instanceof Number n) {
                row.setField(i, n.longValue());
            } else if (root == DataTypeRoot.DOUBLE && v instanceof Number n) {
                row.setField(i, n.doubleValue());
            } else {
                row.setField(i, v);
            }
        }
        return row;
    }
}
