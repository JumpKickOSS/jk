// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.tui.RichText;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdeChromeTest {

    @AfterEach
    void beginCommand() {
        CliOutput.beginCommand(false);
    }

    @Test
    void renderLines_single_ide_wedge_with_newest_details_on_top() {
        var buf = new ByteArrayOutputStream();
        try (IdeChrome chrome = IdeChrome.start(stream(buf), false, "Sync")) {
            chrome.phase(IdeChrome.phaseReady("JetBrains IDEA", "hello-http"));
            chrome.addDetails(List.of(
                    RichText.plain("Registered the jk-graalvm-25 JDK"),
                    RichText.plain("Generated 79 project files in .idea")));
            chrome.phase(IdeChrome.phaseReady("VS Code", "hello-http"));
            chrome.addDetails(List.of(RichText.plain("Generated 7 project files for redhat.java")));

            List<String> lines =
                    chrome.renderLines(0).stream().map(TestAnsi::strip).toList();
            assertThat(lines.getFirst()).contains("IDE").contains("VS Code: The hello-http project is ready");
            assertThat(lines.getFirst())
                    .doesNotContain("Sync")
                    .doesNotContain("Code  ")
                    .doesNotContain("IDEA  ");
            String tree = String.join("\n", lines);
            int vscode = tree.indexOf("Generated 7 project files for redhat.java");
            int ideaJdk = tree.indexOf("Registered the jk-graalvm-25 JDK");
            int ideaFiles = tree.indexOf("Generated 79 project files in .idea");
            assertThat(vscode).isGreaterThan(0);
            assertThat(ideaJdk).isGreaterThan(vscode);
            assertThat(ideaFiles).isGreaterThan(ideaJdk);
        }
    }

    @Test
    void succeed_prints_one_ide_settle_with_styled_project_name() {
        var buf = new ByteArrayOutputStream();
        try (IdeChrome chrome = IdeChrome.start(stream(buf), false, "Sync")) {
            chrome.phase("BSP: Wrote hello-http/.bsp/jk.json");
            chrome.succeed(IdeChrome.projectReady("hello-http"));
        }
        String visible = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
        assertThat(visible).contains("IDE");
        assertThat(visible).contains("The hello-http project is ready");
        // One command chip — not Sync / IDEA / Code / BSP as sibling wedges.
        assertThat(visible).doesNotContain(" > Sync");
        assertThat(visible).doesNotContain("IDEA >");
        assertThat(visible).doesNotContain("Code >");
        assertThat(visible).doesNotContain("BSP >");
    }

    @Test
    void note_survives_as_committed_follow_up() {
        var buf = new ByteArrayOutputStream();
        try (IdeChrome chrome = IdeChrome.start(stream(buf), false, "Sync")) {
            chrome.note(IdeChrome.restartNote());
            chrome.succeed(IdeChrome.projectReady("widget"));
        }
        String raw = Capture.lf(buf.toString(StandardCharsets.UTF_8));
        String visible = TestAnsi.strip(raw);
        assertThat(visible).contains("Note").contains("restart your IDE");
        assertThat(visible).contains("The widget project is ready");
        // Envelope is one leading blank — note() must not add a second.
        assertThat(raw).startsWith("\n");
        assertThat(raw).doesNotStartWith("\n\n");
    }

    @Test
    void projectReady_uses_bright_cyan_bold_markup() {
        assertThat(IdeChrome.projectReady("hello-http").plainText()).isEqualTo("The hello-http project is ready");
        assertThat(IdeChrome.phaseReady("JetBrains IDEA", "hello-http"))
                .isEqualTo("JetBrains IDEA: The hello-http project is ready");
    }

    @Test
    void bspWrote_is_workspace_relative(@TempDir Path tmp) throws IOException {
        Path ws = tmp.resolve("hello-http");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("jk.toml"), """
                name = "hello-http"
                """);
        Path bsp = ws.resolve(".bsp").resolve("jk.json");
        String visible = TestAnsi.strip(IdeChrome.bspWrote(bsp, ws).render());
        assertThat(visible).contains(".bsp/jk.json");
        assertThat(visible).doesNotContain(ws.toAbsolutePath().toString());
        assertThat(visible).startsWith("BSP: Wrote ");
    }

    private static PrintStream stream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }
}
