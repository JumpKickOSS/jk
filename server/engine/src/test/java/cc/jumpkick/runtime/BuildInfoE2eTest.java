// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.TaskForecast;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [build-info]} end to end: the jar carries {@code git.properties} naming the checkout's
 * commit; a second build on the same commit skips the step and the jar; a new commit rewrites the
 * file and repackages without recompiling, and {@code jk explain} forecasts exactly that.
 */
@Tag("integration")
class BuildInfoE2eTest {

    @TempDir
    Path tmp;

    @Test
    void the_jar_carries_the_commit_and_only_a_new_commit_repackages() throws Exception {
        Path ws = workspace(tmp);
        RevCommit first = commit(ws, "one");
        Path cache = TestCaches.dir("build-info-cache");
        lock(ws, cache);
        BuildLayout lib = BuildLayout.of(ws, ws.resolve("lib"), JkBuildParser.parse(ws.resolve("lib/jk.toml")));

        Steps build1 = build(ws, cache, "first", Set.of(ws.resolve("lib")));
        assertThat(build1.status("lib", TaskNames.BUILD_INFO)).isEqualTo(TaskStatus.SUCCESS);
        Properties git = gitProperties(lib.mainJar());
        assertThat(git.getProperty("git.commit.id")).isEqualTo(first.getName());
        assertThat(git.getProperty("git.branch")).isEqualTo("main");
        assertThat(git.getProperty("git.build.version")).isEqualTo("1.0.0");
        byte[] jarOnce = Files.readAllBytes(lib.mainJar());

        Steps build2 = build(ws, cache, "second", Set.of(ws.resolve("lib")));
        assertThat(build2.status("lib", TaskNames.BUILD_INFO)).isEqualTo(TaskStatus.SKIPPED);
        assertThat(build2.status("lib", TaskNames.PACKAGE_JAR)).isEqualTo(TaskStatus.SKIPPED);
        assertThat(Files.readAllBytes(lib.mainJar())).as("one commit, one jar").isEqualTo(jarOnce);
        assertThat(forecast(ws, cache, "lib", TaskNames.BUILD_INFO)).isEmpty();

        RevCommit second = commit(ws, "two");
        Optional<TaskForecast.Task> forecast = forecast(ws, cache, "lib", TaskNames.BUILD_INFO);
        assertThat(forecast).isPresent();
        assertThat(forecast.get().status()).isEqualTo(TaskForecast.Status.RUN);
        Steps build3 = build(ws, cache, "third", null);
        assertThat(build3.status("lib", TaskNames.BUILD_INFO)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(build3.status("lib", TaskNames.PACKAGE_JAR)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(build3.status("lib", TaskNames.COMPILE_JAVA))
                .as("a commit is not a source change")
                .isEqualTo(TaskStatus.SKIPPED);
        assertThat(gitProperties(lib.mainJar()).getProperty("git.commit.id")).isEqualTo(second.getName());
    }

    private static Properties gitProperties(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("git.properties");
            assertThat(entry).as("git.properties in " + jar).isNotNull();
            Properties p = new Properties();
            p.load(new StringReader(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)));
            return p;
        }
    }

    private static Optional<TaskForecast.Task> forecast(Path ws, Path cache, String module, String step)
            throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        List<TaskForecast.Module> plan = TaskForecaster.of(graph, JkStores.cacheCas(cache), actionCache, cache, true);
        return plan.stream()
                .filter(x -> x.dir().getFileName().toString().equals(module))
                .flatMap(m -> m.steps().stream())
                .filter(s -> s.name().equals(step))
                .findFirst();
    }

    private static Steps build(Path ws, Path cache, String what, @Nullable Set<Path> dirty) {
        Steps steps = new Steps();
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, dirty, false, false), steps);
        assertThat(result.errors()).as("errors, build " + what).isEmpty();
        assertThat(result.success()).as("success, build " + what).isTrue();
        return steps;
    }

    private static void lock(Path ws, Path cache) throws Exception {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        Files.copy(ws.resolve("jk-lock.toml"), ws.resolve("lib/jk-lock.toml"));
    }

    private static RevCommit commit(Path repo, String message) throws Exception {
        try (Git git = Files.isDirectory(repo.resolve(".git"))
                ? Git.open(repo.toFile())
                : Git.init()
                        .setDirectory(repo.toFile())
                        .setInitialBranch("main")
                        .call()) {
            Files.writeString(repo.resolve("NOTES-" + message + ".md"), message);
            git.add().addFilepattern(".").call();
            return git.commit()
                    .setMessage(message)
                    .setAuthor("t", "t@e")
                    .setCommitter("t", "t@e")
                    .call();
        }
    }

    /** Per module and step: final status. */
    private static final class Steps implements WorkspaceBuildListener {
        private final Map<String, TaskStatus> statusByModuleStep = new ConcurrentHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    statusByModuleStep.put(name + "/" + step, status);
                }
            };
        }

        @Nullable
        TaskStatus status(String module, String step) {
            return statusByModuleStep.get(module + "/" + step);
        }
    }

    /** A one-library workspace inside its own git checkout; the build outputs are ignored by git. */
    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve(".gitignore"), "target/\n");
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib"]
                """);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25
                javadoc = false

                [build-info]

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path src = Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(src.resolve("One.java"), """
                package com.example;

                /** One. */
                public final class One {
                    private One() {}

                    /** The answer. */
                    public static int one() {
                        return 1;
                    }
                }
                """);
        return ws;
    }
}
