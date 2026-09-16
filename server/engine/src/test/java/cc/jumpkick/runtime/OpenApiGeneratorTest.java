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
 * The manual's {@code examples/openapi-spring} through the engine: {@code [openapi]} fetches
 * openapi-generator-cli from Central, the generator worker runs it in the generate stage, and the
 * Boot module compiles against the generated interface. A second build finds the generate step
 * cached; a spec edit re-runs it and the compiler follows.
 *
 * <p>Network test (Maven Central); the CAS persists under build/ so repeat runs are warm.
 */
@Tag("network")
class OpenApiGeneratorTest {

    private static final Path EXAMPLE =
            RepoRoot.find(OpenApiGeneratorTest.class).resolve("docs/user/examples/openapi-spring");

    @Test
    void the_example_generates_compiles_hits_and_regenerates_on_a_spec_edit(@TempDir Path tmp) throws Exception {
        workerJarFromWorkspace(PluginJar.GENERATOR, "plugins/generator");
        workerJarFromWorkspace(PluginJar.OPENAPI, "plugins/openapi");
        Path project = tmp.resolve("openapi-spring");
        PathUtil.copyTree(EXAMPLE, project);
        Files.deleteIfExists(project.resolve("jk-lock.toml"));
        Path cache = TestCaches.dir("openapi-generator-cache");

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        assertThat(build.pluginConfig("openapi")).isPresent();
        BuildPlanResult lock = LockPlans.lockBuildPlan(
                        project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(lock.errors()).isEmpty();

        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(first.success()).isTrue();
        assertThat(anyFile(project.resolve("target"), "GreetingsApi.class"))
                .as("the generated interface compiled")
                .isTrue();
        assertThat(anyFile(project.resolve("target"), "GreetingController.class"))
                .as("the module's own source referencing the generated interface compiled")
                .isTrue();
        assertThat(generateStep(first).status()).isEqualTo(TaskStatus.SUCCESS);

        BuildPlanResult second = build(project, cache);
        assertThat(second.success()).isTrue();
        assertThat(generateStep(second).status())
                .as("an unchanged spec is a cache hit for the generate step")
                .isEqualTo(TaskStatus.SKIPPED);

        Path spec = project.resolve("api/openapi.yaml");
        Files.writeString(
                spec,
                Files.readString(spec)
                        .replace(
                                "        message:\n          type: string",
                                "        message:\n          type: string\n        language:\n          type: string"));
        BuildPlanResult third = build(project, cache);
        assertThat(third.errors()).isEmpty();
        assertThat(third.success()).isTrue();
        assertThat(generateStep(third).status())
                .as("a spec edit re-runs the generate step")
                .isEqualTo(TaskStatus.SUCCESS);
    }

    /**
     * The worker jar this build produced, when the resident engine planning jk's own tests did not
     * hand it over: that engine learns a new first-party worker only once it is installed, so until
     * then the sibling module's jar stands in — the same jar the engine would name.
     */
    private static void workerJarFromWorkspace(PluginJar worker, String module) throws IOException {
        if (System.getProperty(worker.jarProperty()) != null) return;
        Path dir = RepoRoot.find(OpenApiGeneratorTest.class).resolve(module);
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

    private static BuildPlanResult.StepReport generateStep(BuildPlanResult result) {
        Optional<BuildPlanResult.StepReport> step = result.steps().stream()
                .filter(s -> s.name().contains("generate-openapi"))
                .findFirst();
        assertThat(step)
                .as("a generate-openapi step in "
                        + result.steps().stream().map(s -> s.name()).toList())
                .isPresent();
        return step.get();
    }

    private static boolean anyFile(Path root, String nameFragment) throws IOException {
        boolean[] found = new boolean[1];
        PathUtil.forEachRegularFile(root, (file, attrs) -> {
            if (file.getFileName().toString().contains(nameFragment)) found[0] = true;
        });
        return found[0];
    }
}
