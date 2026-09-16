// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Variants;
import cc.jumpkick.plugin.manifest.VariantApply;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.PluginDescriptorOps;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression: testOnly plans must construct valid. run-tests requires the plugin tasks
 * that are test-only or contribute to the test classpath, so those tasks (and the plugin tasks
 * they transitively require) must be in the plan even though the packaging-feeding rest stay
 * out; and appendDeclaredTails must not re-root a test/compile plan onto package tails that
 * reference the absent package-jar.
 */
class BuildPlannerTestOnlyPlanTest {

    private static final String MANIFEST = """
            [plugin]
            id      = "fake"
            table   = "fake"
            version = "1.0.0"

            [schema]
            enabled = { type = "bool", default = true }

            [code]
            protocol-prefix = "##FAKE:"
            """;

    @TempDir
    Path tmp;

    @Test
    void test_only_plan_keeps_test_classpath_plugin_tasks_and_their_requires() throws Exception {
        Path dir = pluginProject();
        // Mirror coreBuilder's parse: variant overlays fold into plugin configs BEFORE the
        // describe key is computed, so the seeded cache file must use the same effective build.
        JkBuild build = VariantApply.apply(
                        JkBuildParser.reparse(dir.resolve("jk.toml")), dir, Variants.Selection.parse(""), Map.of())
                .build();
        seedDescribeCache(
                dir,
                build,
                List.of(
                        // Normal packaging-feeding task — must stay out of test plans.
                        "{\"t\":\"task\",\"name\":\"dex\",\"inputs\":[\"classes\"],\"outputs\":[\"dex\"]}",
                        // Helper the test-classpath task depends on — must ride along.
                        "{\"t\":\"task\",\"name\":\"helper\",\"inputs\":[],\"outputs\":[\"h\"]}",
                        "{\"t\":\"task\",\"name\":\"test-config\",\"inputs\":[\"step:helper\"],"
                                + "\"outputs\":[\"tc\"],\"contributesTestClasspath\":[\"tc\"]}"));

        Set<String> testPlan = planNames(dir, true);
        assertThat(testPlan)
                .as("testOnly plan validates and keeps run-tests' plugin requires")
                .contains(TaskNames.RUN_TESTS, "plugin-test-config", "plugin-helper")
                .doesNotContain("plugin-dex", TaskNames.PACKAGE_JAR);

        Set<String> buildPlan = planNames(dir, false);
        assertThat(buildPlan)
                .as("full build plan still schedules every plugin task and packaging")
                .contains("plugin-dex", "plugin-helper", "plugin-test-config", TaskNames.PACKAGE_JAR);
    }

    @Test
    void declared_tails_do_not_apply_to_test_or_compile_plans() throws Exception {
        Path dir = tmp.resolve("app");
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(
                dir.resolve("src/main/java/ex/Main.java"),
                "package ex; class Main { public static void main(String[] a) {} }\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "app"
                version = "1.0"
                java = 25

                [application]
                main = "ex.Main"
                assembly = true
                """);
        Files.writeString(dir.resolve("jk-lock.toml"), "schema = 1\n");

        BuildPlanner.Inputs testOnly = inputs(dir, true, false);
        BuildPlan.Builder tb = BuildPlanner.coreBuilder(testOnly);
        PlannerTails.appendDeclaredTails(tb, testOnly);
        Set<String> testNames = tb.build().steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        assertThat(testNames)
                .as("test plan terminates at run-tests; no assembly tail, no package-jar")
                .contains(TaskNames.RUN_TESTS)
                .doesNotContain(TaskNames.PACKAGE_ASSEMBLY, TaskNames.PACKAGE_JAR);

        BuildPlanner.Inputs compileOnly = inputs(dir, false, true);
        BuildPlan.Builder cb = BuildPlanner.coreBuilder(compileOnly);
        PlannerTails.appendDeclaredTails(cb, compileOnly);
        Set<String> compileNames =
                cb.build().steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        assertThat(compileNames)
                .as("compile plan stops at the stamps and the resource copy; no assembly tail")
                .contains(TaskNames.WRITE_STAMP, TaskNames.COPY_RESOURCES)
                .doesNotContain(TaskNames.PACKAGE_ASSEMBLY, TaskNames.PACKAGE_JAR);
    }

    @Test
    void test_only_plan_keeps_freshness_stamps(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.createDirectories(dir.resolve("src/main/kotlin"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "m"
                version = "1.0"
                jdk = 25
                java = 25
                kotlin = "2.3.21"
                """);
        Set<String> names = BuildPlanner.coreBuilder(inputs(dir, true, false)).build().steps().stream()
                .map(s -> s.name())
                .collect(Collectors.toSet());
        assertThat(names)
                .as("edit→test loop keeps compile incrementality: stamps survive the run-tests prune")
                .contains(TaskNames.RUN_TESTS, TaskNames.WRITE_STAMP, TaskNames.WRITE_STAMP_KOTLIN)
                .doesNotContain(TaskNames.PACKAGE_JAR);
    }

