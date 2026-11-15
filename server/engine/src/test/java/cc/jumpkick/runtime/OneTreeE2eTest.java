// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every build but a release is a snapshot: {@code jk build}, {@code jk test} and {@code jk install}
 * send the same session, so they name and stamp one tree and the next of them runs nothing. A
 * release names and stamps the declared version, and neither reuses what the other packaged.
 */
@Tag("integration")
class OneTreeE2eTest {

    @Test
    void build_test_and_snapshot_plan_one_tree_and_a_release_shares_no_jar_with_it(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("one-tree-cache");
        Path ws = workspace(tmp);
        BuildPlan lock = LockPlans.lockBuildPlan(ws, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();
        String lockBefore = Files.readString(ws.resolve("jk-lock.toml"));

        // Audited, explained, built again: the snapshot tree every verb but release builds.
        SecondBuild.assertSecondBuildRunsNothing(ws, cache);
        Path snapshotJar = ws.resolve("a/target/a-1.0.0-SNAPSHOT.jar");
        assertThat(stamp(snapshotJar)).isEqualTo("1.0.0-SNAPSHOT");
        assertThat(ws.resolve("a/target/a-1.0.0.jar")).doesNotExist();

        Steps release = build(ws, cache, true);
        assertThat(release.status("a", TaskNames.PACKAGE_JAR))
                .as("a release packages its own jar, not the snapshot's")
                .isEqualTo(TaskStatus.SUCCESS);
        assertThat(stamp(ws.resolve("a/target/a-1.0.0.jar"))).isEqualTo("1.0.0");

        Steps after = build(ws, cache, false);
        assertThat(after.ran())
                .as("the snapshot build after a release: its jars are still its own")
                .doesNotContainKeys("a/" + TaskNames.PACKAGE_JAR, "b/" + TaskNames.RUN_TESTS);
        assertThat(stamp(snapshotJar)).isEqualTo("1.0.0-SNAPSHOT");
        assertThat(Files.readString(ws.resolve("jk-lock.toml")))
                .as("the lock records declared versions whichever verb built")
                .isEqualTo(lockBefore);
    }

    /** The {@code version} line of the filtered resource inside {@code jar}. */
    private static String stamp(Path jar) throws IOException {
        assertThat(jar).exists();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("a/version.properties");
            assertThat(entry).as("the filtered resource in %s", jar).isNotNull();
            String body = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            return body.strip().substring("version=".length());
        }
    }

    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["a", "b"]
                """);
        Path a = module(ws, "a", "");
        Files.writeString(
                a.resolve("jk.toml"),
                Files.readString(a.resolve("jk.toml")) + "\n[resources]\nfiltered = [\"src/filter/resources\"]\n");
        Path res = Files.createDirectories(a.resolve("src/filter/resources/a"));
        Files.writeString(res.resolve("version.properties"), "version=${project.version}\n");
        Path b = module(ws, "b", "a = { workspace = true }");
        Path tests = Files.createDirectories(b.resolve("src/test/java/demo/b"));
        Files.writeString(tests.resolve("BTest.java"), """
                package demo.b;

                import static org.junit.jupiter.api.Assertions.assertNotNull;

                import org.junit.jupiter.api.Test;

                class BTest {
                    @Test
                    void sees_its_sibling() {
                        assertNotNull(demo.a.M.class.getClassLoader().getResource("a/version.properties"));
                    }
                }
                """);
        return ws;
    }

    private static Path module(Path ws, String name, String dependency) throws IOException {
        Path m = Files.createDirectories(ws.resolve(name));
        Files.writeString(m.resolve("jk.toml"), """
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                java    = 25
                javadoc = false

                [dependencies]
                %s

                [test-dependencies]
                junit-jupiter = "6.1.3"

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """.formatted(name, dependency));
        Path main = Files.createDirectories(m.resolve("src/main/java/demo/" + name));
        // A nonce: content no earlier run stored, so a key one build takes differently cannot hit.
        Files.writeString(
                main.resolve("M.java"),
                "package demo." + name + "; public class M { static final String NONCE = \"" + UUID.randomUUID()
                        + "\"; }\n");
        return m;
    }

    private static Steps build(Path ws, Path cache, boolean release) throws Exception {
        Steps steps = new Steps();
        Session session = Session.defaults().withCacheDir(cache).withRelease(release);
        WorkspaceResult result = SessionContext.where(
                session,
                () -> WorkspaceExecute.buildWorkspace(
                        new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false), steps));
        assertThat(result.success()).as("build: " + result.errors()).isTrue();
        return steps;
    }

    /** Each module's step statuses, keyed {@code module/step}. */
    private static final class Steps implements WorkspaceBuildListener {
        private final Map<String, TaskStatus> statuses = new TreeMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    synchronized (Steps.this) {
                        statuses.put(name + "/" + step, status);
                    }
                }
            };
        }

        synchronized @Nullable TaskStatus status(String module, String step) {
            return statuses.get(module + "/" + step);
        }

        /** The steps that did work, keyed {@code module/step}. */
        synchronized Map<String, TaskStatus> ran() {
            Map<String, TaskStatus> out = new TreeMap<>();
            statuses.forEach((k, v) -> {
                if (v == TaskStatus.SUCCESS) out.put(k, v);
            });
            return out;
        }
    }
}
