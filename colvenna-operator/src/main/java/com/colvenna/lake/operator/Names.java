package com.colvenna.lake.operator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Names the operator makes for what limits a name's length. */
public final class Names {
    private Names() {}

    /**
     * The name as it is when it has at most {@code max} characters; otherwise cut short (without
     * the separators it would end in) and ended with the separator and the first 8 hex digits of
     * the whole name's SHA-256, {@code max} characters at most: two names never share a cut one.
     */
    public static String fit(String name, int max, char separator) {
        if (name.length() <= max) {
            return name;
        }
        String hash = sha256(name).substring(0, 8);
        String kept = name.substring(0, max - 1 - hash.length());
        int end = kept.length();
        while (end > 0 && kept.charAt(end - 1) == separator) {
            end--;
        }
        return kept.substring(0, end) + separator + hash;
    }

    /** The SHA-256 of a text's UTF-8 bytes, in lower-case hex. */
    public static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
