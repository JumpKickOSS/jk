// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Building a form, its defaults, answer checking, {@code rev} and the JSON round trip. */
class FormTest {

    static Form sample() {
        return Form.of(
                "new-project",
                "New project",
                Step.of(
                        "project",
                        "Project",
                        Field.of("lang", "Language", FieldKind.CHOICE)
                                .option("java", "Java", "The JVM's own")
                                .option("kotlin")
                                .defaultValue("java")
                                .build(),
                        Field.of("name", "Name", FieldKind.TEXT)
                                .help("Also the directory")
                                .pattern("[a-z][a-z0-9-]*")
                                .build(),
                        Field.of("group", "Group", FieldKind.TEXT)
                                .defaultFrom("git-email")
                                .build(),
                        Field.of("template", "Template", FieldKind.SEARCH)
                                .source("/api/templates?lang={lang}")
                                .build(),
                        Field.of("dir", "Directory", FieldKind.PATH)
                                .defaultValue(".")
                                .build()),
                Step.of(
                        "template",
                        "Template",
                        Field.of("database", "Database", FieldKind.CHOICE)
                                .option("none")
                                .option("postgres")
                                .option("mysql")
                                .defaultValue("postgres")
                                .build(),
                        Field.of("migrations", "Migrations", FieldKind.CHOICE)
                                .option("flyway")
                                .option("liquibase")
                                .defaultValue("flyway")
                                .when("database != 'none'")
                                .build(),
                        Field.of("features", "Features", FieldKind.MULTI)
                                .option("web")
                                .option("cli")
                                .defaultValue(List.of("web"))
                                .build(),
                        Field.of("native", "Build a native binary", FieldKind.BOOL)
                                .defaultValue(false)
                                .advanced(true)
                                .build()));
    }

    @Test
    void required_defaults_to_true_only_without_a_default() {
        Form form = sample();

        assertThat(field(form, "name").required()).isTrue();
        assertThat(field(form, "template").required()).isTrue();
        assertThat(field(form, "lang").required()).isFalse();
        assertThat(field(form, "group").required()).isFalse();
        assertThat(Field.of("x", "X", FieldKind.TEXT)
                        .defaultValue("a")
                        .required(true)
                        .build()
                        .required())
                .isTrue();
    }

    @Test
    void defaults_fill_what_was_not_answered_and_derived_ones_ask_the_resolver() {
        Form form = sample();
        DerivedDefaults derived = from -> from.equals("git-email") ? "com.example" : null;

        FormCheck checked = form.check(Map.of("name", "demo", "template", "spring-boot/webmvc"), derived);

        assertThat(checked.ok()).isTrue();
        assertThat(checked.values())
                .containsExactly(
                        Map.entry("lang", "java"),
                        Map.entry("name", "demo"),
                        Map.entry("group", "com.example"),
                        Map.entry("template", "spring-boot/webmvc"),
                        Map.entry("dir", "."),
                        Map.entry("database", "postgres"),
                        Map.entry("migrations", "flyway"),
                        Map.entry("features", List.of("web")),
                        Map.entry("native", false));
        assertThat(form.check(Map.of("name", "demo", "template", "t"), DerivedDefaults.NONE)
                        .values())
                .doesNotContainKey("group");
    }

    @Test
    void a_derived_default_that_does_not_fit_counts_as_none() {
        Field lang = Field.of("lang", "Language", FieldKind.CHOICE)
                .option("java")
                .defaultFrom("history")
                .build();

        assertThat(Form.defaultOf(lang, from -> "cobol")).isNull();
        assertThat(Form.defaultOf(lang, from -> "java")).isEqualTo("java");
    }

    @Test
    void a_hidden_field_takes_its_default_whatever_was_answered() {
        FormCheck checked =
                sample().check(answers("database", "none", "migrations", "liquibase"), DerivedDefaults.NONE);

        assertThat(checked.ok()).isTrue();
        assertThat(checked.hidden()).containsExactly("migrations");
        assertThat(checked.values()).containsEntry("migrations", "flyway");
    }

    @Test
    void answers_are_checked_against_kind_options_pattern_and_required() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("lang", "cobol");
        bad.put("name", "Demo");
        bad.put("features", List.of("web", "gui"));
        bad.put("native", "yes");
        bad.put("port", "8080");

        FormCheck checked = sample().check(bad, DerivedDefaults.NONE);

