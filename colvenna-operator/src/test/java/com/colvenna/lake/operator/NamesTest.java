package com.colvenna.lake.operator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void aNameThatFitsIsKept() {
        assertThat(Names.fit("abc", 3, '-')).isEqualTo("abc");
    }

    @Test
    void aLongerNameIsCutWithAHashOfTheWhole() {
        String cut = Names.fit("a".repeat(70), 63, '_');
        assertThat(cut).hasSize(63).startsWith("a".repeat(54) + "_");
        assertThat(cut.substring(55)).isEqualTo(Names.sha256("a".repeat(70)).substring(0, 8));
        // Two names that differ only past the cut get two names.
        assertThat(Names.fit("a".repeat(70) + "b", 63, '_'))
                .isNotEqualTo(Names.fit("a".repeat(70) + "c", 63, '_'));
    }

    @Test
    void theCutDropsTheSeparatorsItWouldEndIn() {
        String cut = Names.fit("a".repeat(52) + "__" + "b".repeat(20), 63, '_');
        assertThat(cut).doesNotContain("__").startsWith("a".repeat(52) + "_").hasSize(61);
        assertThat(Names.fit("a".repeat(53) + "-" + "b".repeat(20), 63, '-'))
                .startsWith("a".repeat(53) + "-")
                .hasSize(62);
    }
}
