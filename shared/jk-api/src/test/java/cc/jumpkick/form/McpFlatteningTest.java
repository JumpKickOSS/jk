// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.form.McpFlattening.Answered;
import cc.jumpkick.form.McpFlattening.Ask;
import cc.jumpkick.form.McpFlattening.Complete;
import cc.jumpkick.form.McpFlattening.Elicitation;
import cc.jumpkick.form.McpFlattening.Escalate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** One requested schema per field kind and protocol revision, and the escalations. */
class McpFlatteningTest {

    private static final McpFlattening.SearchMatches NO_SEARCH = (f, endpoint) -> List.of();

    @Test
    void text_is_a_string_with_its_pattern() {
        Field name = Field.of("name", "Name", FieldKind.TEXT)
                .help("The leaf")
                .pattern("[a-z]+")
                .build();

        for (McpRevision rev : McpRevision.values()) {
            assertThat(schema(rev, name))
                    .isEqualTo("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"title\":\"Name\","
                            + "\"description\":\"The leaf\",\"pattern\":\"[a-z]+\"}},\"required\":[\"name\"]}");
        }
    }

    @Test
    void bool_is_a_boolean() {
        Field ok = Field.of("ok", "Proceed", FieldKind.BOOL).build();

        assertThat(schema(McpRevision.V2025_06_18, ok)).contains("\"ok\":{\"type\":\"boolean\",\"title\":\"Proceed\"}");
    }

    @Test
    void choice_is_a_string_enum_labelled_per_revision() {
        Field db = Field.of("db", "Database", FieldKind.CHOICE)
                .option("pg", "PostgreSQL", null)
                .option("none")
                .build();
        Field bare = Field.of("db", "Database", FieldKind.CHOICE)
                .option("pg")
                .option("none")
                .build();

        assertThat(schema(McpRevision.V2025_06_18, db))
                .contains("\"db\":{\"type\":\"string\",\"title\":\"Database\",\"enum\":[\"pg\",\"none\"],"
                        + "\"enumNames\":[\"PostgreSQL\",\"none\"]}");
        assertThat(schema(McpRevision.V2025_11_25, db))
                .contains("\"db\":{\"type\":\"string\",\"title\":\"Database\",\"oneOf\":[{\"const\":\"pg\","
                        + "\"title\":\"PostgreSQL\"},{\"const\":\"none\",\"title\":\"none\"}]}");
        assertThat(schema(McpRevision.V2025_11_25, bare)).contains("\"enum\":[\"pg\",\"none\"]}");
    }

    @Test
    void multi_is_an_array_where_the_revision_has_them() {
        Ask ask = ask(McpRevision.V2025_11_25, features());

        assertThat(ask.schemaJson())
                .isEqualTo(
                        "{\"type\":\"object\",\"properties\":{\"features\":{\"type\":\"array\",\"title\":\"Features\","
                                + "\"items\":{\"anyOf\":[{\"const\":\"web\",\"title\":\"Web\"},{\"const\":\"cli\",\"title\":\"cli\"}]}}},"
                                + "\"required\":[\"features\"]}");
        assertThat(ask.answers(Map.of("features", List.of("cli"))))
                .isEqualTo(new Answered(Map.of("features", List.of("cli"))));
    }

    @Test
    void multi_is_a_boolean_per_option_where_the_revision_lacks_arrays() {
        Ask ask = ask(McpRevision.V2025_06_18, features());

        assertThat(ask.schemaJson())
                .isEqualTo("{\"type\":\"object\",\"properties\":{"
                        + "\"features.web\":{\"type\":\"boolean\",\"title\":\"Web\",\"description\":\"Features\",\"default\":false},"
                        + "\"features.cli\":{\"type\":\"boolean\",\"title\":\"cli\",\"description\":\"Features: A command line\","
                        + "\"default\":false}}}");
        assertThat(ask.answers(Map.of("features.web", true, "features.cli", false)))
                .isEqualTo(new Answered(Map.of("features", List.of("web"))));
    }

    @Test
    void search_offers_the_top_eight_matches_and_more_which_escalates() {
        Field lang = Field.of("lang", "Language", FieldKind.CHOICE)
                .option("java")
                .defaultValue("java")
                .build();
        Field template = Field.of("template", "Template", FieldKind.SEARCH)
                .source("/t?lang={lang}")
                .build();
        Form form = Form.of("f", "F", Step.of("s", "Pick one", lang, template));
        List<String> asked = new ArrayList<>();
        McpFlattening.SearchMatches search = (f, endpoint) -> {
            asked.add(endpoint);
            return IntStream.range(0, 12).mapToObj(i -> Option.of("t" + i)).toList();
        };

        Elicitation e = McpFlattening.flatten(
                form, form.steps().getFirst(), Map.of(), McpRevision.V2025_06_18, DerivedDefaults.NONE, search);

        Ask ask = (Ask) e;
        assertThat(asked).containsExactly("/t?lang=java");
        assertThat(ask.message()).isEqualTo("Pick one");
        assertThat(ask.schemaJson())
                .contains("\"enum\":[\"t0\",\"t1\",\"t2\",\"t3\",\"t4\",\"t5\",\"t6\",\"t7\",\"more …\"]")
                .contains("\"enumNames\":[\"t0\",\"t1\",\"t2\",\"t3\",\"t4\",\"t5\",\"t6\",\"t7\",\"More…\"]");
        assertThat(ask.answers(Map.of("template", "t3"))).isEqualTo(new Answered(Map.of("template", "t3")));
        assertThat(ask.answers(Map.of("template", McpFlattening.MORE)))
                .isInstanceOf(Escalate.class)
                .extracting(r -> ((Escalate) r).keys())
                .isEqualTo(List.of("template"));
    }

