package com.colvenna.lake.operator.kube;

import io.fabric8.kubernetes.api.model.HasMetadata;

/** Which of two declarations came first, when both claim the same target. */
public final class Creation {
    private Creation() {}

    /**
     * Whether {@code a} was created before {@code b}. Creation timestamps have one-second
     * resolution, so a tie goes to the smaller uid: exactly one of two declarations keeps the
     * target.
     */
    public static boolean before(HasMetadata a, HasMetadata b) {
        int byTime =
                a.getMetadata()
                        .getCreationTimestamp()
                        .compareTo(b.getMetadata().getCreationTimestamp());
        return byTime != 0
                ? byTime < 0
                : a.getMetadata().getUid().compareTo(b.getMetadata().getUid()) < 0;
    }
}
