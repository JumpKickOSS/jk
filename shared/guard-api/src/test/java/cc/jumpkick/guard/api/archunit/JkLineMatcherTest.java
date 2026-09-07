// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.archunit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JkLineMatcherTest {
    private final JkLineMatcher matcher = new JkLineMatcher();

    @Test
    void lines_that_differ_only_by_source_line_number_are_the_same_violation() {
        String a = "Method <a.B.f()> calls method <x.Y.g()> in (B.java:12)";
        String b = "Method <a.B.f()> calls method <x.Y.g()> in (B.java:340)";
        assertThat(matcher.matches(a, b)).isTrue();
        assertThat(JkArchUnit.normalise(a)).isEqualTo("Method <a.B.f()> calls method <x.Y.g()> in (B.java)");
    }

    @Test
    void anonymous_and_lambda_ordinals_are_folded_before_comparing() {
        String a = "Method <a.B$1.lambda$run$3()> calls method <x.Y.g()> in (B.java:12)";
        String b = "Method <a.B$2.lambda$run$8()> calls method <x.Y.g()> in (B.java:99)";
        assertThat(matcher.matches(a, b)).isTrue();
    }

    @Test
    void a_different_target_or_origin_is_a_different_violation() {
        String a = "Method <a.B.f()> calls method <x.Y.g()> in (B.java:12)";
        assertThat(matcher.matches(a, "Method <a.B.f()> calls method <x.Y.h()> in (B.java:12)"))
                .isFalse();
        assertThat(matcher.matches(a, "Method <a.C.f()> calls method <x.Y.g()> in (C.java:12)"))
                .isFalse();
    }

    @Test
    void a_line_without_a_location_compares_verbatim() {
        assertThat(matcher.matches("Class <a.B> is public", "Class <a.B> is public"))
                .isTrue();
        assertThat(matcher.matches("Class <a.B> is public", "Class <a.B> is final"))
                .isFalse();
        assertThat(JkArchUnit.normalise("no location (x:1) here"))
                .as("only `:digits)` folds")
                .isEqualTo("no location (x) here");
    }
}
