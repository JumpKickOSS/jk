// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.repo.ArtifactLocator;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.workspace.ExecPlans;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.wire.protocol.ExecPlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: a standalone project whose dependency is built from source, a {@code path} target
 * or a tag of a git repository, locks, builds and runs from clean. The lock's fetch stores the jar
 * under the {@code file:} repository the target was built into, and the build reads it there.
 */
@Tag("integration")
class SourceDependencyBuildE2eTest {

    @Test
    void a_path_dependency_locks_builds_and_runs(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("source-dependency-cache");
        writeLibrary(tmp.resolve("lib"), "from the path");
        Path app = writeApp(tmp.resolve("app"), "lib = { path = \"../lib\" }");

        assertLocksBuildsAndRuns(app, cache, "from the path");
    }

    @Test
    void a_git_dependency_on_a_bare_repository_locks_builds_and_runs(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("source-dependency-cache");
        Path work = writeLibrary(tmp.resolve("lib"), "from git");
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            git.add().addFilepattern(".").call();
            var commit = git.commit()
                    .setMessage("v1")
                    .setAuthor("t", "t@e")
                    .setCommitter("t", "t@e")
                    .call();
            git.tag().setName("v1.0.0").setObjectId(commit).setAnnotated(false).call();
        }
        Path bare = tmp.resolve("lib.git");
        Git.cloneRepository()
                .setURI(work.toUri().toString())
                .setDirectory(bare.toFile())
                .setBare(true)
                .call()
                .close();
        Path app = writeApp(
                tmp.resolve("app"), "lib = { git = \"" + bare.toUri() + "\", tag = \"v1.0.0\" }");

        assertLocksBuildsAndRuns(app, cache, "from git");
    }

    private static void assertLocksBuildsAndRuns(Path app, Path cache, String expected) throws Exception {
        LockPlans.LockScope scope = LockPlans.lockScope(app);
        BuildPlan lockPlan = LockPlans.lockBuildPlan(
                scope.lockDir(), scope.effective(), cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lockPlan.run().success()).as("lock").isTrue();

        Lockfile lock = LockfileReader.read(LockPaths.lockFile(app));
        Lockfile.Artifact row = lock.artifacts().stream()
                .filter(a -> a.name().startsWith("t:lib:"))
                .findFirst()
                .orElseThrow();
        assertThat(row.source()).startsWith(RepoArtifactResolver.GIT_SOURCE_PREFIX);
        assertThat(new ArtifactLocator(JkStores.store()).locate(row))
                .as("the locked jar is where the lock's fetch stored it")
                .isPresent();

        WorkspaceResult built = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(app, cache, null, 0, null, true, false, 1, null, false, false),
                WorkspaceBuildListener.NOOP);
        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();

        ExecPlan run = ExecPlans.execPlan(app, cache, "run", null, null);
        assertThat(run.error()).isNull();
        Process p = new ProcessBuilder(run.argv())
                .directory(app.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        assertThat(out).contains(expected);
    }

    private static Path writeLibrary(Path dir, String greeting) throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/main/java/t/lib"));
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "t"
                name    = "lib"
                version = "1.0.0"
                java    = 25
                javadoc = false
                """);
        Files.writeString(src.resolve("Greeter.java"), """
                package t.lib;

                public final class Greeter {
                    private Greeter() {}

                    public static String greet() {
                        return "%s";
                    }
                }
                """.formatted(greeting));
        return dir;
    }

    private static Path writeApp(Path dir, String dependency) throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/main/java/t/app"));
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "t"
                name    = "app"
                version = "1.0.0"
                java    = 25
                javadoc = false

                [application]
                main = "t.app.Main"

                [dependencies]
                %s
                """.formatted(dependency));
        Files.writeString(src.resolve("Main.java"), """
                package t.app;

                public final class Main {
                    private Main() {}

                    public static void main(String[] args) {
                        System.out.println(t.lib.Greeter.greet());
                    }
                }
                """);
        return dir;
    }
}
