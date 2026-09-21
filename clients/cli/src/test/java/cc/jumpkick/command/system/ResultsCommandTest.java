// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.cli.CommandDispatch;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.util.MarkdownReports;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultsCommandTest {

    @TempDir
    Path tmp;

    private String prevBuilds;

    @BeforeEach
    void isolateBuilds() {
        prevBuilds = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty("jk.env.JK_STATE_DIR", tmp.toString());
    }

    @AfterEach
    void restoreBuilds() {
        if (prevBuilds == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevBuilds);
    }

    @Test
    void command_is_registered() {
        assertThat(CommandDispatch.commands().stream().map(c -> c.name())).contains("results");
    }

    @Test
    void prints_latest_journal_markdown() throws Exception {
        Path proj = project();
        ProjectBuilds.RunDir older = open(proj);
        Files.writeString(older.resultsFile(), "# older\n");
        Files.writeString(older.detailsFile(), "{\"n\":1}\n");
        ProjectBuilds.RunDir newer = open(proj);
        Files.writeString(newer.resultsFile(), "# jk results — FAIL\ncompile boom\n");
        Files.writeString(newer.detailsFile(), "{\"n\":2}\n");

        String out = Capture.stdout(() -> Jk.execute("-C", proj.toString(), "results"));
        assertThat(out).isEqualTo("# jk results — FAIL\ncompile boom\n");
        assertThat(out).doesNotContain("older");
    }

    @Test
    void details_prints_latest_jsonl() throws Exception {
        Path proj = project();
        ProjectBuilds.RunDir older = open(proj);
        Files.writeString(older.resultsFile(), "# older\n");
        Files.writeString(older.detailsFile(), "{\"n\":1}\n");
        ProjectBuilds.RunDir newer = open(proj);
        Files.writeString(newer.resultsFile(), "# newer\n");
        Files.writeString(newer.detailsFile(), "{\"type\":\"error\",\"message\":\"boom\"}\n");

        String out = Capture.stdout(() -> Jk.execute("-C", proj.toString(), "results", "--details"));
        assertThat(out).isEqualTo("{\"type\":\"error\",\"message\":\"boom\"}\n");
        assertThat(out).doesNotContain("\"n\":1");
    }

    /**
     * The file on disk leads with a UTF-8 BOM so a Windows {@code cat} decodes it; a terminal is
     * not that reader. Printing the mark would put a visible {@code ï»¿} in front of the heading
     * under PowerShell's ANSI codepage, and hand a leading U+FEFF to anything parsing the pipe.
     */
    @Test
    void the_files_byte_order_mark_is_not_printed() throws Exception {
        Path proj = project();
        ProjectBuilds.RunDir run = open(proj);
        MarkdownReports.write(run.resultsFile(), "# jk results — OK\n");
        Files.writeString(run.detailsFile(), "{\"n\":1}\n");
        assertThat(Files.readString(run.resultsFile(), StandardCharsets.UTF_8))
                .as("the file itself keeps the mark")
                .startsWith(MarkdownReports.BOM);

        String out = Capture.stdout(() -> Jk.execute("-C", proj.toString(), "results"));
        assertThat(out).isEqualTo("# jk results — OK\n");
        assertThat(out).doesNotContain(MarkdownReports.BOM);
    }

    /** The fallback copy under {@code target/} is written the same way, and read back the same way. */
    @Test
    void the_target_copys_mark_is_not_printed_either() throws Exception {
        Path proj = project();
        Files.createDirectories(proj.resolve("target"));
        MarkdownReports.write(proj.resolve("target").resolve("jk-results.md"), "# from target\n");

        String out = Capture.stdout(() -> Jk.execute("-C", proj.toString(), "results"));
        assertThat(out).isEqualTo("# from target\n");
    }

    @Test
    void skips_newer_run_that_has_no_report_yet() throws Exception {
        Path proj = project();
        ProjectBuilds.RunDir finished = open(proj);
        Files.writeString(finished.resultsFile(), "# finished\n");
        Files.writeString(finished.detailsFile(), "{\"n\":1}\n");
        ProjectBuilds.RunDir inFlight = open(proj);
        Files.writeString(inFlight.detailsFile(), "{\"type\":\"task-start\"}\n");

        String md = Capture.stdout(() -> Jk.execute("-C", proj.toString(), "results"));
        assertThat(md).isEqualTo("# finished\n");
        String details = Capture.stdout(() -> Jk.execute("-C", proj.toString(), "results", "--details"));
        assertThat(details).isEqualTo("{\"type\":\"task-start\"}\n");
    }

    @Test
    void falls_back_to_target_copy_when_journal_has_no_markdown() throws Exception {
        Path proj = project();
        Files.createDirectories(proj.resolve("target"));
        Files.writeString(proj.resolve("target").resolve("jk-results.md"), "# from target\n");

        String out = Capture.stdout(() -> Jk.execute("-C", proj.toString(), "results"));
        assertThat(out).isEqualTo("# from target\n");
    }

    @Test
    void missing_report_is_a_failure() throws Exception {
        Path proj = project();
        Captured cap = capture(() -> Jk.execute("-C", proj.toString(), "results"));
        assertThat(cap.code).isEqualTo(1);
        assertThat(cap.out).isEmpty();
        assertThat(TestAnsi.strip(cap.err)).contains("no jk-results.md");
    }

    @Test
    void missing_details_is_a_failure() throws Exception {
        Path proj = project();
        Files.createDirectories(proj.resolve("target"));
        Files.writeString(proj.resolve("target").resolve("jk-results.md"), "# from target\n");
        Captured cap = capture(() -> Jk.execute("-C", proj.toString(), "results", "--details"));
        assertThat(cap.code).isEqualTo(1);
        assertThat(cap.out).isEmpty();
        assertThat(TestAnsi.strip(cap.err)).contains("no details.jsonl");
    }

    @Test
    void projectRoot_walks_up_to_the_manifest() throws Exception {
        Path proj = project();
        Path nested =
                Files.createDirectories(proj.resolve("src").resolve("main").resolve("java"));
        assertThat(ResultsCommand.projectRoot(nested))
                .isEqualTo(proj.toAbsolutePath().normalize());
    }

    @Test
    void projectRoot_uses_the_workspace_root_for_a_member() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                [workspace]
                modules = ["app"]
                """);
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group = "g"
                name = "app"
                version = "0.1.0"
                """);
        assertThat(ResultsCommand.projectRoot(app))
                .isEqualTo(ws.toAbsolutePath().normalize());
    }

    /**
     * Two worktrees of one repository share the project id, its home and its build numbers. Each
     * {@code jk results} prints the newest run recorded for its own checkout, never the sibling's,
     * and the identity file lists both checkouts.
     */
    @Test
    void prints_this_checkouts_run_not_the_sibling_worktrees() throws Exception {
        Path a = project("wt-a");
        Path b = project("wt-b");
        Files.writeString(open(a).resultsFile(), "# a #1\n");
        Files.writeString(open(b).resultsFile(), "# b #2\n");
        ProjectBuilds.RunDir third = open(a);
        Files.writeString(third.resultsFile(), "# a #3\n");
        assertThat(third.buildNumber()).as("one sequence per id").isEqualTo(3);

        assertThat(Capture.stdout(() -> Jk.execute("-C", a.toString(), "results")))
                .isEqualTo("# a #3\n");
        assertThat(Capture.stdout(() -> Jk.execute("-C", b.toString(), "results")))
                .isEqualTo("# b #2\n");

        var identity = ProjectIdentity.IdentityFile.read(third.projectHome()).orElseThrow();
        assertThat(identity.checkouts().stream().map(ProjectIdentity.Checkout::path))
                .containsExactly(
                        a.toAbsolutePath().normalize(), b.toAbsolutePath().normalize());
    }

    private Path project() throws Exception {
        return project("app");
    }

    private Path project(String name) throws Exception {
        Path proj = Files.createDirectories(tmp.resolve(name));
        Files.writeString(proj.resolve("jk.toml"), """
                id = "results-cmd"
                group = "g"
                name = "app"
                version = "0.1.0"
                """);
        return proj;
    }

    /** A run as the engine journals it: {@code record.json} names the checkout it ran in. */
    private ProjectBuilds.RunDir open(Path proj) throws Exception {
        ProjectBuilds.RunDir run = ProjectBuilds.openRun(tmp.resolve("builds"), "g:app", proj);
        Files.writeString(
                run.recordFile(),
                MiniJson.write(
                        Map.of("dir", proj.toAbsolutePath().normalize().toString(), "buildNumber", run.buildNumber())));
        return run;
    }

    private static Captured capture(IntSupplier body) {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            int code = body.getAsInt();
            return new Captured(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    private record Captured(int code, String out, String err) {}
}
