// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FingerprintsTest {

    @Test
    void anonymous_class_ordinals_fold_to_a_bare_dollar() {
        assertThat(Fingerprints.normalise("a.b.Jsonl$3")).isEqualTo("a.b.Jsonl$");
        assertThat(Fingerprints.normalise("a.b.Jsonl$12#run()V")).isEqualTo("a.b.Jsonl$#run()V");
        assertThat(Fingerprints.normalise("a.b.Outer$1$2")).isEqualTo("a.b.Outer$$");
    }

    @Test
    void lambda_counters_fold_but_the_enclosing_method_name_stays() {
        assertThat(Fingerprints.normalise("a.B#lambda$run$7()V")).isEqualTo("a.B#lambda$run$()V");
        assertThat(Fingerprints.normalise("a.B#lambda$new$0(I)V")).isEqualTo("a.B#lambda$new$(I)V");
        assertThat(Fingerprints.normalise("a.B#lambda$static$12()V")).isEqualTo("a.B#lambda$static$()V");
    }

    @Test
    void named_nested_classes_and_descriptors_are_untouched() {
        assertThat(Fingerprints.normalise("a.b.Outer$Inner#f(I)V")).isEqualTo("a.b.Outer$Inner#f(I)V");
        assertThat(Fingerprints.normalise("a.B#f(Ljava/lang/String;I)Z")).isEqualTo("a.B#f(Ljava/lang/String;I)Z");
        assertThat(Fingerprints.normalise("a.B#run()V")).isEqualTo("a.B#run()V");
    }

    @Test
    void two_sites_that_differ_only_by_synthetic_ordinals_share_a_fingerprint() {
        String before =
                Fingerprints.normalise("a.B$1#lambda$run$3()V -> java.lang.String#replace(CC)Ljava/lang/String;");
        String after =
                Fingerprints.normalise("a.B$2#lambda$run$9()V -> java.lang.String#replace(CC)Ljava/lang/String;");
        assertThat(before).isEqualTo(after);
        assertThat(before).isEqualTo("a.B$#lambda$run$()V -> java.lang.String#replace(CC)Ljava/lang/String;");
    }

    @Test
    void a_change_of_target_changes_the_fingerprint() {
        assertThat(Fingerprints.normalise("a.B#f()V -> x.Y#g()V"))
                .isNotEqualTo(Fingerprints.normalise("a.B#f()V -> x.Y#h()V"));
    }
}
