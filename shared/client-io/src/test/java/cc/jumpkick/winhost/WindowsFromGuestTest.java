// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A WSL guest offers, and installs, JumpKick for Windows. */
class WindowsFromGuestTest {

    @TempDir
    Path tmp;

    /** A guest's interop: {@code cmd.exe} expands {@link #vars}, {@code wslpath} maps {@code C:\} under tmp. */
    private final Map<String, String> vars = new HashMap<>();

    private final List<List<String>> asked = new ArrayList<>();

    private @Nullable String interop(List<String> argv) {
        asked.add(argv);
        if (argv.getFirst().equals("cmd.exe")) {
            String expr = argv.getLast(); // echo %NAME%
            String name = expr.substring(expr.indexOf('%') + 1, expr.lastIndexOf('%'));
            String v = vars.get(name);
            return (v == null ? "%" + name + "%" : v) + "\r\n";
        }
        if (argv.getFirst().equals("wslpath")) {
            String win = argv.getLast();
            if (!win.startsWith("C:\\")) return null;
            return tmp.resolve("c").resolve(win.substring(3).replace('\\', '/')) + "\n";
        }
        return null;
    }

    private Path c(String rel) {
        return tmp.resolve("c").resolve(rel);
    }

    @Test
    void the_row_is_one_environment_row_answered_on_its_own() {
        SetupChange row = WindowsFromGuest.row();
        assertThat(row.id()).isEqualTo(WindowsFromGuest.ROW);
        assertThat(row.title()).isEqualTo("JumpKick for Windows");
        assertThat(row.kind()).isEqualTo(SetupChange.Kind.ENVIRONMENT);
        assertThat(row.kind().elevated()).as("no administrator prompt").isFalse();
        assertThat(row.why()).contains("dashboard").contains("notifications").contains("front door");
    }

    @Test
    void windows_lacks_jumpkick_when_its_home_has_no_jk_exe() throws IOException {
        vars.put("USERPROFILE", "C:\\Users\\ann");
        assertThat(WindowsFromGuest.windowsHome(this::interop)).isEqualTo("C:\\Users\\ann\\.jk");
        assertThat(WindowsFromGuest.missing(this::interop)).isTrue();
        assertThat(WindowsFromGuest.windowsJk(this::interop)).isNull();

        Path jk = c("Users/ann/.jk/bin/jk.exe");
        Files.createDirectories(jk.getParent());
        Files.writeString(jk, "MZ");
        assertThat(WindowsFromGuest.missing(this::interop)).isFalse();
        assertThat(WindowsFromGuest.windowsJk(this::interop)).isEqualTo(jk);
    }

    @Test
    void a_jk_home_set_on_windows_is_where_it_looks() {
        vars.put("USERPROFILE", "C:\\Users\\ann");
        vars.put("JK_HOME", "C:\\jk");
        assertThat(WindowsFromGuest.windowsHome(this::interop)).isEqualTo("C:\\jk");
    }

    @Test
    void a_guest_that_cannot_ask_windows_never_says_it_is_missing() {
        assertThat(WindowsFromGuest.missing(argv -> null)).isFalse();
        // Interop answers, but names no home.
        assertThat(WindowsFromGuest.missing(this::interop)).isFalse();
    }

    @Test
    void a_release_installs_its_own_version_and_a_snapshot_the_newest_at_or_below_it() throws IOException {
        URI releases = URI.create("https://example.test/releases");
        WindowsFromGuest.Source release = Objects.requireNonNull(
                WindowsFromGuest.source("0.52.0", "C:\\dev\\dist", this::interop, v -> true, () -> "0.52.0", releases));
        assertThat(release).isInstanceOf(WindowsFromGuest.Source.Release.class);
        assertThat(release.version()).isEqualTo("0.52.0");
        assertThat(((WindowsFromGuest.Source.Release) release).installer())
                .isEqualTo(URI.create("https://example.test/install.ps1"));

        WindowsFromGuest.Source snapshot = Objects.requireNonNull(WindowsFromGuest.source(
                "0.52.2-SNAPSHOT", null, this::interop, v -> v.equals("0.52.1"), () -> "0.52.1", releases));
        assertThat(snapshot.version()).isEqualTo("0.52.1");
    }

    @Test
    void only_a_snapshot_installs_the_dev_dist_and_only_a_folder_that_holds_one() throws IOException {
        URI releases = URI.create("https://example.test/releases");
        Files.createDirectories(c("dev/dist"));
        Files.writeString(c("dev/dist/jk.exe"), "MZ");
        assertThat(WindowsFromGuest.source(
                        "0.52.2-SNAPSHOT", "C:\\dev\\dist\\", this::interop, v -> true, () -> "0.52.1", releases))
                .as("no install.ps1 beside it: the release")
                .isInstanceOf(WindowsFromGuest.Source.Release.class);

        Files.writeString(c("dev/dist/install.ps1"), "# installer");
        WindowsFromGuest.Source dist = WindowsFromGuest.source(
                "0.52.2-SNAPSHOT", "C:\\dev\\dist\\", this::interop, v -> true, () -> "0.52.1", releases);
        assertThat(dist).isEqualTo(new WindowsFromGuest.Source.Dist("0.52.2-SNAPSHOT", "C:\\dev\\dist"));
    }

