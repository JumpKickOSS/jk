// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnswerVectorsTest {

    private static Field layout() {
        return Field.of("layout", "Layout", FieldKind.CHOICE)
                .option("traditional")
                .option("simple")
                .defaultValue("traditional")
                .build();
    }

    private static Field deps(String... options) {
        Field.Builder f = Field.of("deps", "Libraries", FieldKind.MULTI).defaultValue(List.of());
        for (String o : options) f.option(o);
        return f.build();
    }

    private static Field bool(String key) {
        return Field.of(key, key, FieldKind.BOOL).defaultValue(false).build();
    }

    private static Field database() {
        return Field.of("database", "Database", FieldKind.CHOICE)
                .option("none")
                .option("h2")
                .option("postgres")
                .defaultValue("none")
                .build();
    }

    @Test
    void the_defaults_come_first_and_answer_nothing_a_default_answers() {
        List<AnswerVectors.Vector> vectors = AnswerVectors.of(List.of(layout(), deps("guava"), bool("native")));

        assertThat(vectors.getFirst().label()).isEqualTo(AnswerVectors.DEFAULTS);
        assertThat(vectors.getFirst().answers()).isEmpty();
        assertThat(vectors.stream().skip(1).map(AnswerVectors.Vector::label))
                .doesNotContain(AnswerVectors.DEFAULTS, "")
                .doesNotHaveDuplicates();
    }

    @Test
    void every_two_values_of_two_fields_appear_together_and_the_cover_is_not_the_product() {
        List<Field> fields = List.of(layout(), database(), deps("guava", "commons-io"), bool("native"));
        List<AnswerVectors.Vector> vectors = AnswerVectors.of(fields);

        // Axes: layout(2) database(3) deps.guava(2) deps.commons-io(2) native(2) — 48 in the product.
        // The defaults vector answers nothing and settles to every default, so its row is the defaults.
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(Map.of(
                "layout", "traditional",
                "database", "none",
                "deps.guava", "false",
                "deps.commons-io", "false",
                "native", "false"));
        for (AnswerVectors.Vector v : vectors.subList(1, vectors.size())) rows.add(axes(v.answers()));
        assertThat(vectors).hasSizeLessThan(48);
        List<String> axes = List.of("layout", "database", "deps.guava", "deps.commons-io", "native");
        Map<String, List<String>> values = Map.of(
                "layout", List.of("traditional", "simple"),
                "database", List.of("none", "h2", "postgres"),
                "deps.guava", List.of("false", "true"),
                "deps.commons-io", List.of("false", "true"),
                "native", List.of("false", "true"));
        for (int i = 0; i < axes.size(); i++) {
            for (int j = i + 1; j < axes.size(); j++) {
                for (String a : values.getOrDefault(axes.get(i), List.of())) {
                    for (String b : values.getOrDefault(axes.get(j), List.of())) {
                        String x = axes.get(i);
                        String y = axes.get(j);
                        assertThat(rows)
                                .as("%s=%s with %s=%s", x, a, y, b)
                                .anyMatch(r -> a.equals(r.get(x)) && b.equals(r.get(y)));
                    }
                }
            }
        }
    }

    @Test
    void a_new_option_demands_a_vector_that_picks_it() {
        List<AnswerVectors.Vector> before = AnswerVectors.of(List.of(layout(), deps("guava")));
        List<AnswerVectors.Vector> after = AnswerVectors.of(List.of(layout(), deps("guava", "jackson")));

        assertThat(before).noneMatch(v -> v.label().contains("jackson"));
        assertThat(after).anyMatch(v -> ((List<?>) v.answers().getOrDefault("deps", List.of())).contains("jackson"));
    }

    @Test
    void the_vectors_are_the_same_on_every_run() {
        List<Field> fields = List.of(layout(), database(), deps("guava", "commons-io"), bool("native"));
        assertThat(AnswerVectors.of(fields)).isEqualTo(AnswerVectors.of(fields));
    }

    @Test
    void a_text_field_with_no_default_cannot_be_answered() {
        Field motto = Field.of("motto", "Motto", FieldKind.TEXT).build();
        assertThatThrownBy(() -> AnswerVectors.of(List.of(motto)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("motto");
    }

    /** A vector's answers on the cover's axes: a multi as one true/false axis per option. */
    private static Map<String, String> axes(Map<String, Object> answers) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : answers.entrySet()) {
            if (e.getValue() instanceof List<?> picked) {
                for (String o : List.of("guava", "commons-io"))
                    out.put(e.getKey() + "." + o, String.valueOf(picked.contains(o)));
            } else {
                out.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return out;
    }
}
