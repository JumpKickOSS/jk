// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End to end, the test framework jk adds and aligns in a workspace: a member with no {@code
 * [test-dependencies]} keeps its Jupiter beside a sibling that declares some, and a member that
 * declares Jupiter 5 after a lock that resolved the implicit Jupiter 6 runs on Platform 1 after a
 * plain relock.
 */
// Out of the unit tier: resolves from Maven Central and forks test JVMs.
@Tag("integration")
class WorkspaceImplicitJupiterE2eTest {

    @Test
    void a_member_with_no_test_table_runs_its_tests_beside_a_sibling_that_declares_one(@TempDir Path tmp)
            throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path ws = workspace(tmp, "", "\n[test-dependencies]\nassertj = \"org.assertj:assertj-core:3.27.6\"\n");

        assertThat(lock(ws, cache)).as("workspace lock").isTrue();
        WorkspaceResult result = build(ws, cache);

        assertThat(result.errors()).isEmpty();
        assertThat(result.success())
                .as("a's Jupiter test compiles and runs though b declares a test table")
                .isTrue();
    }

    @Test
    void a_declared_jupiter_5_relocks_the_platform_off_the_implicit_jupiter_6(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path ws = workspace(tmp, "", "");
        assertThat(lock(ws, cache)).as("first lock, implicit Jupiter").isTrue();
        assertThat(version(ws, "org.junit.platform:junit-platform-engine:jar:")).startsWith("6.");

        String five = "\n[test-dependencies]\njunit-jupiter = \"org.junit.jupiter:junit-jupiter:5.12.2\"\n";
        Files.writeString(ws.resolve("a/jk.toml"), "name = \"a\"\n" + five);
        Files.writeString(ws.resolve("b/jk.toml"), "name = \"b\"\n" + five);
        assertThat(lock(ws, cache)).as("relock keeping pins").isTrue();

        for (String platform : List.of("engine", "commons", "launcher")) {
            assertThat(version(ws, "org.junit.platform:junit-platform-" + platform + ":jar:"))
                    .as("junit-platform-%s follows Jupiter 5.12.2", platform)
                    .isEqualTo("1.12.2");
        }
        assertThat(version(ws, "org.junit.jupiter:junit-jupiter-api:jar:")).isEqualTo("5.12.2");
        assertThat(build(ws, cache).success()).as("both members' tests run").isTrue();
    }

    private static boolean lock(Path ws, Path cache) throws Exception {
        return LockPlans.lockBuildPlan(ws, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run()
                .success();
    }

    private static WorkspaceResult build(Path ws, Path cache) {
        return WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false),
                new WorkspaceBuildListener() {});
    }

    /** The one version every row of {@code key} in the lock carries; fails when the rows disagree. */
    private static String version(Path ws, String key) throws Exception {
        Lockfile lock = LockfileReader.read(ws.resolve("jk-lock.toml"));
        List<String> versions = lock.artifacts().stream()
                .filter(a -> a.packageKey().equals(key))
                .map(Lockfile.Artifact::version)
                .distinct()
                .toList();
        assertThat(versions).as("%s rows", key).hasSize(1);
        return versions.getFirst();
    }

    /**
     * A root with no sources and members a and b, each with one Jupiter test unless its test table
     * names no Jupiter; {@code aTail} and {@code bTail} end their manifests.
     */
    private static Path workspace(Path tmp, String aTail, String bTail) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["a", "b"]
                """);
        for (String m : List.of("a", "b")) {
            Path dir = Files.createDirectories(ws.resolve(m));
            Files.writeString(dir.resolve("jk.toml"), "name = \"" + m + "\"\n" + (m.equals("a") ? aTail : bTail));
            Path main = Files.createDirectories(dir.resolve("src/main/java/" + m));
            Files.writeString(main.resolve("Lib.java"), "package " + m + ";\npublic final class Lib {}\n");
            // A member whose test table names no Jupiter has no Jupiter test of its own to compile.
            String tail = m.equals("a") ? aTail : bTail;
            if (tail.contains("[test-dependencies]") && !tail.contains("junit-jupiter")) continue;
            Path test = Files.createDirectories(dir.resolve("src/test/java/" + m));
            Files.writeString(test.resolve("LibTest.java"), """
                    package %s;
                    class LibTest {
                        @org.junit.jupiter.api.Test
                        void runs() {}
                    }
                    """.formatted(m));
        }
        return ws;
    }
}
