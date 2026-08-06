// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.Hashing;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression (JK-1575): testOnly plans must construct valid. run-tests requires the plugin tasks
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
        JkBuild build = cc.jumpkick.plugin.manifest.VariantApply.apply(
                        JkBuildParser.reparse(dir.resolve("jk.toml")),
                        dir,
                        cc.jumpkick.model.Variants.Selection.parse(""),
                        java.util.Map.of())
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
                [project]
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
        BuildPlanner.appendDeclaredTails(tb, testOnly);
        Set<String> testNames =
                tb.build().steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        assertThat(testNames)
                .as("test plan terminates at run-tests; no assembly tail, no package-jar")
                .contains(TaskNames.RUN_TESTS)
                .doesNotContain(TaskNames.PACKAGE_ASSEMBLY, TaskNames.PACKAGE_JAR);

        BuildPlanner.Inputs compileOnly = inputs(dir, false, true);
        BuildPlan.Builder cb = BuildPlanner.coreBuilder(compileOnly);
        BuildPlanner.appendDeclaredTails(cb, compileOnly);
        Set<String> compileNames =
                cb.build().steps().stream().map(s -> s.name()).collect(Collectors.toSet());
        assertThat(compileNames)
                .as("compile plan terminates at write-stamp; no assembly tail")
                .contains(TaskNames.WRITE_STAMP)
                .doesNotContain(TaskNames.PACKAGE_ASSEMBLY, TaskNames.PACKAGE_JAR);
    }

    @Test
    void test_only_plan_keeps_freshness_stamps(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.createDirectories(dir.resolve("src/main/kotlin"));
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
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
                [project]
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

    @Test
    void single_language_compile_only_keeps_single_stamp_terminal(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "ex"
                name = "m"
                version = "1.0"
                java = 25
                """);
        Set<String> names = BuildPlanner.coreBuilder(inputs(dir, false, true)).build().steps().stream()
                .map(s -> s.name())
                .collect(Collectors.toSet());
        assertThat(names)
                .as("single-language jk compile: no join task, stamp is the terminal")
                .contains(TaskNames.WRITE_STAMP)
                .doesNotContain(BuildPlanner.COMPILE_JOIN, TaskNames.PACKAGE_JAR);
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
                [project]
                name = "demo"
                group = "com.demo"
                version = "0.1.0"
                java = 25

                [plugins]
                fake = { path = "%s", sha256 = "%s" }

                [fake]
                enabled = true
                """.formatted(dir.relativize(jar).toString().replace('\\', '/'), hex));

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
        PluginDescriptorOps.materialize(dir, hex, casJar);
        return dir;
    }

    /** Pre-seed the content-keyed describe cache so coreBuilder never forks a plugin worker. */
    private static void seedDescribeCache(Path dir, JkBuild build, List<String> declLines) throws Exception {
        PluginBuild.Active active =
                PluginBuild.activeCodePlugin(build, dir).orElseThrow();
        Path target = BuildLayout.of(dir, build).moduleTargetDir();
        String key = PluginBuild.describeKey(active, build);
        Path cacheFile = target.resolve("plugin").resolve("fake-describe-" + key + ".jsonl");
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, declLines, StandardCharsets.UTF_8);
    }

    private Set<String> planNames(Path dir, boolean testOnly) {
        return BuildPlanner.coreBuilder(inputs(dir, testOnly, false)).build().steps().stream()
                .map(s -> s.name())
                .collect(Collectors.toSet());
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
                cc.jumpkick.config.SessionContext.current());
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
