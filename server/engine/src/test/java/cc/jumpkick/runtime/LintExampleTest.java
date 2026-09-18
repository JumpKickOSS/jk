// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.testing.TestCaches;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manual's {@code examples/lint-checkstyle} through the engine: {@code [lint]} fetches
 * Checkstyle from Central and its worker runs it after compile. A clean module passes and the
 * second build finds the lint step cached; a violation written into the source re-runs the step,
 * fails the build, and the finding is a diagnostic naming the file, the line and the rule.
 *
 * <p>Network test (Maven Central); the CAS persists under build/ so repeat runs are warm.
 */
@Tag("network")
class LintExampleTest {

    private static final Path EXAMPLE =
            RepoRoot.find(LintExampleTest.class).resolve("docs/user/examples/lint-checkstyle");

    @Test
    void the_example_lints_clean_hits_the_cache_and_reports_a_violation_by_rule(@TempDir Path tmp) throws Exception {
        workerJarFromWorkspace(PluginJar.LINT, "plugins/lint");
        Path project = tmp.resolve("lint-checkstyle");
        PathUtil.copyTree(EXAMPLE, project);
        Files.deleteIfExists(project.resolve("jk-lock.toml"));
        Path cache = TestCaches.dir("lint-checkstyle-cache");

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        assertThat(build.pluginConfig("lint")).isPresent();
        BuildPlanResult lock = LockPlans.lockBuildPlan(
                        project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(lock.errors()).isEmpty();

        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(first.success()).isTrue();
        assertThat(lintStep(first).status()).isEqualTo(TaskStatus.SUCCESS);

        BuildPlanResult second = build(project, cache);
        assertThat(second.success()).isTrue();
        assertThat(lintStep(second).status())
                .as("an unchanged module is a cache hit for the lint step")
                .isEqualTo(TaskStatus.SKIPPED);

        Path sample = project.resolve("src/main/java/demo/Sample.java");
        Files.writeString(
                sample,
                Files.readString(sample)
                        .replace(
                                "        if (status < BAD_REQUEST) {\n            return Outcome.OK;\n        }",
                                "        if (status < 400) return Outcome.OK;"));
        BuildPlanResult third = build(project, cache);
        assertThat(third.success())
                .as("an error-severity finding fails the build")
                .isFalse();
        assertThat(lintStep(third).status()).isEqualTo(TaskStatus.FAIL);
        assertThat(third.errors())
                .as("the finding names the file, the line and the rule")
                .anySatisfy(d -> assertThat(d.message()).contains("Sample.java").contains("[MagicNumber]"))
                .anySatisfy(d -> assertThat(d.message()).contains("[NeedBraces]"));
        assertThat(BuildLayout.of(project, build).mainJar())
                .as("the compile itself succeeded; lint failed after it")
                .exists();
    }

    /** The worker jar this build produced, when the resident engine did not hand it over. */
    private static void workerJarFromWorkspace(PluginJar worker, String module) throws IOException {
        if (System.getProperty(worker.jarProperty()) != null) return;
        Path dir = RepoRoot.find(LintExampleTest.class).resolve(module);
        Path jar =
                BuildLayout.of(dir, JkBuildParser.parse(dir.resolve("jk.toml"))).mainJar();
        assertThat(jar).as(worker.artifactId() + " built by this workspace").isRegularFile();
        System.setProperty(worker.jarProperty(), jar.toAbsolutePath().toString());
    }

    private static BuildPlanResult build(Path project, Path cache) throws Exception {
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                0,
                null,
                null,
                true,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        return BuildPlanner.fullPlan(in).run();
    }

    private static BuildPlanResult.StepReport lintStep(BuildPlanResult result) {
        Optional<BuildPlanResult.StepReport> step = result.steps().stream()
                .filter(s -> s.name().contains("lint-checkstyle"))
                .findFirst();
        assertThat(step)
                .as("a lint-checkstyle step in "
                        + result.steps().stream().map(s -> s.name()).toList())
                .isPresent();
        return step.get();
    }
}