    @Test
    void mixed_compile_only_plan_keeps_both_stamps_and_assembler(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.createDirectories(dir.resolve("src/main/kotlin"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "m"
                version = "1.0"
                jdk = 25
                java = 25
                kotlin = "2.3.21"
                """);
        Set<String> names = BuildPlanner.coreBuilder(inputs(dir, false, true)).build().steps().stream()
                .map(s -> s.name())
                .collect(Collectors.toSet());
        assertThat(names)
                .as("mixed jk compile keeps every language's stamp and the classes assembler")
                .contains(
                        TaskNames.WRITE_STAMP,
                        TaskNames.WRITE_STAMP_KOTLIN,
                        TaskNames.ASSEMBLE_CLASSES,
                        BuildPlanner.COMPILE_JOIN)
                .doesNotContain(TaskNames.PACKAGE_JAR, TaskNames.RUN_TESTS);
    }

    /**
     * The resource copy is part of the classes tree a dependent compiles against, so a compile-only
     * plan carries it beside the stamp; the two are independent leaves the join keeps.
     */
    @Test
    void single_language_compile_only_joins_the_stamp_and_the_resource_copy(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "m"
                version = "1.0"
                java = 25
                """);
        BuildPlan plan = BuildPlanner.coreBuilder(inputs(dir, false, true)).build();
        Set<String> names = plan.steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        assertThat(names)
                .as("single-language jk compile: stamp and resource copy, nothing that reads a jar")
                .contains(TaskNames.WRITE_STAMP, TaskNames.COPY_RESOURCES, BuildPlanner.COMPILE_JOIN)
                .doesNotContain(TaskNames.PACKAGE_JAR, TaskNames.COMPILE_TEST, TaskNames.RUN_TESTS);
        Task join = plan.steps().stream()
                .filter(s -> s.name().equals(BuildPlanner.COMPILE_JOIN))
                .findFirst()
                .orElseThrow();
        assertThat(join.requires()).containsExactlyInAnyOrder(TaskNames.WRITE_STAMP, TaskNames.COPY_RESOURCES);
    }

    /**
     * Regression: compile-test's classpath includes classes/main, which copy-resources
     * writes — without this edge the two are racing siblings under build-logic-after-compile and
     * the fingerprint intermittently walks a half-copied dir (`jk build -r` on resource modules).
     */
    @Test
    void compile_test_requires_copy_resources(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.createDirectories(dir.resolve("src/test/java"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "m"
                version = "1.0"
                java = 25
                """);
        BuildPlanner.Inputs in = inputs(dir, false, false);
        BuildPlan.Builder b = BuildPlanner.coreBuilder(in);
        // Tails keep the test branch in a full plan (terminal join).
        PlannerTails.appendDeclaredTails(b, in);
        var plan = b.build();
        var compileTest = plan.steps().stream()
                .filter(s -> s.name().equals(TaskNames.COMPILE_TEST))
                .findFirst()
                .orElseThrow();
        assertThat(compileTest.requires()).contains(TaskNames.COPY_RESOURCES);
    }

    // ---- fixture ------------------------------------------------------------------------------

    /** A Java module with a path-pinned, materialized [code] plugin (no worker fork needed). */
    private Path pluginProject() throws Exception {
        Path vendor = Files.createDirectories(tmp.resolve("vendor"));
        Path jar = writePluginJar(vendor.resolve("fake-1.0.0.jar"));
        String hex = Hashing.sha256Hex(jar);

        Path dir = Files.createDirectories(tmp.resolve("proj"));
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(dir.resolve("src/main/java/ex/A.java"), "package ex; class A {}\n");
        Files.createDirectories(dir.resolve("src/test/java/ex"));
        Files.writeString(dir.resolve("src/test/java/ex/ATest.java"), "package ex; class ATest {}\n");
        Files.writeString(dir.resolve("jk.toml"), """
                name = "demo"
                group = "com.demo"
                version = "0.1.0"
                java = 25

                [plugins]
                fake = { path = "%s", sha256 = "%s" }

                [fake]
                enabled = true
                """.formatted(
                        dir.relativize(jar).toString().replace('\\', '/'), hex));

        Cas cas = new Cas(tmp.resolve("cache"));
        Path casJar = cas.putFile(jar, hex);
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        null,
                        null,
                        List.of(),
                        List.of(new Lockfile.PluginEntry("path:fake", "local", "sha256:" + hex))),
                dir.resolve("jk-lock.toml"));
        PluginDescriptorOps.materialize(dir, hex, casJar, "path:fake");
        return dir;
    }

    /** Pre-seed the content-keyed describe cache so coreBuilder never forks a plugin worker. */
    private void seedDescribeCache(Path dir, JkBuild build, List<String> declLines) throws Exception {
        PluginBuild.Active active = ActivePlugins.of(build, dir).getFirst();
        Path target = BuildLayout.of(dir, build).moduleTargetDir();
        // The same jar lookup the planner makes, so the seeded key is the one it computes.
        String key = PluginBuild.describeKey(active, build, PluginBuild.locateWorkerJar(active, tmp.resolve("cache")));
        Path cacheFile = target.resolve("plugin").resolve("fake-describe-" + key + ".jsonl");
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, declLines, StandardCharsets.UTF_8);
    }

    /**
     * A rebuilt plugin without a manifest version bump declares different steps under the same
     * version; the describe reply is keyed on the worker jar's content so it is asked again.
     */
    @Test
    void describe_key_follows_the_worker_jar_content() throws Exception {
        Path dir = pluginProject();
        JkBuild build = JkBuildParser.reparse(dir.resolve("jk.toml"));
        PluginBuild.Active active = ActivePlugins.of(build, dir).getFirst();
        Path shipped = tmp.resolve("vendor").resolve("fake-1.0.0.jar");
        Path rebuilt = Files.writeString(tmp.resolve("vendor").resolve("fake-1.0.0-rebuilt.jar"), "other bytes");
        Path sameBytes = Files.copy(shipped, tmp.resolve("vendor").resolve("fake-1.0.0-copy.jar"));

        String key = PluginBuild.describeKey(active, build, shipped);
        assertThat(PluginBuild.describeKey(active, build, sameBytes))
                .as("the jar's content is the input, not its path")
                .isEqualTo(key);
        assertThat(PluginBuild.describeKey(active, build, rebuilt)).isNotEqualTo(key);
        assertThat(PluginBuild.describeKey(active, build, null))
                .as("a jar that is nowhere keys as absent, never as the shipped one")
                .isNotEqualTo(key);
    }

    private Set<String> planNames(Path dir, boolean testOnly) {
        // Full plans need the tails: since run-tests is a terminal-join leaf, not a
        // packaging prerequisite, and a core-only build would prune the whole test branch.
        BuildPlanner.Inputs in = inputs(dir, testOnly, false);
        BuildPlan.Builder b = BuildPlanner.coreBuilder(in);
        if (!testOnly) PlannerTails.appendDeclaredTails(b, in);
        return b.build().steps().stream().map(s -> s.name()).collect(Collectors.toSet());
    }

    private BuildPlanner.Inputs inputs(Path dir, boolean testOnly, boolean compileOnly) {
        return new BuildPlanner.Inputs(
                dir,
                tmp.resolve("cache"),
                dir.resolve("jk.toml"),
                dir.resolve("jk-lock.toml"),
                dir,
                1,
                0,
                null,
                null,
                false,
                false,
                testOnly,
                compileOnly,
                Set.of(),
                SessionContext.current());
    }

    private static Path writePluginJar(Path jar) throws Exception {
        Files.createDirectories(jar.getParent());
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out, mf)) {
            jos.putNextEntry(new JarEntry("jk-plugin.toml"));
            jos.write(MANIFEST.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jar;
    }
}
