// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Engine compile lane: Scala-only and mixed Java+Scala through Zinc compileMixed. */
@Tag("slow")
class ScalaBuildE2eTest {

    private static final String REPOS = """
            [repositories]
            central = "https://repo.maven.apache.org/maven2/"

            [test-dependencies]
            junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "latest" }
            junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }
            """;

    /**
     * A compiler newer than the Scala stdlib Zinc itself drags onto the worker's classpath. Every
     * {@code scala.*} class the compiler touches has to come from its own closure: parented on the
     * worker loader, scalac 3.9.0 died on {@code NoSuchMethodError: scala.Option.orNull()} with
     * the older Option loaded underneath it.
     */
    @Test
    void a_compiler_newer_than_zincs_stdlib_compiles(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("newer"));
        Files.writeString(project.resolve("jk.toml"), """
                name    = "newer"
                group   = "com.example"
                version = "1.0.0"
                java    = 25
                scala   = "=3.9.0"

                """ + REPOS);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("Newer.scala"), """
                package com.example

                object Newer:
                  def orNone(s: String): Option[String] = Option(s).filter(_.nonEmpty)
                """);
        BuildPlanResult result = build(project, cache());
        assertThat(result.errors()).as("errors=%s", result.errors()).isEmpty();
        assertThat(project.resolve("target/lib/newer-1.0.0.jar")).exists();
    }

    @Test
    void scala_only_module_compiles_and_packages(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("sapp"));
        Path cache = cache();
        Files.writeString(project.resolve("jk.toml"), """
                name    = "sapp"
                group   = "com.example"
                version = "1.0.0"
                java    = 25
                scala   = "3.8.4"

                """ + REPOS);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("Greeting.scala"), """
                package com.example

                class Greeting(val message: String):
                  def shout: String = message.toUpperCase
                """);

        BuildPlanResult result = build(project, cache);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(project.resolve("target/classes/main/com/example/Greeting.class"))
                .exists();
        try (var walk = Files.walk(project.resolve("target"))) {
            assertThat(walk.anyMatch(f -> f.getFileName().toString().equals("sapp-1.0.0.jar")))
                    .as("packaged jar")
                    .isTrue();
        }
    }

    @Test
    void scala_only_under_extra_src_gets_a_compiler(@TempDir Path tmp) throws Exception {
        // :.scala living only under a [build] extra-src overlay (no standard main root) must
        // still get a Scala toolchain — the gate reads the merged source set, not the main-roots walk.
        Path project = Files.createDirectories(tmp.resolve("sextra"));
        Path cache = cache();
        Files.writeString(project.resolve("jk.toml"), """
                name    = "sextra"
                group   = "com.example"
                version = "1.0.0"
                java    = 25
                scala   = "3.8.4"

                [build]
                extra-src = ["src/overlay"]

                """ + REPOS);
        // Traditional layout (src/main/java present) so the main-roots scala walk does NOT reach the
        // overlay — the bug only bites when the narrow walk misses the extra-src .scala.
        Path mainJava = Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.writeString(mainJava.resolve("Plain.java"), "package com.example; public class Plain {}");
        Path overlay = Files.createDirectories(project.resolve("src/overlay/com/example"));
        Files.writeString(overlay.resolve("Over.scala"), """
                package com.example

                class Over:
                  def hi: String = "overlay"
                """);

        BuildPlanResult result = build(project, cache);
        assertThat(result.errors()).as("errors=%s", result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(project.resolve("target/classes/main/com/example/Over.class"))
                .exists();
        assertThat(project.resolve("target/classes/main/com/example/Plain.class"))
                .exists();
    }

    @Test
    void mixed_java_scala_circular_compiles(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("mixed"));
        Path cache = cache();
        Files.writeString(project.resolve("jk.toml"), """
                name    = "mixed"
                group   = "com.example"
                version = "1.0.0"
                java    = 25
                scala   = "3.8.4"

                """ + REPOS);
        Path src = Files.createDirectories(project.resolve("src"));
        Files.writeString(src.resolve("A.scala"), """
                class A:
                  def ping: String = "a"
                  def fromB(b: B): String = b.pong
                """);
        Files.writeString(src.resolve("B.java"), """
                public class B {
                  public String pong() { return "b"; }
                  public String fromA() { return new A().ping(); }
                }
                """);

        BuildPlanResult result = build(project, cache);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(project.resolve("target/classes/main/A.class")).exists();
        assertThat(project.resolve("target/classes/main/B.class")).exists();
    }

    @Test
    void scala_junit_test_compiles(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("stest"));
        Path cache = cache();
        Files.writeString(project.resolve("jk.toml"), """
                name    = "stest"
                group   = "com.example"
                version = "1.0.0"
                java    = 25
                scala   = "3.8.4"

                """ + REPOS);
        Path src = Files.createDirectories(project.resolve("src/main/scala/com/example"));
        Files.writeString(src.resolve("Calc.scala"), """
                package com.example

                class Calc:
                  def doubleValue(value: Int): Int = value * 2
                """);
        Path test = Files.createDirectories(project.resolve("src/test/scala/com/example"));
        Files.writeString(test.resolve("CalcTest.scala"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class CalcTest:
                  @Test
                  def doubleValueReturnsTwiceTheInput(): Unit =
                    assertEquals(10, Calc().doubleValue(5))
                """);

        BuildPlanResult result = build(project, cache);
        List<String> underTarget = List.of();
        Path target = project.resolve("target");
        if (Files.isDirectory(target)) {
            try (var walk = Files.walk(target)) {
                underTarget = walk.filter(Files::isRegularFile)
                        .map(p -> project.relativize(p).toString())
                        .toList();
            }
        }
        assertThat(result.errors()).as("errors target=%s", underTarget).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(underTarget).as("compiled outputs").anyMatch(s -> s.endsWith("CalcTest.class"));
    }

    private static Path cache() {
        return Path.of(System.getProperty("user.dir"), "build", "scala-e2e-cache");
    }

    private static BuildPlanResult build(Path project, Path cache) throws Exception {
        var build = JkBuildParser.parse(project.resolve("jk.toml"));
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
                false,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        return BuildPlanner.fullPlan(in).run();
    }
}
