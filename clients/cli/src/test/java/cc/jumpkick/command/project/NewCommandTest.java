// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.model.command.Exit;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class NewCommandTest {

    @Test
    void with_no_terminal_and_no_template_jk_new_scaffolds_the_app_template_with_its_defaults(@TempDir Path tempDir)
            throws IOException {
        Path target = tempDir.resolve("widget");
        Run run = jk("new", "--group", "com.example", "--no-module", target.toString());
        assertThat(run.exit()).as(run.err()).isZero();

        Path buildFile = target.resolve("jk.toml");
        // No jk-lock.toml at scaffold time — it's generated on the first build/run.
        assertThat(target.resolve("jk-lock.toml")).doesNotExist();
        assertThat(target.resolve("src/main/java/com/example/Main.java")).isRegularFile();
        assertThat(target.resolve("src/test/java/com/example/MainTest.java")).isRegularFile();
        assertThat(target.resolve("AGENTS.md")).isRegularFile();
        JkBuild parsed = JkBuildParser.parse(buildFile);
        assertThat(parsed.project().group()).isEqualTo("com.example");
        assertThat(parsed.project().name()).isEqualTo("widget");
        assertThat(parsed.project().version()).isEqualTo("0.1.0");
        assertThat(parsed.project().java()).isEqualTo(25);
        assertThat(parsed.mainClass()).isEqualTo("com.example.Main");
        // The app's questions took their defaults: no fat jar, no native binary, no libraries.
        assertThat(Files.readString(buildFile))
                .doesNotContain("assembly = true")
                .doesNotContain("[native]")
                .doesNotContain("[dependencies]");
    }

    @Test
    void with_no_terminal_a_named_template_takes_its_defaults(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("lib");
        Run run = jk("new", "-t", "library", "--group", "com.example", "--no-module", target.toString());
        assertThat(run.exit()).as(run.err()).isZero();

        JkBuild parsed = JkBuildParser.parse(target.resolve("jk.toml"));
        assertThat(parsed.mainClass()).isNull();
        assertThat(target.resolve("src/main/java/com/example/Greeter.java")).isRegularFile();
    }

    @Test
    void executable_answers_the_template_app_and_no_executable_answers_library(@TempDir Path tempDir)
            throws IOException {
        Path app = tempDir.resolve("app");
        Run run = jk("new", "-e", "--group", "com.example", "--no-module", app.toString());
        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(JkBuildParser.parse(app.resolve("jk.toml")).mainClass()).isEqualTo("com.example.Main");

        Path lib = tempDir.resolve("lib");
        run = jk("new", "--no-executable", "--group", "com.example", "--no-module", lib.toString());
        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(lib.resolve("src/main/java/com/example/Greeter.java")).isRegularFile();
    }

    @Test
    void p_answers_the_template_s_questions_the_blank_flags_used_to(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("fat");
        Run run = jk(
                "new",
                "--no-module",
                "-p",
                "assembly=true",
                "-p",
                "native=true",
                "-p",
                "deps=guava",
                target.toString());
        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(Files.readString(target.resolve("jk.toml")))
                .contains("assembly = true")
                .contains("[native]")
                .contains("guava = ");
    }

    @Test
    void jdk_answers_the_template_s_java_level(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("older");
        Run run = jk("new", "--jdk", "21", "--no-module", target.toString());
        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(JkBuildParser.parse(target.resolve("jk.toml")).project().java())
                .isEqualTo(21);
    }

    @Test
    void each_former_blank_flag_that_answers_no_field_is_refused_naming_its_replacement(@TempDir Path tempDir) {
        Map<List<String>, String> refused = new LinkedHashMap<>();
        refused.put(List.of("-A"), "-p assembly=true");
        refused.put(List.of("--native"), "-p native=true");
        refused.put(List.of("-d", "guava"), "-p deps=guava,commons-lang,commons-io");
        refused.put(List.of("-m", "core"), "module = \"<name>\"");
        refused.put(List.of("-P"), "third-party-plugin");
        refused.put(List.of("--jdk", "temurin-25"), "--jdk 25");
        refused.put(List.of("-e", "-t", "cli"), "keep one");
        int i = 0;
        for (var e : refused.entrySet()) {
            Path target = tempDir.resolve("p" + i++);
            List<String> argv = new ArrayList<>(List.of("new", "--no-module"));
            argv.addAll(e.getKey());
            argv.add(target.toString());
            Run run = jk(argv.toArray(String[]::new));
            assertThat(run.exit()).as(String.join(" ", e.getKey())).isEqualTo(Exit.USAGE);
            assertThat(run.err())
                    .as(String.join(" ", e.getKey()))
                    .startsWith("jk new: ")
                    .contains(e.getValue());
            assertThat(target).doesNotExist();
        }
    }

    @Test
    void the_global_dir_is_the_parent_directory_and_a_missing_name_names_its_flag(@TempDir Path tempDir)
            throws IOException {
        Run missing = jk("new", "--no-module", "--dir", tempDir.toString());
        assertThat(missing.exit()).isEqualTo(Exit.USAGE);
        assertThat(missing.err()).isEqualTo("jk new: name needs a value: jk new <name>");

        Run run = jk("new", "widget", "--no-module", "--dir", tempDir.toString());
        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(tempDir.resolve("widget/jk.toml")).isRegularFile();
    }

    @Test
    void a_name_is_lowercase_and_a_wrong_one_names_its_flag(@TempDir Path tempDir) {
        Run run = jk("new", "--no-module", tempDir.resolve("MyApp").toString());
        assertThat(run.exit()).isEqualTo(Exit.USAGE);
        assertThat(run.err()).contains("jk new: name must match [a-z][a-z0-9-]*; got 'MyApp' (--name <name>)");
        assertThat(tempDir.resolve("MyApp")).doesNotExist();
    }

    @Test
    void a_required_template_question_without_a_flag_or_default_is_an_error_in_the_prd_s_wording(@TempDir Path tempDir)
            throws IOException {
        Path g8 = tempDir.resolve("templates/java/none/asking.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve(".jk-template.toml"), """
                language = "java"
                framework = "none"
                name = "asking"
                layouts = ["traditional"]

                [[ask]]
                key = "database"
                label = "Database"
                kind = "choice"
                options = ["none", "postgres", "mysql"]
                """);
        Files.writeString(g8.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name = \"$name$\"\n");

        Run run = jk(
                "new", "-t", g8.toString(), "--no-module", tempDir.resolve("db").toString());
        assertThat(run.exit()).isEqualTo(Exit.USAGE);
        assertThat(run.err()).isEqualTo("jk new: database needs a value: -p database=none|postgres|mysql");
    }

    @Test
    void existing_jk_toml_emits_styled_error(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), "# existing");
        var captured = new ByteArrayOutputStream();
        var prevErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("new", "--no-module", tempDir.toString());
        } finally {
            System.setErr(prevErr);
        }
        assertThat(exit).isEqualTo(2);
        assertThat(Files.readString(tempDir.resolve("jk.toml"))).isEqualTo("# existing");

        var err = captured.toString(StandardCharsets.UTF_8);
        assertThat(err).contains("Failed to create project");
        assertThat(err).contains("already exists");
        // Project name is the leaf of the target dir.
        assertThat(err).contains(tempDir.getFileName().toString());
        // Some ANSI escape codes are present (styled output).
        assertThat(err).contains("\033[");
    }

    @Test
    void kotlin_lang_scaffolds_the_kotlin_app(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("kt");
        Run run = jk("new", "--group", "com.example", "--lang", "kotlin", "--no-module", target.toString());
        assertThat(run.exit()).as(run.err()).isZero();
        JkBuild parsed = JkBuildParser.parse(target.resolve("jk.toml"));
        assertThat(parsed.project().isKotlin()).isTrue();
        assertThat(parsed.mainClass()).isEqualTo("com.example.MainKt");
        assertThat(target.resolve("src/main/kotlin/com/example/Main.kt")).isRegularFile();
    }

    @Test
    void groovy_lang_scaffolds_the_groovy_app_and_a_language_with_no_template_is_refused(@TempDir Path tempDir)
            throws IOException {
        Path target = tempDir.resolve("gv");
        Run run = jk("new", "--group", "com.example", "--lang", "groovy", "--no-module", target.toString());
        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(JkBuildParser.parse(target.resolve("jk.toml")).project().isGroovy())
                .isTrue();
        assertThat(target.resolve("src/main/groovy/com/example/Main.groovy")).isRegularFile();

        Run scala = jk(
                "new", "--lang", "scala", "--no-module", tempDir.resolve("sc").toString());
        assertThat(scala.exit()).isEqualTo(Exit.USAGE);
        assertThat(scala.err()).contains("jk new: lang must be one of java, kotlin, groovy; got 'scala'");
    }

    @Test
    void jk_init_takes_the_same_path_in_the_current_directory(@TempDir Path tempDir) throws IOException {
        Path here = Files.createDirectories(tempDir.resolve("here"));
        Run run = jk("init", "--group", "com.example", "--no-module", "--dir", here.toString());
        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(JkBuildParser.parse(here.resolve("jk.toml")).project().name())
                .isEqualTo("here");
        assertThat(here.resolve("src/main/java/com/example/Main.java")).isRegularFile();

        Run refused = jk("init", "-A", "--no-module", "--dir", tempDir.toString());
        assertThat(refused.exit()).isEqualTo(Exit.USAGE);
        assertThat(refused.err()).isEqualTo("jk init: --assembly is the template's question now: -p assembly=true");
    }

    @Test
    void wizard_preset_name_is_empty_when_no_positional() {
        assertThat(NewArgs.presetName(null, Path.of("/home/bob/myapp"))).isEmpty();
    }

    @Test
    void wizard_preset_name_uses_cwd_leaf_for_dot_arg() {
        assertThat(NewArgs.presetName(Path.of("."), Path.of("/home/bob/myapp"))).contains("myapp");
    }

    @Test
    void wizard_preset_name_uses_arg_leaf_for_named_arg() {
        // Relative arg.
        assertThat(NewArgs.presetName(Path.of("my-project"), Path.of("/home/bob")))
                .contains("my-project");
        // Absolute path: still use the leaf, not the full path.
        assertThat(NewArgs.presetName(Path.of("/tmp/foo/my-project"), Path.of("/home/bob")))
                .contains("my-project");
    }

    @Test
    void wizard_preset_name_falls_back_when_dot_at_filesystem_root() {
        // Defensive: filesystem root has no leaf name; don't blow up.
        assertThat(NewArgs.presetName(Path.of("."), Path.of("/"))).isEmpty();
    }

    @Test
    void resolve_target_with_dot_uses_cwd() {
        assertThat(NewArgs.resolveTarget(Path.of("."), Path.of("/home/bob/myapp"), "myapp"))
                .isEqualTo(Path.of("/home/bob/myapp"));
    }

    @Test
    void resolve_target_with_relative_arg_resolves_against_cwd() {
        assertThat(NewArgs.resolveTarget(Path.of("widget"), Path.of("/home/bob"), "widget"))
                .isEqualTo(Path.of("/home/bob/widget"));
    }

    @Test
    void resolve_target_with_absolute_arg_uses_it_as_is() {
        assertThat(NewArgs.resolveTarget(Path.of("/tmp/foo/widget"), Path.of("/home/bob"), "widget"))
                .isEqualTo(Path.of("/tmp/foo/widget"));
    }

    @Test
    void resolve_target_with_no_arg_creates_subdir_named_after_project() {
        assertThat(NewArgs.resolveTarget(null, Path.of("/home/bob"), "myapp")).isEqualTo(Path.of("/home/bob/myapp"));
    }

    @Test
    void named_positional_uses_arg_as_project_name(@TempDir Path tempDir) throws IOException {
        // `jk init <abs-path>/my-project` → name = "my-project" (the leaf).
        Path target = tempDir.resolve("my-project");
        int exit = Jk.execute("new", "--group", "com.example", "--no-module", target.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(target.resolve("jk.toml"));
        assertThat(parsed.project().name()).isEqualTo("my-project");
    }

    @Test
    void new_inside_workspace_registers_module_and_skips_lock(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = ["core"]
                """);
        Path app = tempDir.resolve("app");

        int exit = Jk.execute("new", "--name", "app", app.toString());
        assertThat(exit).isEqualTo(0);

        // Module project scaffolded, but with NO per-module lock (root owns it).
        assertThat(app.resolve("jk.toml")).exists();
        assertThat(app.resolve("jk-lock.toml")).doesNotExist();

        // Group inherited from the workspace root.
        JkBuild module = JkBuildParser.parse(app.resolve("jk.toml"));
        assertThat(module.project().group()).isEqualTo("cc.jumpkick");
        assertThat(module.project().name()).isEqualTo("app");

        // Registered in the root [workspace].modules.
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(workspaceOf(root).modules()).containsExactly("core", "app");
    }

    @Test
    void non_tty_skips_wizard(@TempDir Path tempDir) throws IOException {
        var captured = new ByteArrayOutputStream();
        var prevOut = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("new", "--name", "foo", "--no-module", tempDir.toString());
        } finally {
            System.setOut(prevOut);
        }
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("jk.toml")).exists();
        // No JLine raw-mode escape sequences should appear on stdout in flag mode.
        var output = captured.toString(StandardCharsets.UTF_8);
        assertThat(output).doesNotContain("\u001b[?1049h"); // alt-screen toggle
        assertThat(output).doesNotContain("\u001b[6n"); // device status report (raw mode cursor query)
    }

    /** What one {@code jk} invocation returned and printed to stderr. */
    private record Run(int exit, String err) {}

    private static Run jk(String... argv) {
        int[] exit = new int[1];
        String err = Capture.stderr(() -> Capture.stdout(() -> exit[0] = Jk.execute(argv)));
        // The command's chrome frames stderr in blank lines; the lines themselves are what is asserted.
        return new Run(exit[0], TestAnsi.strip(err).strip());
    }

    private static Workspace workspaceOf(JkBuild build) {
        assertThat(build.workspace()).as("[workspace] table").isNotNull();
        return Objects.requireNonNull(build.workspace());
    }
}
