// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The minified plugin: R8 {@code --classfile} full mode over a plain Java app + its runtime
 * closure, packaged as one slim executable jar replacing the main artifact. The acceptance is
 * behavioral AND structural: the shrunk jar <em>runs</em> ({@code java -jar} prints the expected
 * output — R8 kept everything reachable), dead library code is gone (an unreferenced
 * commons-lang3 package is absent), and the artifact is a fraction of the input closure.
 *
 * <p>Obfuscated builds additionally owe the {@code -mapping.txt} deobfuscation map, and owe it
 * <em>after a cache hit too</em>: it is the only copy, and a shipped obfuscated jar whose map has
 * silently vanished can never have a crash report resolved again.
 *
 * <p>Network test (Maven Central: commons-lang3 + the r8 jar); the CAS persists under build/ so
 * repeat runs are warm.
 */
@Tag("slow")
class MinifiedPluginTest {

    @Test
    void shrunk_jar_runs_and_dead_code_is_gone(@TempDir Path tmp) throws Exception {
        Path project = writeProject(tmp);
        Path cache = spikeCache();

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        assertThat(build.pluginConfig("minified")).isPresent();

        BuildPlanResult result = build(project, cache);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();

        // Artifacts are additive: the thin jar always, the fat jar because minified implies it,
        // and the minified jar itself. The fat jar beside it is what makes the two comparable.
        Path target = project.resolve("target");
        assertThat(target.resolve("slim-1.0.0.jar")).exists();
        assertThat(target.resolve("slim-1.0.0-all.jar")).exists();
        Path jar = target.resolve("slim-1.0.0-min.jar");
        assertThat(jar).exists();
        assertThat(Files.size(jar))
                .as("the minified jar is smaller than the fat jar it came from")
                .isLessThan(Files.size(target.resolve("slim-1.0.0-all.jar")));

        // Behavioral: the shrunk jar actually runs — R8 kept the reachable closure.
        Process run = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(run.getInputStream().readAllBytes());
        assertThat(run.waitFor()).as("java -jar exit (output: %s)", output).isZero();
        assertThat(output).contains("Hello jk");

        // Structural: dead library code is gone; the app + used helpers survived.
        try (JarFile jf = new JarFile(jar.toFile())) {
            assertThat(jf.getJarEntry("com/example/slim/Main.class")).isNotNull();
            assertThat(jf.getJarEntry("org/apache/commons/lang3/StringUtils.class"))
                    .isNotNull();
            assertThat(jf.getJarEntry("org/apache/commons/lang3/time/DateUtils.class"))
                    .as("unreferenced commons-lang3 code shrunk away")
                    .isNull();
        }
        // The whole closure (commons-lang3 alone is ~700 KB) collapses to a fraction.
        assertThat(Files.size(jar)).isLessThan(400_000);
    }

    /**
     * The deobfuscation map is a build OUTPUT, not a side effect. Delete it and the jar, rebuild:
     * the packaging action key still hits, so R8 never reruns — and the map has to come back out of
     * the cache with the jar. Before it was declared, this second build restored an obfuscated
     * artifact whose only map was gone for good.
     */
    @Test
    void the_mapping_file_survives_a_packaging_cache_hit(@TempDir Path tmp) throws Exception {
        Path project = writeObfuscatedProject(tmp);
        Path cache = spikeCache();

        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(first.success()).isTrue();

        Path target = project.resolve("target");
        Path jar = target.resolve("slim-1.0.0-min.jar");
        Path mapping = target.resolve("slim-1.0.0-min-mapping.txt");
        assertThat(jar).exists();
        assertThat(mapping)
                .as("R8 writes the map when [minified] obfuscate = true")
                .isNotEmptyFile();
        String mapped = Files.readString(mapping);

        Files.delete(jar);
        Files.delete(mapping);

        BuildPlanResult second = build(project, cache);
        assertThat(second.errors()).isEmpty();
        assertThat(second.success()).isTrue();
        // SKIPPED is what TaskContext.cached() reports: the restore path ran, R8 did not.
        assertThat(second.steps())
                .filteredOn(step -> TaskNames.PACKAGE_MINIFIED.equals(step.name()))
                .singleElement()
                .extracting(BuildPlanResult.StepReport::status)
                .as("the second build must be the cache hit this test is about")
                .isEqualTo(TaskStatus.SKIPPED);

        assertThat(jar).exists();
        assertThat(mapping)
                .as("an obfuscated jar whose map vanished on a cache hit can never be de-obfuscated")
                .exists();
        assertThat(Files.readString(mapping)).isEqualTo(mapped);
    }

    /** The CAS persists under build/ so repeat runs (and the second test here) are warm. */
    private static Path spikeCache() {
        return Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
    }

    /** A one-class app over commons-lang3, shrunk but not obfuscated. */
    private static Path writeProject(Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), """
                name    = "slim"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [application]
                main     = "com.example.slim.Main"
                minified = true

                [minified]

                [dependencies]
                commons-lang3 = { group = "org.apache.commons", name = "commons-lang3", version = "=3.18.0" }

                # This project runs no tests; owning [test-dependencies] keeps the injected
                # junit-jupiter "latest" out of the graph and the lock deterministic
                # (see KotlinSerializationTest).
                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                google  = "https://dl.google.com/dl/android/maven2/"   # r8 publishes to Google Maven
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example/slim"));
        Files.writeString(src.resolve("Main.java"), """
                package com.example.slim;

                import org.apache.commons.lang3.StringUtils;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println(StringUtils.capitalize("hello jk"));
                    }
                }
                """);
        return project;
    }

    /**
     * The obfuscating variant: no library closure (R8 over one class is seconds, and the map is the
     * subject here, not the shrink ratio), and a per-run marker in the source.
     *
     * <p>The marker is load-bearing. The spike cache above outlives the JVM, and packaging keys are
     * content-derived — so a fixed fixture makes the <em>first</em> build a replay of a record some
     * earlier run stored, and then "the map is still there after a cache hit" is true of a run in
     * which the packager never executed. A unique class body forces the first build to really
     * package, so the second one is a hit on a record this run wrote.
     */
    private static Path writeObfuscatedProject(Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), """
                name    = "slim"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [application]
                main     = "com.example.slim.Main"
                minified = true

                [minified]
                obfuscate = true

                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                google  = "https://dl.google.com/dl/android/maven2/"   # r8 publishes to Google Maven
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example/slim"));
        Files.writeString(src.resolve("Main.java"), """
                package com.example.slim;

                public final class Main {
                    static final String RUN = "%s";

                    public static void main(String[] args) {
                        System.out.println("hello jk " + RUN);
                    }
                }
                """.formatted(UUID.randomUUID()));
        return project;
    }

    /** Lock, then the full `jk build` shape — the tails carry -all.jar and -min.jar. */
    private static BuildPlanResult build(Path project, Path cache) throws Exception {
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        BuildPlanResult lockResult = lock.run();
        assertThat(lockResult.errors()).isEmpty();

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
        // The tails carry the additive artifacts (-all.jar, -min.jar); coreBuilder stops at the
        // thin jar, so a plan without them is not what `jk build` runs.
        var builder = BuildPlanner.coreBuilder(in);
        BuildPlanner.appendDeclaredTails(builder, in);
        return builder.build().run();
    }
}