    @Test
    void the_script_carries_every_value_as_a_literal_never_as_script_text() {
        String script = WindowsFromGuest.script(
                new WindowsFromGuest.Source.Release("0.52.1", URI.create("https://example.test/install.ps1"), "why"));
        assertThat(script).contains("$env:JK_NONINTERACTIVE = '1'");
        assertThat(script).contains("$env:JK_VERSION = " + literal("0.52.1"));
        assertThat(script).contains(literal("https://example.test/install.ps1")).contains("| Invoke-Expression");
        assertThat(script).doesNotContain("https://");

        String dist = WindowsFromGuest.script(new WindowsFromGuest.Source.Dist("0.52.2-SNAPSHOT", "C:\\d'x"));
        assertThat(dist)
                .contains("& " + literal("C:\\d'x\\install.ps1") + " " + literal("C:\\d'x\\jk.exe"))
                .doesNotContain("JK_VERSION")
                .doesNotContain("C:\\d'x");
    }

    @Test
    void an_install_runs_the_installer_in_windows_powershell_then_makes_sure_the_engine_runs() throws IOException {
        vars.put("USERPROFILE", "C:\\Users\\ann");
        Path jk = c("Users/ann/.jk/bin/jk.exe");
        List<List<String>> ran = new ArrayList<>();
        List<String> progress = new ArrayList<>();
        MachineSetup.Result r = WindowsFromGuest.install(
                new WindowsFromGuest.Source.Release("0.52.1", URI.create("https://example.test/install.ps1"), "why"),
                "/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe",
                (argv, wait, line) -> {
                    ran.add(argv);
                    if (ran.size() == 1) {
                        line.accept("\u001B[32m+\u001B[0m Installing JumpKick");
                        line.accept("");
                        try {
                            Files.createDirectories(jk.getParent());
                            Files.writeString(jk, "MZ");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    return 0;
                },
                this::interop,
                progress::add);

        assertThat(r.ok()).isTrue();
        assertThat(r.change()).isEqualTo(WindowsFromGuest.ROW);
        assertThat(r.line()).contains("installed JumpKick for Windows 0.52.1").contains("8910");
        assertThat(ran.get(0).getFirst()).isEqualTo("/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe");
        assertThat(ran.get(0)).contains("-NoProfile", "-NonInteractive", "-EncodedCommand");
        String script = new String(Base64.getDecoder().decode(ran.get(0).getLast()), StandardCharsets.UTF_16LE);
        assertThat(script)
                .isEqualTo(WindowsFromGuest.script(new WindowsFromGuest.Source.Release(
                        "0.52.1", URI.create("https://example.test/install.ps1"), "why")));
        assertThat(ran.get(1)).containsExactly(jk.toString(), "engine", "start", "-u", "-x");
        assertThat(progress).contains("+ Installing JumpKick").doesNotContain("");
    }

    @Test
    void a_failed_install_says_why_with_the_installers_last_line() {
        vars.put("USERPROFILE", "C:\\Users\\ann");
        MachineSetup.Result r = WindowsFromGuest.install(
                new WindowsFromGuest.Source.Dist("0.52.2-SNAPSHOT", "C:\\dist"),
                "powershell.exe",
                (argv, wait, line) -> {
                    line.accept("x could not download the JDK");
                    return 1;
                },
                this::interop,
                l -> {});
        assertThat(r.ok()).isFalse();
        assertThat(r.line()).contains("the installer exited 1").contains("could not download the JDK");

        MachineSetup.Result silent = WindowsFromGuest.install(
                new WindowsFromGuest.Source.Dist("0.52.2-SNAPSHOT", "C:\\dist"),
                "powershell.exe",
                (argv, wait, line) -> 0,
                this::interop,
                l -> {});
        assertThat(silent.ok()).as("exit 0 with no jk.exe left behind").isFalse();
        assertThat(silent.line()).contains("left no jk.exe");
    }

    @Test
    void an_answer_is_remembered_beside_the_rest_of_the_memo() throws IOException {
        Path memo = tmp.resolve("state/windows-host.toml");
        assertThat(WindowsFromGuest.answered(memo)).isFalse();
        new SetupMemo(null, 0, 7, List.of("D:\\src"), List.of(), List.of(), Map.of(), 0, 0, List.of("other"))
                .write(memo);

        WindowsFromGuest.remember(memo, false, 1234);

        SetupMemo m = SetupMemo.read(memo);
        assertThat(WindowsFromGuest.answered(memo)).isTrue();
        assertThat(m.answeredRows()).containsExactly("other", WindowsFromGuest.ROW);
        assertThat(m.answer()).isEqualTo(SetupMemo.Answer.DECLINED);
        assertThat(m.verified()).containsExactly("D:\\src");
        assertThat(m.checkedAt()).isEqualTo(7);
        WindowsFromGuest.remember(memo, true, 2345);
        assertThat(SetupMemo.read(memo).answeredRows()).containsExactly("other", WindowsFromGuest.ROW);
    }

    @Test
    void output_is_read_back_a_whole_line_at_a_time() throws IOException {
        Path out = Files.writeString(tmp.resolve("out"), "one\r\ntw");
        List<String> lines = new ArrayList<>();
        int at = WindowsFromGuest.emit(out, 0, lines::add, false);
        assertThat(lines).containsExactly("one");
        Files.writeString(out, "one\r\ntwo\nthr");
        at = WindowsFromGuest.emit(out, at, lines::add, true);
        assertThat(lines).containsExactly("one", "two", "thr");
        assertThat(at).isEqualTo(Files.readString(out).length());
    }

    private static String literal(String s) {
        return "(D '" + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "')";
    }
}
