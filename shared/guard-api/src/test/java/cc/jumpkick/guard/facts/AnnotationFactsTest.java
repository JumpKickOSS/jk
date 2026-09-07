// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnnotationFactsTest {

    @Test
    void the_type_name_is_the_descriptor_in_binary_form() {
        assertThat(Fixtures.tag("slow").typeName()).isEqualTo("org.junit.jupiter.api.Tag");
        assertThat(Fixtures.nullMarked().typeName()).isEqualTo("org.jspecify.annotations.NullMarked");
        assertThat(Fixtures.nullMarked().runtimeVisible()).as("CLASS retention").isFalse();
        assertThat(Fixtures.tag("x").runtimeVisible()).as("RUNTIME retention").isTrue();
    }

    @Test
    void value_is_the_value_attribute_or_empty() {
        assertThat(Fixtures.tag("slow").value()).containsExactly("slow");
        assertThat(Fixtures.test().value()).isEmpty();
        AnnotationFacts multi = new AnnotationFacts(
                "Lorg/junit/jupiter/api/Tags;", true, Map.of("value", List.of("a", "b"), "other", List.of("c")));
        assertThat(multi.value()).containsExactly("a", "b");
        assertThat(multi.values().get("other")).containsExactly("c");
    }

    @Test
    void values_are_copied_and_immutable() {
        Map<String, List<String>> values = new HashMap<>();
        values.put("value", List.of("x"));
        AnnotationFacts a = new AnnotationFacts("La/A;", true, values);
        values.put("later", List.of("y"));
        assertThat(a.values()).containsOnlyKeys("value");
        assertThatThrownBy(() -> a.values().put("z", List.of())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void enum_constants_and_class_literals_are_plain_strings() {
        AnnotationFacts a = new AnnotationFacts(
                "La/Mode;",
                true,
                Map.of("kind", List.of("FAST"), "type", List.of("java/lang/String"), "flags", List.of("1", "2")));
        assertThat(a.values().get("kind")).containsExactly("FAST");
        assertThat(a.values().get("type")).containsExactly("java/lang/String");
        assertThat(a.values().get("flags")).containsExactly("1", "2");
    }

    @Test
    void equality_ignores_the_input_map_ordering() {
        AnnotationFacts a = new AnnotationFacts("La/A;", true, Map.of("a", List.of("1"), "b", List.of("2")));
        Map<String, List<String>> reversed = new LinkedHashMap<>();
        reversed.put("b", List.of("2"));
        reversed.put("a", List.of("1"));
        assertThat(new AnnotationFacts("La/A;", true, reversed)).isEqualTo(a);
        assertThat(new AnnotationFacts("La/A;", false, reversed)).isNotEqualTo(a);
    }
}
