// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static cc.jumpkick.cli.tui.form.Scripted.CTRL_C;
import static cc.jumpkick.cli.tui.form.Scripted.ENTER;
import static cc.jumpkick.cli.tui.form.Scripted.RIGHT;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.form.Scripted;
import cc.jumpkick.cli.tui.form.Scripted.Mode;
import cc.jumpkick.cli.tui.form.Scripted.Run;
import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldKind;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCommand;
import cc.jumpkick.form.Option;
import cc.jumpkick.form.Step;
import cc.jumpkick.wire.protocol.NewProjectFormAck;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@code jk new} on a terminal, scripted: jk's step, then the chosen template's step with one field
 * of every kind a template asks, then the summary. The forms are the shapes the engine serves.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class NewFormTest {

    private static final Map<String, String> CATALOGUE = new LinkedHashMap<>();

    static {
        CATALOGUE.put("java/none/app", "Blank Java application");
        CATALOGUE.put("kotlin/none/app", "Blank Kotlin application");
        CATALOGUE.put("kotlin/spring-boot/hello", "Boot hello app");
    }

    private static final Map<String, FormCommand.Flag> FLAGS = Map.of(
            "lang", FormCommand.Flag.named("--lang"),
            "template", FormCommand.Flag.named("-t"),
            "name", FormCommand.Flag.positional(),
            "group", FormCommand.Flag.named("--group"),
            "layout", FormCommand.Flag.named("-L"));

    private static final DerivedDefaults DERIVED = from -> from.equals("git-email") ? "com.acme" : null;

    /** The engine's step 1; {@code lang} defaults to the template's language once one is chosen. */
    private static Step project(String lang, boolean layout) {
        List<Field> fields = new ArrayList<>(List.of(
                Field.of("lang", "Language", FieldKind.CHOICE)
                        .option("java")
                        .option("kotlin")
                        .defaultValue(lang)
                        .build(),
                Field.of("template", "Template", FieldKind.SEARCH)
                        .source("/api/templates?lang={lang}")
                        .build(),
                Field.of("name", "Name", FieldKind.TEXT)
                        .pattern("[A-Za-z][A-Za-z0-9._-]*")
                        .build(),
                Field.of("group", "Group", FieldKind.TEXT)
                        .defaultFrom("git-email")
                        .build()));
        if (layout) {
            fields.add(Field.of("layout", "Layout", FieldKind.CHOICE)
                    .option("traditional")
                    .option("simple")
                    .defaultValue("traditional")
                    .build());
        }
        return new Step("project", "Project", fields);
    }

    private static final Step TEMPLATE = Step.of(
            "template",
            "Template options",
            Field.of("motto", "Motto", FieldKind.TEXT).pattern("[a-z]+").build(),
            Field.of("native", "Native binary", FieldKind.BOOL)
                    .defaultValue(false)
                    .build(),
            Field.of("database", "Database", FieldKind.CHOICE)
                    .option("none")
                    .option("postgres")
                    .defaultValue("none")
                    .build(),
            Field.of("libs", "Libraries", FieldKind.MULTI)
                    .option("guava")
                    .option("jackson")
                    .defaultValue(List.of())
                    .build(),
            Field.of("flavour", "Flavour", FieldKind.CHOICE)
                    .option("plain")
                    .option("sweet")
                    .defaultValue("plain")
                    .advanced(true)
                    .build());

    /** A fake engine: records each fetch and answers with the step-1 form or a template's. */
    private static final class Engine implements NewForm.Forms {
        final List<String> fetched = new ArrayList<>();

        @Override
        public NewProjectFormAck fetch(@Nullable String template, @Nullable String lang) {
            fetched.add(template + "@" + lang);
            if (template == null) return ack(Form.of("new-project", "New project", project("java", false)), "");
            String id = (lang == null ? "java" : lang) + "/none/" + template;
            String language = id.split("/")[0];
            return ack(Form.of("new-project", "New project", project(language, true), TEMPLATE), id);
        }

        private static NewProjectFormAck ack(Form form, String id) {
            return new NewProjectFormAck(null, form.toJson(), id, CATALOGUE);
        }
    }

    private static Optional<NewForm.Answered> walk(
            Scripted.Mode mode, String keys, String lines, Engine engine, NewForm.Given given, List<String> out)
            throws Exception {
        Run<NewForm.Answered> run = Scripted.drive(mode, keys, lines, asker -> {
            try {
                return NewForm.walk(asker, engine, given, DERIVED, Path.of("."), "jk new", FLAGS);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        out.add(mode == Mode.PLAIN ? run.written() : String.join("\n", run.last()));
        return run.answer();
    }

    private static NewForm.Given nothing() {
        return new NewForm.Given(null, null, null, null, null, Map.of());
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void keys_walk_jks_step_then_the_templates_and_end_on_the_command(Mode mode) throws Exception {
        Engine engine = new Engine();
        String keys = RIGHT + ENTER // kotlin
                + ENTER // template: app, the first kotlin match
                + "demo" + ENTER // name
                + ENTER // group: derived
                + RIGHT + ENTER // layout: simple
                + "hi" + ENTER // motto
                + "y" // native
                + RIGHT + ENTER // database: postgres
                + " " + ENTER; // libs: guava
        List<String> screens = new ArrayList<>();

        NewForm.Answered a = walk(mode, keys, "", engine, nothing(), screens).orElseThrow();

        assertThat(engine.fetched).containsExactly("null@null", "app@kotlin");
        assertThat(a.template()).isEqualTo("kotlin/none/app");
        assertThat(a.name()).isEqualTo("demo");
        assertThat(a.text("lang")).isEqualTo("kotlin");
        assertThat(a.text("group")).isEqualTo("com.acme");
        assertThat(a.text("layout")).isEqualTo("simple");
        assertThat(a.params())
                .containsExactly(
                        Map.entry("motto", "hi"),
                        Map.entry("native", "true"),
                        Map.entry("database", "postgres"),
                        Map.entry("libs", "guava"),
                        Map.entry("flavour", "plain"));
        assertThat(a.command())
                .isEqualTo("jk new demo -t kotlin/none/app -L simple -p motto=hi -p native=true "
                        + "-p database=postgres -p libs=guava");
        assertThat(screens.getFirst())
                .contains("Template options")
                .contains("Equivalent command  jk new demo -t kotlin/none/app")
                .doesNotContain("Flavour");
    }

    @Test
    void plain_mode_walks_the_same_form_with_lines() throws Exception {
        Engine engine = new Engine();
        String lines = "2\n" // kotlin
                + "app\n" // template
                + "demo\n" // name
                + "\n" // group
                + "\n" // layout: traditional
                + "hi\n" // motto
                + "\n" // native: no
                + "\n" // database: none
                + "none\n"; // libs
        List<String> transcripts = new ArrayList<>();

        NewForm.Answered a =
                walk(Mode.PLAIN, "", lines, engine, nothing(), transcripts).orElseThrow();

        assertThat(a.params()).containsEntry("motto", "hi").containsEntry("native", "false");
        assertThat(a.command()).isEqualTo("jk new demo -t kotlin/none/app -p motto=hi");
        assertThat(transcripts.getFirst())
                .contains("jk: = New project > Project\n")
                .contains("jk: = New project > Template options\n")
                .contains("Equivalent command: jk new demo -t kotlin/none/app -p motto=hi\n")
                .doesNotContain("\u001b");
    }

    @Test
    void flags_answer_their_fields_and_only_the_rest_is_asked() throws Exception {
        Engine engine = new Engine();
        NewForm.Given given = new NewForm.Given(
                null, "app", "demo", null, "traditional", Map.of("motto", "hi", "libs", "guava,jackson", "x", "1"));
        List<String> transcripts = new ArrayList<>();

        NewForm.Answered a = walk(Mode.PLAIN, "", "\n" + "y\n" + "2\n", engine, given, transcripts)
                .orElseThrow();

        assertThat(engine.fetched).containsExactly("app@null");
        assertThat(a.template()).isEqualTo("java/none/app");
        assertThat(a.params())
                .containsEntry("libs", "guava,jackson")
                .containsEntry("native", "true")
                .containsEntry("database", "postgres")
                .containsEntry("x", "1");
        String transcript = transcripts.getFirst();
        assertThat(transcript)
                .contains("Template\n-> java/none/app\n")
                .contains("Language\n-> java\n")
                .contains("Motto\n-> hi\n")
                .doesNotContain("Motto (")
                .contains("Group [com.acme]: -> com.acme\n");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void backing_out_answers_nothing(Mode mode) throws Exception {
        assertThat(walk(mode, ENTER + CTRL_C, "1\n", new Engine(), nothing(), new ArrayList<>()))
                .isEmpty();
    }

    @Test
    void the_template_search_lists_the_language_s_templates_by_their_shortest_ref() {
        var search = NewForm.search(CATALOGUE);
        Field f = Field.of("template", "Template", FieldKind.SEARCH)
                .source("/api/templates?lang={lang}")
                .build();
        assertThat(search.matches(f, "/api/templates?lang=kotlin", ""))
                .extracting(Option::id)
                .containsExactly("app", "spring-boot/hello");
        assertThat(search.matches(f, "/api/templates?lang=kotlin", "boot"))
                .extracting(Option::id)
                .containsExactly("spring-boot/hello");
        assertThat(search.matches(f, "/api/templates?lang=java", ""))
                .extracting(Option::help)
                .containsExactly("Blank Java application");
    }

    @Test
    void help_lists_each_field_the_template_adds() {
        Form form = Form.of("new-project", "New project", project("java", true), TEMPLATE);
        assertThat(NewForm.help(form, "java/none/app")).isEqualTo("""
                        Template java/none/app (-L for layout, -p key=value for the rest):
                          layout: traditional|simple = traditional  # Layout
                          motto: text /[a-z]+/ (required)  # Motto
                          native: true|false = false  # Native binary
                          database: none|postgres = none  # Database
                          libs: [guava,jackson] = []  # Libraries
                          flavour: plain|sweet = plain (advanced)  # Flavour
                        """);
        assertThat(NewForm.help(Form.of("new-project", "New project", project("java", false)), "java/none/x"))
                .isEqualTo("Template java/none/x asks nothing beyond jk's own flags.\n");
    }

    @Test
    void option_values_are_read_from_the_command_line() {
        assertThat(NewCommand.optionValue(List.of("-t", "cli", "--help"), "-t", "--template"))
                .isEqualTo("cli");
        assertThat(NewCommand.optionValue(List.of("--template=spring-boot/webmvc"), "-t", "--template"))
                .isEqualTo("spring-boot/webmvc");
        assertThat(NewCommand.optionValue(List.of("--help"), "-t", "--template"))
                .isNull();
    }
}
