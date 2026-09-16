// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.Capture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk install} on a workspace root. The root of a workspace is a build unit that carries no
 * sources: it publishes nothing, and every member does. A failure anywhere on that path must reach
 * the caller — on both the human and the machine stream — because an install that exits non-zero
 * with an empty stdout and an empty stderr gives nobody anything to act on.
 */
@Tag("integration")
class InstallWorkspaceTest {

    @Test
    void install_publishes_every_member_and_not_the_coordinator_root(@TempDir Path tmp) throws Exception {
        Path cache = workspace(tmp, "public final class Lib { }");

        var streams = Capture.both(() -> assertThat(install(tmp, cache)).isEqualTo(0));

        // Stripped: the coordinate is styled per segment, so a raw substring would neither match
        // the member nor — worse — ever match the root, making the negative assertion vacuous.
        String out = TestAnsi.strip(streams.out());
        assertThat(out).contains("Installed ex:lib:1.0");
        assertThat(out)
                .as("the root packages nothing, so claiming it installed is a lie")
                .doesNotContain("Installed ex:ws");
        assertThat(JkStores.resolve("repos").resolve("jk-local/ex/lib/1.0/lib-1.0.jar"))
                .exists();
    }

    @Test
    void a_failing_install_names_the_module_instead_of_exiting_silently(@TempDir Path tmp) throws Exception {
        Path cache = workspace(tmp, "public final class Lib { this is not java }");

        int[] exit = {0};
        var streams = Capture.both(() -> exit[0] = install(tmp, cache));

        assertThat(exit[0]).isNotZero();
        // Stripped: the failure wedge styles the coordinate per segment, as build's does.
        assertThat(TestAnsi.strip(streams.out() + streams.err()))
                .as("a workspace install that fails must say why")
                .isNotBlank()
                .contains("ex:lib");
    }

    @Test
    void a_failing_install_emits_a_jsonl_finish_event(@TempDir Path tmp) throws Exception {
        Path cache = workspace(tmp, "public final class Lib { this is not java }");

        int[] exit = {0};
        String out = Capture.stdout(() -> exit[0] = install(tmp, cache, "--output", "jsonl"));

        assertThat(exit[0]).isNotZero();
        assertThat(out)
                .as("a machine-readable format that prints nothing on failure is unusable")
                .contains("\"type\":\"workspace-finish\"")
                .contains("\"success\":false");
    }

    @Test
    void a_member_s_declared_test_env_name_reaches_its_test_jvm(@TempDir Path tmp) throws Exception {
        // `JK_WEB_JS_SKIP=1 jk install` must skip the JS tier as `jk build` does. A declared
        // [test] env name reaches the test JVM only on the request's client env — the engine is a
        // daemon — and install's request used to carry none. The jk.env seam stands in for the
        // shell: JK_REPO_* names ride ClientEnvForward, and the member declares the same name.
        Path cache = Path.of(SharedTestCache.arg());
        workspace(tmp, "public final class Lib { }");
        Files.writeString(tmp.resolve("lib/jk.toml"), """
                name = "lib"

                [m2]
                install = false

                [test-dependencies]
                junit-jupiter = "latest"

                [test]
                env = ["JK_REPO_PROBE"]
                """);
        Path test = tmp.resolve("lib/src/test/java/ex/LibTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, """
                package ex;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class LibTest {
                    @Test
                    void the_declared_name_came_from_the_shell_that_ran_jk() {
                        assertEquals("from-the-client", System.getenv("JK_REPO_PROBE"));
                    }
                }
                """);
        assertThat(run("lock", "-C", tmp.toString(), "--cache-dir", cache.toString()))
                .as("the fixture's lock resolves JUnit")
                .isEqualTo(0);

        System.setProperty("jk.env.JK_REPO_PROBE", "from-the-client");
        int[] exit = {0};
        Capture.Streams streams;
        try {
            streams = Capture.both(() -> exit[0] = install(tmp, cache));
        } finally {
            System.clearProperty("jk.env.JK_REPO_PROBE");
        }

        assertThat(exit[0])
                .as("the member's test reads the forwarded name\n%s", TestAnsi.strip(streams.out() + streams.err()))
                .isEqualTo(0);
        assertThat(TestAnsi.strip(streams.out())).contains("Installed ex:lib:1.0");
    }

    private static int install(Path ws, Path cache, String... extra) {
        String[] base = {
            "install",
            "-C",
            ws.toString(),
            "--cache-dir",
            cache.toString(),
            "--state-dir",
            ws.resolve("state").toString(),
            "--bin-dir",
            ws.resolve("bin").toString(),
            "--lib-dir",
            ws.resolve("lib").toString(),
            "--m2-dir",
            ws.resolve("m2").toString()
        };
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return run(args);
    }

    /**
     * A sourceless workspace root plus one member holding {@code libBody}. Returns the cache dir.
     * The root gets a build-logic directory: that — not its manifest — is what makes a coordinator
     * root a build unit of its own, and so the shape whose install plan has no jar to publish.
     */
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
