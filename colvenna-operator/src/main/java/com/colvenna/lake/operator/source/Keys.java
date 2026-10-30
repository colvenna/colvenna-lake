package com.colvenna.lake.operator.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A primary key's text form, the same whether read from the source over JDBC or from Paimon: each
 * component's string value, length-prefixed so no separator can collide.
 */
public final class Keys {
    private Keys() {}

    public static String encode(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null) {
                out.append("-1:");
            } else {
                out.append(value.length()).append(':').append(value);
            }
            out.append('|');
        }
        return out.toString();
    }

    public static List<String> decode(String encoded) {
        List<String> values = new ArrayList<>();
        int i = 0;
        while (i < encoded.length()) {
            int colon = encoded.indexOf(':', i);
            int length = Integer.parseInt(encoded.substring(i, colon));
            if (length < 0) {
                values.add(null);
                i = colon + 2;
            } else {
                values.add(encoded.substring(colon + 1, colon + 1 + length));
                i = colon + 1 + length + 1;
            }
        }
        return values;
    }
}
