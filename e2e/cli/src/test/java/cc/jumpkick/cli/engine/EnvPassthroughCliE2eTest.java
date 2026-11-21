// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.Processes;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk test} hands a test JVM the variables of the shell that ran it, over the real wire: the
 * first run's test JVM asks the engine, which asks this client on the job's connection; the next
 * request ships the value up front because the engine named the variable in its project summary.
 * The result holds only while the variable has the value it was read with (the owner's case).
 * Nothing a test JVM is handed lands in any file a run writes, nor stays in the engine's
 * heap once the build is done.
 */
@Tag("integration")
@IsolatedState
class EnvPassthroughCliE2eTest {

    private static final String MUST = "MUST_BE_TRUE";
    private static final String CANARY = "JK_CANARY_ENV";

    @AfterEach
    void clear() {
        System.clearProperty("jk.env." + MUST);
        System.clearProperty("jk.env." + CANARY);
        ProjectInfos.forget();
    }

    @Test
    void a_green_result_holds_only_while_the_variable_it_read_keeps_its_value(@TempDir Path tmp) throws Exception {
        Path ws = project(tmp, """
                @Test
                void mustBeTrue() {
                    assertEquals("true", System.getenv("MUST_BE_TRUE"));
                }
                """);
        String cache = tmp.resolve("cache").toString();
        assertThat(run("lock", "-C", ws.toString(), "--cache-dir", cache)).isZero();

        assertThat(test(ws, cache, "true")).as("MUST_BE_TRUE=true runs green").contains("\"success\":true");
        assertThat(test(ws, cache, "true")).as("the same value again: replayed").contains("\"upToDate\":true");
        assertThat(explain(ws, cache, null))
                .as("jk explain names the variable that went, as the forecast's reason")
                .contains("MUST_BE_TRUE unset")
                .doesNotContain("jk changed");
        assertThat(test(ws, cache, null)).as("unset: not honoured, fails").contains("\"success\":false");
        assertThat(lastRun())
                .as("the build's own miss reason names it too")
                .contains("ran: inputs changed")
                .contains("MUST_BE_TRUE");
        assertThat(test(ws, cache, "banana")).as("banana: not honoured, fails").contains("\"success\":false");
        assertThat(test(ws, cache, "false")).as("false: not honoured, fails").contains("\"success\":false");
        assertThat(test(ws, cache, "true"))
                .as("true again: the green result holds")
                .contains("\"upToDate\":true");

        // A value no run had: no red record under that key, so the forecast says what moved.
        String explain = explain(ws, cache, "pear-never-run");
        assertThat(explain)
                .as("the forecast runs the suite again under a value it never read, and never says the value")
                .contains("\"step\":\"run-tests\",\"status\":\"run\"")
                .contains("MUST_BE_TRUE changed")
                .doesNotContain("pear-never-run");
    }

    /**
     * A canary value planted in the shell, read by name, listed with the names and handed to a
     * program a test starts, appears in no file the runs leave — the project's outputs and reports,
     * the engine's state, logs, history and cache — and not in the engine's live heap after.
     */
    @Test
    void a_value_the_test_jvm_is_handed_leaves_no_trace(@TempDir Path tmp) throws Exception {
        String canary = "canary-" + UUID.randomUUID().toString().replace("-", "");
        String child = Os.isWindows()
                ? "new ProcessBuilder(\"cmd\", \"/c\", \"if defined JK_CANARY_ENV (exit 0) else (exit 3)\")"
                : "new ProcessBuilder(\"sh\", \"-c\", \"test -n \\\"$JK_CANARY_ENV\\\"\")";
        Path ws = project(tmp, """
                @Test
                void readsTheCanary() throws Exception {
                    assertEquals(39, System.getenv("JK_CANARY_ENV").length());
                    assertEquals(true, System.getenv().containsKey("JK_CANARY_ENV"));
                    assertEquals(true, System.getenv().keySet().size() > 0);
                    assertEquals(0, CHILD.start().waitFor());
                }

                @Test
                void failsAfterReading() {
                    assertEquals("not it", System.getenv("JK_CANARY_ENV").substring(0, 6));
                }
                """.replace("CHILD", child));
        String cache = tmp.resolve("cache").toString();
        assertThat(run("lock", "-C", ws.toString(), "--cache-dir", cache)).isZero();
        System.setProperty("jk.env." + CANARY, canary);
        List<String> said = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ProjectInfos.forget();
            said.add(Capture.stdout(() -> run("test", "-O", "jsonl", "-C", ws.toString(), "--cache-dir", cache)));
        }
        said.add(Capture.stdout(() -> run("results", "-C", ws.toString())));
        said.add(Capture.stdout(() -> run("results", "-a", "-C", ws.toString())));
        for (String out : said) assertThat(out).as("what the commands said").doesNotContain(canary);

        byte[] needle = canary.getBytes(StandardCharsets.UTF_8);
        List<Path> roots = List.of(ws, tmp.resolve("cache"), JkDirs.home(), JkDirs.state(), JkDirs.cache());
        List<Path> found = new ArrayList<>();
        for (Path root : roots) found.addAll(holding(root, needle));
        assertThat(found).as("files holding the canary").isEmpty();

        long pid = EngineProcessControl.readPidFile(EnginePaths.current().pid());
        if (pid > 0) {
            Path dump = tmp.resolve("engine.hprof");
            Path jcmd = Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "jcmd.exe" : "jcmd");
            Processes.Captured dumped = Processes.capture(
                    List.of(jcmd.toString(), Long.toString(pid), "GC.heap_dump", dump.toString()),
                    Duration.ofSeconds(120));
            assertThat(dumped.timedOut()).isFalse();
            if (Files.isRegularFile(dump)) {
                assertThat(contains(Files.readAllBytes(dump), needle))
                        .as("the engine's live heap after the builds")
                        .isFalse();
                Files.delete(dump);
            }
        }
    }

    private static String explain(Path ws, String cache, @Nullable String value) {
        ProjectInfos.forget();
        if (value == null) System.clearProperty("jk.env." + MUST);
        else System.setProperty("jk.env." + MUST, value);
        return Capture.stdout(() -> run("explain", "-O", "jsonl", "-C", ws.toString(), "--cache-dir", cache));
    }

    /** {@code jk history show} of the last build, as a person reads it. */
    private static String lastRun() {
        String list = Capture.stdout(() -> run("history", "list", "-O", "jsonl", "-n", "1"));
        Matcher id = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(list);
        assertThat(id.find()).as("a build on record: " + list).isTrue();
        return Capture.stdout(() -> run("history", "show", id.group(1)));
    }

    private static String test(Path ws, String cache, @Nullable String value) {
        ProjectInfos.forget();
        if (value == null) System.clearProperty("jk.env." + MUST);
        else System.setProperty("jk.env." + MUST, value);
        return Capture.stdout(() -> run("test", "-O", "jsonl", "-C", ws.toString(), "--cache-dir", cache));
    }

    private static Path project(Path tmp, String body) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "envs"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter = "6.1.3"

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.writeString(
                Files.createDirectories(ws.resolve("src/test/java/demo")).resolve("EnvTest.java"),
                """
                package demo;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class EnvTest {
                BODY}
                """.replace("BODY", body.indent(4)));
        Files.createDirectories(ws.resolve("src/main/java/demo"));
        Files.writeString(ws.resolve("src/main/java/demo/M.java"), "package demo; public class M {}\n");
        return ws;
    }

    /** The regular files under {@code root} whose bytes contain {@code needle}. */
    private static List<Path> holding(Path root, byte[] needle) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        // The fetched artifacts, JDKs and tools are downloads, never written from a run.
        List<Path> skip = List.of(JkDirs.store(), JkDirs.jdks(), JkDirs.tools());
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : (Iterable<Path>) walk::iterator) {
                if (skip.stream().anyMatch(f::startsWith)) continue;
                if (!Files.isRegularFile(f) || Files.size(f) > (256L << 20)) continue;
                try {
                    if (contains(Files.readAllBytes(f), needle)) out.add(f);
                } catch (IOException unreadable) {
                    // a socket or a file another process holds open exclusively
                }
            }
        }
        return out;
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
