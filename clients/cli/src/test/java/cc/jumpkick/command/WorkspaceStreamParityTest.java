// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * One {@code --output jsonl} envelope for every build-kind verb. {@code jk build}, {@code test},
 * {@code compile}, {@code image} and {@code native} are the same operation with a different phase
 * selection — the verb says which stages the build includes, not what kind of job it is — so an
 * agent that can parse one can parse all of them. {@code jk run} is the outlier only at its tail:
 * the workspace pre-build streams, and {@code workspace-finish} is end-of-stream because the
 * exec'd program owns stdout from there.
 *
 * <p>Counted, not merely present: a second {@code workspace-start} or a missing terminal is exactly
 * what makes a stream unparseable, and {@code jk native} shipped a {@code workspace-progress} with
 * neither for long enough to prove that presence checks do not catch it.
 *
 * <p>{@code image} and {@code native} are absent here because they need a container daemon and a
 * GraalVM respectively; their arms are the same two lines as {@code compile}'s, and
 * {@link WorkspaceRunViewOwnerTest#only_the_owner_emits_the_workspace_vocabulary} is what stops
 * either re-growing a private copy of the vocabulary.
 */
@Tag("integration")
class WorkspaceStreamParityTest {

    @ParameterizedTest
    @ValueSource(strings = {"build", "test", "compile"})
    void every_build_kind_verb_opens_and_terminates_the_stream_exactly_once(String verb, @TempDir Path tmp)
            throws Exception {
        Path cache = workspace(tmp);

        String out = Capture.stdout(
                () -> assertThat(run(verb, "-C", tmp.toString(), "--cache-dir", cache.toString(), "--output", "jsonl"))
                        .as("%s failed on a workspace that compiles", verb)
                        .isEqualTo(0));

        assertThat(countType(out, "workspace-start"))
                .as("%s --output jsonl must open the stream exactly once", verb)
                .isEqualTo(1);
        assertThat(countType(out, "workspace-finish"))
                .as("%s --output jsonl must terminate the stream exactly once", verb)
                .isEqualTo(1);
        // Pairing, not a count: how many modules this fixture has is a fixture detail, but a
        // module the stream opens and never closes is a parser hanging on a module that finished.
        int starts = countType(out, "module-start");
        assertThat(starts).as("%s must announce the modules it entered", verb).isPositive();
        assertThat(countType(out, "module-finish"))
                .as("%s must close every module it opened", verb)
                .isEqualTo(starts);
        assertThat(lastLine(out))
                .as("%s: a parser that stops at the terminal must not lose events after it", verb)
                .contains("\"type\":\"workspace-finish\"");
    }

    /**
     * A failing module still terminates the stream. A machine format that prints nothing — or an
     * unclosed envelope — on failure is unusable precisely when it is needed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"build", "test", "compile"})
    void a_failing_module_still_terminates_the_stream(String verb, @TempDir Path tmp) throws Exception {
        Path cache = workspace(tmp, "public final class Lib { this is not java }");

        int[] exit = {0};
        String out = Capture.stdout(
                () -> exit[0] = run(verb, "-C", tmp.toString(), "--cache-dir", cache.toString(), "--output", "jsonl"));

        assertThat(exit[0])
                .as("%s must fail on source that does not compile", verb)
                .isNotZero();
        assertThat(countType(out, "workspace-finish")).isEqualTo(1);
        assertThat(out).contains("\"success\":false");
    }

    /**
     * The graph-error arm terminates too. A dependency that cannot resolve never reaches a module
     * listener, so nothing but the terminal reports it — and this is the arm that returns the
     * engine's own exit code rather than a flat failure.
     */
    @Test
    void an_unresolvable_dependency_still_terminates_the_stream(@TempDir Path tmp) throws Exception {
        Path cache = workspace(tmp);
        Files.writeString(tmp.resolve("lib").resolve("jk.toml"), """
                name = "lib"

                [dependencies]
                nope = { group = "com.example.absent", name = "nothing", version = "9.9.9" }

                [m2]
                install = false
                """);

        int[] exit = {0};
        String out = Capture.stdout(() -> exit[0] =
                run("build", "-C", tmp.toString(), "--cache-dir", cache.toString(), "--offline", "--output", "jsonl"));

        assertThat(exit[0]).as("an unresolvable graph is a failure").isNotZero();
        assertThat(countType(out, "workspace-finish"))
                .as("the only line that reports a graph error is the terminal")
                .isEqualTo(1);
        assertThat(out).contains("\"success\":false");
    }

    /**
     * {@code jk run} streams the pre-build and stops: the terminal is the <em>last</em> line jk
     * writes to its own stdout, which is what lets a parser treat it as end-of-stream and hand the
     * rest to the program. The child's own output is not asserted here — it inherits the real file
     * descriptor, so an in-process capture never sees it.
     */
    @Test
    void run_terminates_the_prebuild_stream_before_the_exec(@TempDir Path tmp) throws Exception {
        Path cache = workspace(tmp);
        Path lib = tmp.resolve("lib");
        Files.writeString(
                lib.resolve("src/main/java/ex/Main.java"),
                "package ex; public final class Main { public static void main(String[] a) {"
                        + " System.out.println(\"ran\"); } }\n");
        Files.writeString(lib.resolve("jk.toml"), """
                name = "lib"
                main-class = "ex.Main"

                [m2]
                install = false
                """);

        int[] exit = {-1};
        String out = Capture.stdout(
                () -> exit[0] = run("run", "-C", lib.toString(), "--cache-dir", cache.toString(), "--output", "jsonl"));

        assertThat(exit[0]).as("the program ran and exited cleanly").isEqualTo(0);
        assertThat(countType(out, "workspace-start")).isEqualTo(1);
        assertThat(countType(out, "workspace-finish"))
                .as("the pre-build's terminal is what tells a parser jk is done writing")
                .isEqualTo(1);
        assertThat(lastLine(out))
                .as("jk writes no stdout of its own after the terminal — the exec owns the stream")
                .contains("\"type\":\"workspace-finish\"");
    }

    private static String lastLine(String stream) {
        return stream.trim().lines().reduce((first, last) -> last).orElse("");
    }

    private static int countType(String stream, String type) {
        String needle = "\"type\":\"" + type + "\"";
        int n = 0;
        for (int i = stream.indexOf(needle); i >= 0; i = stream.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    private static Path workspace(Path tmp) throws Exception {
        return workspace(tmp, "public final class Lib { public int two() { return 2; } }");
    }

    /** A sourceless workspace root plus one member holding {@code libBody}. Returns the cache dir. */
    private static Path workspace(Path tmp, String libBody) throws Exception {
        Files.createDirectories(tmp.resolve(".jk"));
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "ex"
                name = "ws"
                version = "1.0"
                java = 25

                [workspace]
                modules = ["lib"]
                """);
        Path lib = tmp.resolve("lib");
        Files.createDirectories(lib.resolve("src/main/java/ex"));
        Files.writeString(lib.resolve("jk.toml"), """
                name = "lib"

                [m2]
                install = false
                """);
        Files.writeString(lib.resolve("src/main/java/ex/Lib.java"), "package ex; " + libBody + "\n");
        return tmp.resolve("cache");
    }
}
