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
 * Each schema preset's example fixture (under its plugin's test resources) through the engine:
 * the preset fetches its tool from Central and its worker generates in the generate stage, the
 * module's own source compiles against the generated classes, a second build finds the generate
 * step cached, and an edit to the schema re-runs it.
 *
 * <p>Network test (Maven Central); the CAS persists under build/ so repeat runs are warm.
 */
@Tag("network")
class SchemaPresetExamplesTest {

    private static final Path ROOT = RepoRoot.find(SchemaPresetExamplesTest.class);

    @Test
    void the_avro_example_generates_compiles_hits_and_regenerates_on_a_schema_edit(@TempDir Path tmp) throws Exception {
        Path project = example(tmp, PluginJar.AVRO, "plugins/avro");
        Path cache = TestCaches.dir("avro-example-cache");
        lock(project, cache);

        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(first.success()).isTrue();
        assertThat(anyFile(project.resolve("target"), "UserCreated.class")).isTrue();
        assertThat(step(first, "generate-avro").status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(step(build(project, cache), "generate-avro").status()).isEqualTo(TaskStatus.SKIPPED);

        Path schema = project.resolve("src/main/avro/UserCreated.avsc");
        Files.writeString(
                schema,
                Files.readString(schema)
                        .replace(
                                "{\"name\": \"email\", \"type\": \"string\"}",
                                "{\"name\": \"email\", \"type\": \"string\"},\n   {\"name\": \"name\", \"type\": \"string\"}"));
        BuildPlanResult third = build(project, cache);
        assertThat(third.errors()).isEmpty();
        assertThat(step(third, "generate-avro").status()).isEqualTo(TaskStatus.SUCCESS);
    }

    @Test
    void the_jaxb_example_generates_compiles_and_hits(@TempDir Path tmp) throws Exception {
        Path project = example(tmp, PluginJar.JAXB, "plugins/jaxb");
        Path cache = TestCaches.dir("jaxb-example-cache");
        lock(project, cache);

        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(first.success()).isTrue();
        assertThat(anyFile(project.resolve("target"), "ObjectFactory.class")).isTrue();
        assertThat(step(first, "generate-jaxb").status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(step(build(project, cache), "generate-jaxb").status()).isEqualTo(TaskStatus.SKIPPED);
    }

    @Test
    void the_jooq_example_generates_from_the_migrations_and_regenerates_on_a_migration_edit(@TempDir Path tmp)
            throws Exception {
        Path project = example(tmp, PluginJar.JOOQ, "plugins/jooq");
        Path cache = TestCaches.dir("jooq-example-cache");
        lock(project, cache);

        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(first.success()).isTrue();
        assertThat(anyFile(project.resolve("target"), "Orders.class")).isTrue();
        assertThat(step(first, "generate-jooq").status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(step(build(project, cache), "generate-jooq").status()).isEqualTo(TaskStatus.SKIPPED);

        Path migration = project.resolve("src/main/resources/db/migration/V3__shipments.sql");
        Files.writeString(migration, "create table shipments (id bigint primary key, order_id bigint not null);\n");
        BuildPlanResult third = build(project, cache);
        assertThat(third.errors()).isEmpty();
        assertThat(step(third, "generate-jooq").status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(anyFile(project.resolve("target"), "Shipments.class")).isTrue();
    }

    /** The plugin's example fixture copied under {@code tmp}, its worker jar handed to the engine. */
    private static Path example(Path tmp, PluginJar worker, String module) throws IOException {
        workerJarFromWorkspace(PluginJar.GENERATOR, "plugins/generator");
        workerJarFromWorkspace(worker, module);
        Path project = tmp.resolve("example");
        PathUtil.copyTree(ROOT.resolve(module).resolve("src/test/resources/example"), project);
        return project;
    }

    private static void lock(Path project, Path cache) throws Exception {
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlanResult lock = LockPlans.lockBuildPlan(
                        project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(lock.errors()).isEmpty();
    }

    /** The worker jar this build produced, when the resident engine did not hand it over. */
    private static void workerJarFromWorkspace(PluginJar worker, String module) throws IOException {
        if (System.getProperty(worker.jarProperty()) != null) return;
        Path dir = ROOT.resolve(module);
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

    private static BuildPlanResult.StepReport step(BuildPlanResult result, String name) {
        Optional<BuildPlanResult.StepReport> step =
                result.steps().stream().filter(s -> s.name().contains(name)).findFirst();
        assertThat(step)
                .as("a " + name + " step in "
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
