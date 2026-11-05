// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import static cc.jumpkick.cli.tui.form.Scripted.CTRL_C;
import static cc.jumpkick.cli.tui.form.Scripted.DOWN;
import static cc.jumpkick.cli.tui.form.Scripted.ENTER;
import static cc.jumpkick.cli.tui.form.Scripted.LEFT;
import static cc.jumpkick.cli.tui.form.Scripted.RIGHT;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.form.Scripted.Mode;
import cc.jumpkick.cli.tui.form.Scripted.Run;
import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldKind;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCommand;
import cc.jumpkick.form.Option;
import cc.jumpkick.form.Step;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** A whole form walked on the terminal: conditions, advanced fields, preset answers and the summary. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class FormRendererTest {

    private static final Form FORM = Form.of(
            "new-project",
            "New",
            Step.of(
                    "project",
                    "Project",
                    Field.of("lang", "Language", FieldKind.CHOICE)
                            .option("java")
                            .option("kotlin")
                            .defaultValue("java")
                            .build(),
                    Field.of("name", "Name", FieldKind.TEXT).pattern("[a-z]+").build(),
                    Field.of("group", "Group", FieldKind.TEXT)
                            .defaultFrom("git-email")
                            .build(),
                    Field.of("template", "Template", FieldKind.SEARCH)
                            .source("/t?lang={lang}")
                            .build(),
                    Field.of("native", "Native", FieldKind.BOOL)
                            .defaultValue(false)
                            .advanced(true)
                            .build()),
            Step.of(
                    "template",
                    "Template options",
                    Field.of("database", "Database", FieldKind.CHOICE)
                            .option("none")
                            .option("postgres")
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
                            .build()));

    private static final Map<String, FormCommand.Flag> FLAGS = Map.of(
            "lang", FormCommand.Flag.named("--lang"),
            "name", FormCommand.Flag.positional(),
            "template", FormCommand.Flag.named("-t"));

    private static FormSources sources(List<String> endpoints) {
        DerivedDefaults derived = from -> from.equals("git-email") ? "com.example" : null;
        FormSources.Search search = (field, endpoint, query) -> {
            endpoints.add(endpoint);
            return List.of(Option.of("spring-boot/webmvc"), Option.of("cli"));
        };
        return new FormSources(derived, search, PathPrompt.directories(Path.of(".")));
    }

    @ParameterizedTest
    @EnumSource(
            value = Mode.class,
            names = {"NERD", "ANSI"})
    void keys_walk_every_shown_field_and_skip_hidden_and_advanced_ones(Mode mode) throws Exception {
        List<String> endpoints = new ArrayList<>();
        String keys = RIGHT + ENTER // kotlin
                + "demo" + ENTER // name
                + ENTER // group default
                + DOWN + ENTER // template: cli
                + LEFT + ENTER // database: none, so migrations is hidden
                + " " + ENTER; // features: web
        Run<Map<String, Object>> run = Scripted.drive(
                mode, keys, "", asker -> new FormRenderer(asker, sources(endpoints)).run(FORM, Map.of()));

        assertThat(run.answer()).contains(expected());
        assertThat(endpoints).first().isEqualTo("/t?lang=kotlin");
        String screen = String.join("\n", run.last());
        assertThat(screen)
                .contains("□  Language ➜ kotlin")
                .contains("□  Database ➜ none")
                .contains("□  Features ➜ web")
                .contains("Template options")
                .doesNotContain("Migrations")
                .doesNotContain("Native");
    }

    @Test
    void plain_mode_walks_the_same_form_with_lines_and_gets_the_same_answers() throws Exception {
        List<String> endpoints = new ArrayList<>();
        String lines = "2\ndemo\n\ncli\n1\n1\n";

        Run<Map<String, Object>> run = Scripted.drive(
                Mode.PLAIN, "", lines, asker -> new FormRenderer(asker, sources(endpoints)).run(FORM, Map.of()));

        assertThat(run.answer()).contains(expected());
        assertThat(run.written())
                .startsWith("jk: = New > Project\nLanguage\n  1) (*) java\n  2) ( ) kotlin\nSelect [1]: -> kotlin\n")
                .contains("Group [com.example]: -> com.example\n")
                .contains("jk: = New > Template options\n")
                .doesNotContain("Migrations")
                .doesNotContain("Native")
                .doesNotContain("\u001b");
    }

    @Test
    void answers_given_up_front_are_shown_settled_and_not_asked() throws Exception {
        List<String> endpoints = new ArrayList<>();
        Map<String, Object> given = Map.of("lang", "kotlin", "name", "demo", "template", "cli", "native", true);

        Run<Map<String, Object>> run = Scripted.drive(
                Mode.PLAIN, "", "\n1\n1\n", asker -> new FormRenderer(asker, sources(endpoints)).run(FORM, given));

        assertThat(run.answer().orElseThrow()).containsEntry("native", true).containsEntry("lang", "kotlin");
        assertThat(run.written()).contains("Language\n-> kotlin\n").contains("Name\n-> demo\n");
        assertThat(run.written()).doesNotContain("Select [1]: -> kotlin");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void backing_out_of_any_field_answers_nothing(Mode mode) throws Exception {
        Run<Map<String, Object>> run =
                Scripted.drive(mode, ENTER + CTRL_C, "1\n", asker -> new FormRenderer(asker, sources(new ArrayList<>()))
                        .run(FORM, Map.of()));

        assertThat(run.answer()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void the_summary_prints_the_equivalent_command(Mode mode) throws Exception {
        Run<Boolean> run = Scripted.drive(mode, "", "", asker -> {
            new FormRenderer(asker, sources(new ArrayList<>())).summary("jk new", FORM, expected(), FLAGS);
            return Optional.of(true);
        });

        String command = "jk new demo --lang kotlin -t cli -p database=none -p features=web";
        if (mode == Mode.PLAIN) {
            assertThat(run.written()).isEqualTo("Equivalent command: " + command + "\n");
        } else {
            assertThat(String.join("", run.last())).isEqualTo("Equivalent command  " + command);
        }
    }

    /** What the scripts answer; {@code migrations} is hidden by {@code database = none} and takes its default. */
    private static Map<String, Object> expected() {
        return Map.of(
                "lang", "kotlin",
                "name", "demo",
                "group", "com.example",
                "template", "cli",
                "native", false,
                "database", "none",
                "migrations", "flyway",
                "features", List.of("web"));
    }
}