    @Test
    void search_with_no_matches_and_path_escalate() {
        Field template =
                Field.of("template", "Template", FieldKind.SEARCH).source("/t").build();
        Field dir = Field.of("dir", "Directory", FieldKind.PATH).build();

        assertThat(flatten(McpRevision.V2025_11_25, template))
                .isEqualTo(new Escalate(List.of("template"), "template has no matches to offer"));
        assertThat(flatten(McpRevision.V2025_11_25, dir))
                .isEqualTo(new Escalate(List.of("dir"), "dir is a directory, which needs the wizard"));
    }

    @Test
    void only_missing_required_fields_are_asked() {
        Field answered = Field.of("a", "A", FieldKind.BOOL).build();
        Field defaulted = Field.of("b", "B", FieldKind.BOOL).defaultValue(true).build();
        Field optional = Field.of("c", "C", FieldKind.TEXT).required(false).build();
        Field advanced = Field.of("d", "D", FieldKind.BOOL).advanced(true).build();
        Field hidden = Field.of("e", "E", FieldKind.BOOL).when("!b").build();
        Field dir = Field.of("dir", "Dir", FieldKind.PATH).defaultFrom("cwd").build();
        Field asked = Field.of("f", "F", FieldKind.BOOL).build();
        Form form = Form.of("f", "F", Step.of("s", "S", answered, defaulted, optional, advanced, hidden, dir, asked));

        Elicitation e = McpFlattening.flatten(
                form, form.steps().getFirst(), Map.of("a", true), McpRevision.V2025_11_25, from -> "/work", NO_SEARCH);

        assertThat(((Ask) e).fields()).extracting(Field::key).containsExactly("f");
        assertThat(McpFlattening.flatten(
                        form,
                        form.steps().getFirst(),
                        Map.of("a", true, "f", false),
                        McpRevision.V2025_11_25,
                        from -> "/work",
                        NO_SEARCH))
                .isEqualTo(new Complete());
    }

    @Test
    void a_field_whose_condition_names_a_field_being_asked_waits_for_the_next_round() {
        Field db = Field.of("db", "Database", FieldKind.CHOICE)
                .option("none")
                .option("pg")
                .build();
        Field migrations = Field.of("migrations", "Migrations", FieldKind.CHOICE)
                .option("flyway")
                .when("db != 'none'")
                .build();
        Field after = Field.of("after", "After", FieldKind.BOOL)
                .when("migrations == 'flyway'")
                .build();
        Form form = Form.of("f", "F", Step.of("s", "S", db, migrations, after));
        Step step = form.steps().getFirst();

        Ask first = (Ask)
                McpFlattening.flatten(form, step, Map.of(), McpRevision.V2025_11_25, DerivedDefaults.NONE, NO_SEARCH);
        Ask second = (Ask) McpFlattening.flatten(
                form, step, Map.of("db", "pg"), McpRevision.V2025_11_25, DerivedDefaults.NONE, NO_SEARCH);
        Elicitation none = McpFlattening.flatten(
                form, step, Map.of("db", "none"), McpRevision.V2025_11_25, DerivedDefaults.NONE, NO_SEARCH);

        assertThat(first.fields()).extracting(Field::key).containsExactly("db");
        assertThat(second.fields()).extracting(Field::key).containsExactly("migrations");
        assertThat(none).isEqualTo(new Complete());
    }

    @Test
    void the_revision_follows_the_negotiated_protocol_version() {
        assertThat(McpRevision.of("2024-11-05")).isNull();
        assertThat(McpRevision.of("2025-06-18")).isEqualTo(McpRevision.V2025_06_18);
        assertThat(McpRevision.of("2025-09-01")).isEqualTo(McpRevision.V2025_06_18);
        assertThat(McpRevision.of("2025-11-25")).isEqualTo(McpRevision.V2025_11_25);
        assertThat(McpRevision.of("2026-06-01")).isEqualTo(McpRevision.V2025_11_25);
    }

    private static Field features() {
        return Field.of("features", "Features", FieldKind.MULTI)
                .option("web", "Web", null)
                .option("cli", null, "A command line")
                .build();
    }

    private static Elicitation flatten(McpRevision rev, Field field) {
        Form form = Form.of("f", "F", Step.of("s", "S", field));
        return McpFlattening.flatten(form, form.steps().getFirst(), Map.of(), rev, DerivedDefaults.NONE, NO_SEARCH);
    }

    private static Ask ask(McpRevision rev, Field field) {
        return (Ask) flatten(rev, field);
    }

    private static String schema(McpRevision rev, Field field) {
        return ask(rev, field).schemaJson();
    }
}
