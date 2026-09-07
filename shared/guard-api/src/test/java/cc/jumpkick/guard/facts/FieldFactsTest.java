// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldFactsTest {

    @Test
    void a_constant_field_carries_its_value_and_a_plain_field_carries_none() {
        ClassFacts c = Fixtures.hashing();
        FieldFacts algo = c.fields().get(0);
        FieldFacts calls = c.fields().get(1);
        assertThat(algo.name()).isEqualTo("ALGORITHM");
        assertThat(algo.desc()).isEqualTo("Ljava/lang/String;");
        assertThat(algo.constantValue()).isEqualTo("SHA-256");
        assertThat(algo.access() & Fixtures.ACC_FINAL).isNotZero();
        assertThat(calls.constantValue()).isNull();
        assertThat(calls.desc()).isEqualTo("I");
    }

    @Test
    void field_annotations_are_copied() {
        ArrayList<AnnotationFacts> anns = new ArrayList<>(List.of(Fixtures.nullMarked()));
        FieldFacts f = new FieldFacts("x", "I", 1, null, anns);
        anns.clear();
        assertThat(f.annotations()).containsExactly(Fixtures.nullMarked());
        assertThatThrownBy(() -> f.annotations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void a_field_reference_targets_the_binary_owner_and_name_without_its_descriptor() {
        FieldRef read = new FieldRef("a/b/Hashing", "calls", "I", 30, false, 1);
        FieldRef write = new FieldRef("a/b/Hashing", "calls", "I", 32, true, 3);
        assertThat(read.target()).isEqualTo("a.b.Hashing#calls");
        assertThat(write.target()).isEqualTo(read.target());
        assertThat(write.write()).isTrue();
        assertThat(write.count()).isEqualTo(3);
        assertThat(read.line()).isEqualTo(30);
    }

    @Test
    void equal_field_facts_compare_equal_and_a_different_constant_does_not() {
        FieldFacts a = new FieldFacts("K", "I", 25, "7", List.of());
        FieldFacts b = new FieldFacts("K", "I", 25, "7", List.of());
        FieldFacts c = new FieldFacts("K", "I", 25, "8", List.of());
        assertThat(a).isEqualTo(b).isNotEqualTo(c);
    }
}
