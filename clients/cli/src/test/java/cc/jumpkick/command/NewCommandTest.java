// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class NewCommandTest {

    @Test
    void flag_mode_writes_files(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "new", "--group", "com.example", "--name", "widget", "--jdk", "25", "--no-module", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        Path buildFile = tempDir.resolve("jk.toml");
        assertThat(buildFile).exists();
        // No jk-lock.toml at scaffold time — it's generated on the first build/run.
        assertThat(tempDir.resolve("jk-lock.toml")).doesNotExist();
        // Traditional layout is the default: Maven roots exist from the start.
        assertThat(tempDir.resolve("src/main/java")).isDirectory();
        assertThat(tempDir.resolve("src/test/java")).isDirectory();
        assertThat(Files.readString(buildFile)).doesNotContain("layout");

        JkBuild parsed = JkBuildParser.parse(buildFile);
        assertThat(parsed.project().group()).isEqualTo("com.example");
        assertThat(parsed.project().name()).isEqualTo("widget");
        assertThat(parsed.project().version()).isEqualTo("0.1.0");
        assertThat(parsed.project().jdk()).isEqualTo("25");
        assertThat(parsed.project().java()).isEqualTo(25);
        assertThat(parsed.project().isKotlin()).isFalse();
    }

    @Test
    void explicit_vendor_spec_is_preserved_in_jk_toml(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "new",
                "--group",
                "com.example",
                "--name",
                "widget",
                "--jdk",
                "corretto-25",
                "--no-module",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        // The vendor survives only because the user typed it on the command line.
        assertThat(parsed.project().jdk()).isEqualTo("corretto-25");
        assertThat(parsed.project().jdkMajor()).isEqualTo(25);
        assertThat(Files.readString(tempDir.resolve("jk.toml"))).contains("jdk      = \"corretto-25\"");
    }

    @Test
    void bare_major_jdk_flag_writes_a_bare_pin(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "new", "--group", "com.example", "--name", "widget", "--jdk", "21", "--no-module", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(JkBuildParser.parse(tempDir.resolve("jk.toml")).project().jdk())
                .isEqualTo("21");
    }

    @Test
    void native_project_keeps_a_normal_jdk_and_writes_no_graal(@TempDir Path tempDir) throws IOException {
        // A native project's build JDK follows the normal rules (here an explicit
        // bare major); the GraalVM is never chosen here — it's resolved into jk-lock.toml
        // when [native] is declared — so no `graal` key lands in jk.toml (it's
        // defaulted to the "graalvm" spec at parse time instead).
        int exit = Jk.execute(
                "new",
                "--group",
                "com.example",
                "--name",
                "widget",
                "--native",
                "--jdk",
                "21",
                "--no-module",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.project().jdk()).isEqualTo("21"); // not forced to a GraalVM
        assertThat(parsed.graal()).isEqualTo("graalvm"); // defaulted — no graal key written
        assertThat(parsed.nativeMode()).isEqualTo(JkBuild.NativeMode.ALWAYS);

        String toml = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(toml).contains("[native]").contains("enabled    = \"always\"");
        assertThat(toml).doesNotContain("graal");
    }

    @Test
    void point_release_jdk_flag_is_rejected(@TempDir Path tempDir) {
        var prevErr = System.err;
        var captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute(
                    "new",
                    "--group",
                    "com.example",
                    "--name",
                    "widget",
                    "--jdk",
                    "25.0.3",
                    "--no-module",
                    tempDir.toString());
        } finally {
            System.setErr(prevErr);
        }
        assertThat(exit).isEqualTo(64); // EX_USAGE
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("point release");
        assertThat(tempDir.resolve("jk.toml")).doesNotExist();
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
    void assembly_implies_executable(@TempDir Path tempDir) throws IOException {
        // --assembly used to require an explicit --main; now it just implies
        // --executable and the generated Main FQCN is derived from the group.
        int exit = Jk.execute(
                "new",
                "--group",
                "com.example",
                "--name",
                "widget",
                "--assembly",
                "--layout",
                "traditional",
                "--no-module",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.mainClass()).isEqualTo("com.example.Main");
        assertThat(parsed.isRunnable()).isTrue();
    }

    @Test
    void executable_writes_derived_main_field(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "new",
                "--group",
                "com.example",
                "--name",
                "widget",
                "--executable",
                "--layout",
                "traditional",
                "--no-module",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.mainClass()).isEqualTo("com.example.Main");
        assertThat(parsed.isRunnable()).isTrue();
    }

    @Test
    void executable_simple_layout_writes_packaged_main_fqcn(@TempDir Path tempDir) throws IOException {
        // Simple layout still packages Main under the group: src/<group>/Main.java
        // with `package <group>;`. The jk.toml main field must be the FQCN.
        int exit = Jk.execute(
                "new",
                "--group",
                "com.example",
                "--name",
                "widget",
                "--executable",
                "--layout",
                "simple",
                "--no-module",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        Path main = tempDir.resolve("src/com/example/Main.java");
        assertThat(main).exists();
        assertThat(Files.readString(main)).contains("package com.example;");

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.mainClass()).isEqualTo("com.example.Main");
        assertThat(parsed.isRunnable()).isTrue();
        assertThat(Files.readString(tempDir.resolve("jk.toml"))).doesNotContain("layout");
    }

    @Test
    void executable_default_layout_is_traditional(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "new", "--group", "com.example", "--name", "widget", "--executable", "--no-module", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        Path main = tempDir.resolve("src/main/java/com/example/Main.java");
        assertThat(main).exists();
        assertThat(Files.readString(main)).contains("package com.example;");
        assertThat(Files.readString(tempDir.resolve("jk.toml"))).doesNotContain("layout");
    }

    @Test
    void help_lists_traditional_before_simple() {
        String newHelp =
                Capture.stdout(() -> assertThat(Jk.execute("new", "--help")).isZero());
        assertThat(newHelp).contains("Tree: traditional (default) | simple | auto.");
        assertThat(newHelp.indexOf("traditional")).isLessThan(newHelp.indexOf("simple"));

        String initHelp =
                Capture.stdout(() -> assertThat(Jk.execute("init", "--help")).isZero());
        assertThat(initHelp).contains("Tree: traditional (default) | simple | auto.");
        assertThat(initHelp.indexOf("traditional")).isLessThan(initHelp.indexOf("simple"));
    }

    @Test
    void unknown_layout_flag_is_usage_error(@TempDir Path tempDir) {
        String err = Capture.stderr(() -> assertThat(Jk.execute(
                        "new",
                        "--group",
                        "com.example",
                        "--name",
                        "widget",
                        "--layout",
                        "mill",
                        "--no-module",
                        tempDir.toString()))
                .isEqualTo(64));
        assertThat(err).contains("layout must be");
        assertThat(tempDir.resolve("jk.toml")).doesNotExist();
    }

    @Test
    void library_when_no_main(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute("new", "--group", "com.example", "--name", "widget", "--no-module", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.mainClass()).isNull();
        assertThat(parsed.isRunnable()).isFalse();
    }

    @Test
    void kotlin_lang_writes_kt_sample(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "new",
                "--group",
                "com.example",
                "--name",
                "widget",
                "--lang",
                "kotlin",
                "--executable",
                "--no-module",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        // Default layout is traditional: Main.kt lands under src/main/kotlin/<group>.
        Path app = tempDir.resolve("src/main/kotlin/com/example/Main.kt");
        assertThat(app).exists();
        assertThat(Files.readString(app)).contains("package com.example");
        assertThat(Files.readString(app)).contains("fun main()");
    }

    @Test
    void groovy_lang_writes_groovy_sample(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "new",
                "--group",
                "com.example",
                "--name",
                "widget",
                "--lang",
                "groovy",
                "--executable",
                "--no-module",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        // Default layout is traditional: Main.groovy lands under src/main/groovy/<group>.
        Path app = tempDir.resolve("src/main/groovy/com/example/Main.groovy");
        assertThat(app).exists();
        assertThat(Files.readString(app)).contains("package com.example");
        assertThat(Files.readString(app)).contains("static void main");

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.project().isGroovy()).isTrue();
        assertThat(parsed.mainClass()).isEqualTo("com.example.Main");
    }

    @Test
    void wizard_preset_name_is_empty_when_no_positional() {
        assertThat(NewWizard.wizardPresetName(null, Path.of("/home/bob/myapp"))).isEmpty();
    }

    @Test
    void wizard_preset_name_uses_cwd_leaf_for_dot_arg() {
        assertThat(NewWizard.wizardPresetName(Path.of("."), Path.of("/home/bob/myapp")))
                .contains("myapp");
    }

    @Test
    void wizard_preset_name_uses_arg_leaf_for_named_arg() {
        // Relative arg.
        assertThat(NewWizard.wizardPresetName(Path.of("my-project"), Path.of("/home/bob")))
                .contains("my-project");
        // Absolute path: still use the leaf, not the full path.
        assertThat(NewWizard.wizardPresetName(Path.of("/tmp/foo/my-project"), Path.of("/home/bob")))
                .contains("my-project");
    }

    @Test
    void wizard_preset_name_falls_back_when_dot_at_filesystem_root() {
        // Defensive: filesystem root has no leaf name; don't blow up.
        assertThat(NewWizard.wizardPresetName(Path.of("."), Path.of("/"))).isEmpty();
    }

    @Test
    void resolve_target_with_dot_uses_cwd() {
        assertThat(NewWizard.resolveTarget(Path.of("."), Path.of("/home/bob/myapp"), "myapp"))
                .isEqualTo(Path.of("/home/bob/myapp"));
    }

    @Test
    void resolve_target_with_relative_arg_resolves_against_cwd() {
        assertThat(NewWizard.resolveTarget(Path.of("widget"), Path.of("/home/bob"), "widget"))
                .isEqualTo(Path.of("/home/bob/widget"));
    }

    @Test
    void resolve_target_with_absolute_arg_uses_it_as_is() {
        assertThat(NewWizard.resolveTarget(Path.of("/tmp/foo/widget"), Path.of("/home/bob"), "widget"))
                .isEqualTo(Path.of("/tmp/foo/widget"));
    }

    @Test
    void resolve_target_with_no_arg_creates_subdir_named_after_project() {
        assertThat(NewWizard.resolveTarget(null, Path.of("/home/bob"), "myapp")).isEqualTo(Path.of("/home/bob/myapp"));
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
        assertThat(root.workspace().modules()).containsExactly("core", "app");
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
}
