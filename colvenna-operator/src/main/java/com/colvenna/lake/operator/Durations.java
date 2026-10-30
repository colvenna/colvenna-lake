package com.colvenna.lake.operator;

/**
 * The durations declarations carry: a number and a unit, as {@code 30s}, {@code 5m}, {@code 2h}.
 */
public final class Durations {
    private Durations() {}

    /** {@code 30s}, {@code 5m}, {@code 2h} or {@code 30d} in milliseconds; the fallback if null. */
    public static long millis(String duration, String fallback) {
        String d = duration == null ? fallback : duration;
        long n = Long.parseLong(d.substring(0, d.length() - 1));
        return switch (d.charAt(d.length() - 1)) {
            case 's' -> n * 1000;
            case 'm' -> n * 60_000;
            case 'h' -> n * 3_600_000;
            case 'd' -> n * 86_400_000;
            default -> throw new IllegalArgumentException("Not a duration: " + d);
        };
    }
}
