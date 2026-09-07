// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MethodFactsTest {

    @Test
    void the_member_is_name_plus_descriptor() {
        assertThat(Fixtures.method("run", "()V").member()).isEqualTo("run()V");
        assertThat(Fixtures.method("<init>", "(I)V").member()).isEqualTo("<init>(I)V");
    }

    @Test
    void parameter_count_reads_the_descriptor_without_the_return_type() {
        assertThat(Fixtures.method("f", "()V").parameterCount()).isEqualTo(0);
        assertThat(Fixtures.method("f", "(I)V").parameterCount()).isEqualTo(1);
        assertThat(Fixtures.method("f", "(IJ)Ljava/lang/String;").parameterCount())
                .isEqualTo(2);
        assertThat(Fixtures.method("f", "(Ljava/lang/String;I)V").parameterCount())
                .isEqualTo(2);
    }

    @Test
    void arrays_of_any_dimension_and_of_objects_count_once() {
        assertThat(Fixtures.method("f", "([B)V").parameterCount()).isEqualTo(1);
        assertThat(Fixtures.method("f", "([[J[Ljava/lang/String;)V").parameterCount())
                .isEqualTo(2);
        assertThat(Fixtures.method("f", "(I[[Ljava/lang/String;J)V").parameterCount())
                .isEqualTo(3);
        assertThat(Fixtures.method("f", "([Ljava/util/List;[[[I)Ljava/lang/Object;")
                        .parameterCount())
                .isEqualTo(2);
    }

    @Test
    void branches_and_first_line_are_carried_as_given() {
        MethodFacts m = new MethodFacts("f", "()V", 1, List.of(), List.of(), List.of(), List.of(), 4, 17);
        assertThat(m.branches()).isEqualTo(4);
        assertThat(m.firstLine()).isEqualTo(17);
        assertThat(m.access()).isEqualTo(1);
    }

    @Test
    void parameter_annotations_keep_one_list_per_parameter_in_order() {
        List<List<AnnotationFacts>> perParam = List.of(List.of(), List.of(Fixtures.nullMarked()), List.of());
        MethodFacts m = new MethodFacts("f", "(III)V", 1, List.of(), perParam, List.of(), List.of(), 0, 0);
        assertThat(m.parameterAnnotations()).hasSize(3);
        assertThat(m.parameterAnnotations().get(1)).containsExactly(Fixtures.nullMarked());
        assertThat(m.parameterAnnotations().get(0)).isEmpty();
    }

    @Test
    void lists_are_copied_defensively() {
        ArrayList<CallSite> calls = new ArrayList<>();
        calls.add(new CallSite("x/Y", "g", "()V", 1, null, 1));
        MethodFacts m = new MethodFacts("f", "()V", 1, List.of(), List.of(), calls, List.of(), 0, 0);
        calls.clear();
        assertThat(m.calls()).hasSize(1);
        assertThatThrownBy(() -> m.calls().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