        assertThat(checked.problems())
                .extracting(FormCheck.Problem::message)
                .containsExactly(
                        "'port' is not a field of this form",
                        "lang must be one of java, kotlin; got 'cobol'",
                        "name must match [a-z][a-z0-9-]*; got 'Demo'",
                        "template needs a value",
                        "features must be one of web, cli; got 'gui'",
                        "native must be true or false");
    }

    @Test
    void an_empty_string_is_no_answer_to_a_required_text_field() {
        FormCheck checked = sample().check(answers("name", "", "template", "t"), DerivedDefaults.NONE);

        assertThat(checked.problems()).extracting(FormCheck.Problem::message).containsExactly("name needs a value");
    }

    @Test
    void a_multi_answer_settles_in_option_order() {
        FormCheck checked = sample().check(
                        answers("name", "a", "template", "t", "features", List.of("cli", "web")), DerivedDefaults.NONE);

        assertThat(checked.values()).containsEntry("features", List.of("web", "cli"));
    }

    @Test
    void building_refuses_what_no_surface_could_ask() {
        assertThatThrownBy(() -> Field.of("db", "Db", FieldKind.CHOICE).build())
                .hasMessage("db: a choice field needs options");
        assertThatThrownBy(
                        () -> Field.of("db", "Db", FieldKind.TEXT).option("x").build())
                .hasMessage("db: a text field has no options");
        assertThatThrownBy(() -> Field.of("db", "Db", FieldKind.CHOICE)
                        .option("a")
                        .defaultValue("b")
                        .build())
                .hasMessageContaining("db: default does not fit: db must be one of a; got 'b'");
        assertThatThrownBy(() -> Field.of("n", "N", FieldKind.TEXT)
                        .pattern("[a-z]+")
                        .defaultValue("A")
                        .build())
                .hasMessageContaining("default does not fit");
        assertThatThrownBy(() -> Field.of("n", "N", FieldKind.TEXT).pattern("[").build())
                .hasMessageContaining("does not compile");
        assertThatThrownBy(() -> Field.of("n", "N", FieldKind.BOOL).pattern("x").build())
                .hasMessage("n: only a text field has a pattern");
        assertThatThrownBy(() -> Field.of("t", "T", FieldKind.SEARCH).build())
                .hasMessage("t: a search field needs a source");
        assertThatThrownBy(() -> Field.of("in", "In", FieldKind.BOOL).build()).hasMessageContaining("field key 'in'");
        assertThatThrownBy(() -> Field.of("a-b", "A", FieldKind.BOOL).build()).hasMessageContaining("field key 'a-b'");
        assertThatThrownBy(() -> Option.of("a,b")).hasMessageContaining("no comma or whitespace");
        assertThatThrownBy(() ->
                        Field.of("x", "X", FieldKind.TEXT).help("two\nlines").build())
                .hasMessage("x help must be one line");
    }

    @Test
    void building_refuses_duplicates_and_sources_naming_later_fields() {
        Field a = Field.of("a", "A", FieldKind.BOOL).build();

        assertThatThrownBy(() -> Form.of("f", "F", Step.of("one", "One", a), Step.of("two", "Two", a)))
                .hasMessage("field 'a' appears twice");
        assertThatThrownBy(() -> Form.of("f", "F", Step.of("one", "One", a), Step.of("one", "Again", a)))
                .hasMessage("step 'one' appears twice");
        Field search = Field.of("t", "T", FieldKind.SEARCH).source("/x?q={a}").build();
        assertThatThrownBy(() -> Form.of("f", "F", Step.of("one", "One", search, a)))
                .hasMessageContaining("source of 't' (/x?q={a}) names 'a', which is not an earlier field");
    }

    @Test
    void a_source_expands_earlier_answers_url_encoded() {
        Field template = field(sample(), "template");

        assertThat(template.sourceFor(Map.of("lang", "c++ & co"))).isEqualTo("/api/templates?lang=c%2B%2B+%26+co");
        assertThat(template.sourceFor(Map.of())).isEqualTo("/api/templates?lang=");
    }

    @Test
    void rev_is_stable_for_equal_content_and_moves_with_any_change() {
        String rev = sample().rev();

        assertThat(rev).hasSize(16).matches("[0-9a-f]+").isEqualTo(sample().rev());
        Form relabelled = new Form(sample().id(), "New project!", sample().steps());
        assertThat(relabelled.rev()).isNotEqualTo(rev);
        Form reordered = Form.of(
                "new-project",
                "New project",
                sample().steps().get(0),
                sample().steps().get(1));
        assertThat(reordered.rev()).isEqualTo(rev);
    }

    @Test
    void json_round_trips_to_an_equal_form_with_the_same_rev() {
        Form form = sample();
        String json = form.toJson();

        Form back = Form.fromJson(json);

        assertThat(back).isEqualTo(form);
        assertThat(back.rev()).isEqualTo(form.rev());
        assertThat(back.toJson()).isEqualTo(json);
        assertThat(json)
                .startsWith("{\"id\":\"new-project\",\"title\":\"New project\",\"rev\":\"" + form.rev() + "\"")
                .contains("\"options\":[{\"id\":\"java\",\"label\":\"Java\",\"help\":\"The JVM's own\"},\"kotlin\"]")
                .contains("\"from\":\"git-email\"")
                .contains("\"when\":\"database != 'none'\"")
                .contains("\"advanced\":true")
                .contains("\"default\":[\"web\"]");
    }

    @Test
    void decoding_refuses_a_rev_that_disagrees_and_names_where_a_field_is_wrong() {
        String json = sample().toJson();

        assertThatThrownBy(() -> Form.fromJson(json.replace(sample().rev(), "0000000000000000")))
                .hasMessageContaining("does not match its content");
        assertThatThrownBy(() -> Form.fromJson(json.replace("\"kind\":\"path\"", "\"kind\":\"date\"")))
                .hasMessageContaining("unknown kind 'date'");
        assertThatThrownBy(() -> Form.fromJson("{\"id\":\"f\",\"title\":\"F\",\"steps\":[{\"id\":\"s\",\"title\":\"S\","
                        + "\"fields\":[{\"label\":\"L\",\"kind\":\"bool\"}]}]}"))
                .hasMessage("steps[0].fields[0]: key is missing");
        assertThatThrownBy(() -> Form.fromJson("{")).hasMessageContaining("does not parse");
    }

    private static Field field(Form form, String key) {
        return Objects.requireNonNull(form.field(key), key);
    }

    private static Map<String, Object> answers(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", "demo");
        out.put("template", "t");
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }
}
