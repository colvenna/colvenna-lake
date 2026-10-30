package com.colvenna.lake.operator.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A Kubernetes-style condition. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatusCondition {
    public String type;
    public String status;
    public String reason;
    public String message;
    public String lastTransitionTime;
    public Long observedGeneration;

    /**
     * Sets a condition, keeping its transition time when the status did not change.
     *
     * @return whether the status changed
     */
    public static boolean set(
            List<StatusCondition> conditions,
            String type,
            boolean status,
            String reason,
            String message,
            long generation,
            Instant now) {
        String value = status ? "True" : "False";
        for (StatusCondition c : conditions) {
            if (c.type.equals(type)) {
                boolean changed = !value.equals(c.status);
                if (changed) {
                    c.lastTransitionTime = now.toString();
                }
                c.status = value;
                c.reason = reason;
                c.message = message;
                c.observedGeneration = generation;
                return changed;
            }
        }
        StatusCondition c = new StatusCondition();
        c.type = type;
        c.status = value;
        c.reason = reason;
        c.message = message;
        c.lastTransitionTime = now.toString();
        c.observedGeneration = generation;
        conditions.add(c);
        return true;
    }

    public static boolean isTrue(List<StatusCondition> conditions, String type) {
        return conditions.stream()
                .anyMatch(c -> c.type.equals(type) && Objects.equals(c.status, "True"));
    }

    /** The condition's message, or null. */
    public static String message(List<StatusCondition> conditions, String type) {
        return conditions.stream()
                .filter(c -> c.type.equals(type))
                .map(c -> c.message)
                .findFirst()
                .orElse(null);
    }
}
