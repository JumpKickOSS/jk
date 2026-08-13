// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for the {@code jk ide} front door: by default it generates <b>both</b> IntelliJ and VS
 * Code configs; {@code --idea}/{@code --vscode} narrow it to one.
 */
@Tag("integration")
class IdeCommandTest {

    @Test
    void generates_both_ides_by_default(@TempDir Path tmp) throws IOException {
        Path ws = simpleProject(tmp);
        Path jdks = tmp.resolve("jdks");
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");
        Path ideConfig = ideConfig(tmp);

        assertThat(Jk.execute(new String[] {
                    "ide",
                    "-C",
                    ws.toString(),
                    "--cache-dir",
                    tmp.resolve("cache").toString(),
                    "--jdks-dir",
                    jdks.toString(),
                    "--ide-config-dir",
                    ideConfig.toString()
                }))
                .isEqualTo(0);

        assertThat(ws.resolve(".idea/misc.xml")).exists();
        assertThat(ws.resolve("widget.iml")).exists();
        assertThat(ws.resolve(".vscode/settings.json")).exists();
        assertThat(ws.resolve(".classpath")).exists();
        assertBspConnection(ws);
    }

    @Test
    void human_output_is_a_single_ide_wedge_with_relative_bsp_path(@TempDir Path tmp) throws IOException {
        Path ws = simpleProject(tmp);
        Path jdks = tmp.resolve("jdks");
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");
        Path ideConfig = ideConfig(tmp);

        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        var prev = System.out;
        try {
            System.setOut(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
            assertThat(Jk.execute(new String[] {
                        "ide",
                        "-C",
                        ws.toString(),
                        "--cache-dir",
                        tmp.resolve("cache").toString(),
                        "--jdks-dir",
                        jdks.toString(),
                        "--ide-config-dir",
                        ideConfig.toString()
                    }))
                    .isEqualTo(0);
        } finally {
            System.setOut(prev);
        }
        String visible = cc.jumpkick.cli.TestAnsi.strip(buf.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(visible).contains("IDE");
        assertThat(visible).contains("The widget project is ready");
        // One command chip — not a Sync / IDEA / Code / BSP stack.
        assertThat(visible).doesNotContain(" > Sync");
        assertThat(visible).doesNotContain("IDEA >");
        assertThat(visible).doesNotContain("Code >");
        assertThat(visible).doesNotContain("BSP >");
        assertThat(visible).doesNotContain(ws.toAbsolutePath().toString());
        assertThat(visible).contains("Note");
    }

    @Test
    void idea_flag_generates_only_intellij(@TempDir Path tmp) throws IOException {
        Path ws = simpleProject(tmp);
        Path jdks = tmp.resolve("jdks");
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");
        Path ideConfig = ideConfig(tmp);

        assertThat(Jk.execute(new String[] {
                    "ide",
                    "--idea",
                    "-C",
                    ws.toString(),
                    "--cache-dir",
                    tmp.resolve("cache").toString(),
                    "--jdks-dir",
                    jdks.toString(),
                    "--ide-config-dir",
                    ideConfig.toString()
                }))
                .isEqualTo(0);

        assertThat(ws.resolve(".idea/misc.xml")).exists();
        assertThat(Files.exists(ws.resolve(".vscode"))).isFalse();
        assertThat(Files.exists(ws.resolve(".classpath"))).isFalse();
        assertBspConnection(ws);
    }

    @Test
    void vscode_flag_generates_only_vscode(@TempDir Path tmp) throws IOException {
        Path ws = simpleProject(tmp);
        Path jdks = tmp.resolve("jdks");
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");

        assertThat(Jk.execute(new String[] {
                    "ide",
                    "--vscode",
                    "-C",
                    ws.toString(),
                    "--cache-dir",
                    tmp.resolve("cache").toString(),
                    "--jdks-dir",
                    jdks.toString()
                }))
                .isEqualTo(0);

        assertThat(ws.resolve(".vscode/settings.json")).exists();
        assertThat(Files.exists(ws.resolve(".idea"))).isFalse();
        assertBspConnection(ws);
    }

    @Test
    void print_model_emits_wire_json_without_writing_idea_files(@TempDir Path tmp) throws IOException {
        Path ws = simpleProject(tmp);
        Path jdks = tmp.resolve("jdks");
        fakeJdk(jdks, "temurin-25.0.3", "25.0.3");
        Path cache = tmp.resolve("cache");

        // Capture stdout from Jk.execute — use process-level capture via System.out redirect.
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        var prev = System.out;
        try {
            System.setOut(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
            assertThat(Jk.execute(new String[] {
                        "ide",
                        "--print-model",
                        "-C",
                        ws.toString(),
                        "--cache-dir",
                        cache.toString(),
                        "--jdks-dir",
                        jdks.toString()
                    }))
                    .isEqualTo(0);
        } finally {
            System.setOut(prev);
        }
        String out = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(out).contains("\"type\":\"ide-model-ack\"");
        assertThat(out).contains("\"wsRoot\"");
        assertThat(out).contains("\"moduleDirs\"");
        // No file generation on the print-model path.
        assertThat(Files.exists(ws.resolve(".idea"))).isFalse();
        assertThat(Files.exists(ws.resolve("widget.iml"))).isFalse();
    }

    private static void assertBspConnection(Path ws) throws IOException {
        Path bsp = ws.resolve(".bsp/jk.json");
        assertThat(bsp).exists();
        String json = Files.readString(bsp);
        assertThat(json).contains("\"bspVersion\"");
        assertThat(json).contains("\"bsp\"");
        assertThat(json).contains("\"serve\"");
    }

    private static Path simpleProject(Path tmp) throws IOException {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws.resolve("src/main/java/example"));
        Files.writeString(ws.resolve("src/main/java/example/Hello.java"), "package example;\npublic class Hello {}\n");
        Files.writeString(ws.resolve("jk.toml"), """
                [project]
                group = "dev.example"
                name = "widget"
                version = "0.1.0"
                java = 25
                """);
        return ws;
    }

    private static Path ideConfig(Path tmp) throws IOException {
        Path ideConfig = tmp.resolve("ideconfig");
        Files.createDirectories(ideConfig.resolve("JetBrains/IntelliJIdea2025.1/options"));
        return ideConfig;
    }

    private static void fakeJdk(Path jdksRoot, String name, String version) throws IOException {
        Path home = jdksRoot.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(home.resolve("bin").resolve("javac"), "#!/fake");
        Files.writeString(
                home.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"" + version + "\"\n");
    }
}
