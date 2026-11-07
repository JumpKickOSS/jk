// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.KspProjects.build;
import static cc.jumpkick.runtime.KspProjects.buildSkippingTests;
import static cc.jumpkick.runtime.KspProjects.forecast;
import static cc.jumpkick.runtime.KspProjects.jarEntries;
import static cc.jumpkick.runtime.KspProjects.lock;
import static cc.jumpkick.runtime.KspProjects.step;
import static cc.jumpkick.runtime.KspProjects.write;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.testing.TestCaches;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A KSP processor that writes bytecode and a resource rather than sources, the way Micronaut's
 * writes its bean definitions. Nothing compiles that output, so it reaches the module only through
 * the classes tree: the main round's {@code .class} and {@code .txt} must be in the jar and on the
 * test classpath, and a test round over the test sources must put its own on the test classpath
 * and nowhere else. A module that drops its processors keeps nothing of either round, the test
 * round reads what the test compile reads, a test-only processor gets a test round, and after
 * {@code jk clean} the forecast reads the main round's files from its record as the build restores
 * them.
 *
 * <p>The processor is a few lines of Java compiled here against jk's KSP2 closure and published to
 * a {@code file://} repository, so the round runs a processor this test controls. Network test
 * (Maven Central, for the KSP closure, Kotlin and JUnit); the CAS persists under build/.
 */
@Tag("slow")
class KspOutputsTest {

    private static final String PROVIDER = """
            package proc;

            import com.google.devtools.ksp.processing.CodeGenerator;
            import com.google.devtools.ksp.processing.Dependencies;
            import com.google.devtools.ksp.processing.Resolver;
            import com.google.devtools.ksp.processing.SymbolProcessor;
            import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
            import com.google.devtools.ksp.processing.SymbolProcessorProvider;
            import com.google.devtools.ksp.symbol.KSAnnotated;
            import com.google.devtools.ksp.symbol.KSClassDeclaration;
            import com.google.devtools.ksp.symbol.KSFile;
            import java.io.IOException;
            import java.io.OutputStream;
            import java.io.UncheckedIOException;
            import java.lang.classfile.ClassFile;
            import java.lang.constant.ClassDesc;
            import java.lang.constant.ConstantDescs;
            import java.nio.charset.StandardCharsets;
            import java.util.Iterator;
            import java.util.List;

            /** For each class annotated demo.Marked: a resource marked/<Name>.txt and a class demo.<Name>Marker. */
            public final class MarkerProvider implements SymbolProcessorProvider {
                @Override
                public SymbolProcessor create(SymbolProcessorEnvironment environment) {
                    CodeGenerator generator = environment.getCodeGenerator();
                    return new SymbolProcessor() {
                        @Override
                        public List<KSAnnotated> process(Resolver resolver) {
                            Iterator<KSAnnotated> it = resolver.getSymbolsWithAnnotation("demo.Marked", false).iterator();
                            while (it.hasNext()) {
                                if (it.next() instanceof KSClassDeclaration type) emit(type);
                            }
                            return List.of();
                        }

                        private void emit(KSClassDeclaration type) {
                            String name = type.getSimpleName().asString();
                            KSFile file = type.getContainingFile();
                            Dependencies deps = file == null ? new Dependencies(false) : new Dependencies(false, file);
                            byte[] bytecode = ClassFile.of().build(
                                    ClassDesc.of("demo." + name + "Marker"),
                                    b -> b.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
                                            .withSuperclass(ConstantDescs.CD_Object));
                            write(generator.createNewFile(deps, "demo", name + "Marker", "class"), bytecode);
                            write(generator.createNewFile(deps, "marked", name, "txt"),
                                    name.getBytes(StandardCharsets.UTF_8));
                        }

                        @Override
                        public void finish() {}

                        @Override
                        public void onError() {}
                    };
                }

                private static void write(OutputStream out, byte[] bytes) {
                    try (out) {
                        out.write(bytes);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
            """;

    /** The marker processor declared in the shared table, which runs both rounds. */
    private static final String MAIN_TABLE = """
            [processor-dependencies]
            marker = "com.example:marker-proc:1.0"
            """;

    /** The marker processor declared for the test compile alone. */
    private static final String TEST_TABLE = """
            [test-processor-dependencies]
            marker = "com.example:marker-proc:1.0"
            """;

    private static final String MANIFEST = """
            name    = "marked"
            group   = "com.example"
            version = "1.0.0"
            java    = 25
            kotlin  = "^2.4.10"

            %s
            [test-dependencies]
            junit-jupiter = "6.1.3"

            [repositories]
            central = "https://repo.maven.apache.org/maven2/"

            [repositories.local]
            url = "%s"
            """;

    @Test
    void a_processors_bytecode_and_resources_reach_the_jar_and_the_test_classpath(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("ksp-outputs");
        Path repo = tmp.resolve("repo");
        publishProcessor(repo, tmp.resolve("proc"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        writeSources(project, true);
        Path greeter = project.resolve("src/main/kotlin/demo/Greeter.kt");
        BuildLayout layout = lock(project, manifest(repo, MAIN_TABLE, ""), cache);

        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(first.success()).isTrue();
        assertThat(step(first, "run-tests").status())
                .as("both tests ran and passed")
                .isEqualTo(TaskStatus.SUCCESS);
        assertThat(jarEntries(layout.mainJar()))
                .as("the main round's bytecode and resource are packaged")
                .contains("demo/GreeterMarker.class", "marked/Greeter.txt")
                .doesNotContain("demo/ProbeMarker.class", "marked/Probe.txt");
        assertThat(layout.testClassesDir().resolve("demo/ProbeMarker.class")).isRegularFile();
        assertThat(layout.testClassesDir().resolve("marked/Probe.txt")).isRegularFile();

        BuildPlanResult again = build(project, cache);
        assertThat(again.errors()).isEmpty();
        assertThat(again.success()).isTrue();
        assertThat(jarEntries(layout.mainJar())).contains("demo/GreeterMarker.class", "marked/Greeter.txt");

        // The annotation goes: the round no longer writes Greeter's output, and the copy leaves the
        // classes tree and the jar with it.
        write(greeter, """
                package demo

                class Greeter
                """);
        BuildPlanResult unmarked = buildSkippingTests(project, cache);
        assertThat(unmarked.errors()).isEmpty();
        assertThat(unmarked.success()).isTrue();
        assertThat(layout.classesDir().resolve("demo/GreeterMarker.class")).doesNotExist();
        assertThat(jarEntries(layout.mainJar())).doesNotContain("demo/GreeterMarker.class", "marked/Greeter.txt");
    }

    @Test
    void a_module_that_drops_its_processors_keeps_nothing_of_either_round(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("ksp-outputs");
        Path repo = tmp.resolve("repo");
        publishProcessor(repo, tmp.resolve("proc"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        writeSources(project, false);
        BuildLayout layout = lock(project, manifest(repo, MAIN_TABLE, ""), cache);
        BuildPlanResult first = buildSkippingTests(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(jarEntries(layout.mainJar())).contains("demo/GreeterMarker.class", "marked/Greeter.txt");

        // The processors go. What the old rounds left behind must neither compile nor ship: a stale
        // generated source that would not compile, and a test round's bytecode.
        lock(project, manifest(repo, "", ""), cache);
        Path kspOut = layout.buildDir().resolve("ksp");
        Path kspTestOut = layout.buildDir().resolve("ksp-test");
        write(kspOut.resolve("kotlin/demo/Stale.kt"), "package demo\n\nthis does not compile\n");
        write(kspTestOut.resolve("classes/demo/StaleMarker.class"), "stale");
        BuildPlanResult dropped = buildSkippingTests(project, cache);
        assertThat(dropped.errors()).isEmpty();
        assertThat(dropped.success()).isTrue();
        assertThat(kspOut).as("the main round's output is removed").doesNotExist();
        assertThat(kspTestOut).as("the test round's output is removed").doesNotExist();
        assertThat(layout.classesDir().resolve("demo/GreeterMarker.class")).doesNotExist();
        assertThat(layout.classesDir().resolve("marked/Greeter.txt")).doesNotExist();
        assertThat(jarEntries(layout.mainJar()))
                .contains("demo/Greeter.class")
                .doesNotContain("demo/GreeterMarker.class", "marked/Greeter.txt", "demo/Stale.class");
    }

    @Test
    void the_test_round_leaves_out_what_test_exclude_src_leaves_out(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("ksp-outputs");
        Path repo = tmp.resolve("repo");
        publishProcessor(repo, tmp.resolve("proc"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        writeSources(project, true);
        // Beside a kept test in the same directory, and a whole directory nothing is excluded from.
        write(project.resolve("src/test/kotlin/demo/ExcludedProbe.kt"), """
                package demo

                @Marked
                class Skipped
                """);
        write(project.resolve("src/test/kotlin/other/Other.kt"), """
                package other

                import demo.Marked

                @Marked
                class Other
                """);
        BuildLayout layout =
                lock(project, manifest(repo, MAIN_TABLE, "[test]\nexclude-src = [\"demo/Excluded*.kt\"]\n"), cache);

        BuildPlanResult result = build(project, cache);
        assertThat(result.errors()).isEmpty();
        assertThat(step(result, "run-tests").status()).isEqualTo(TaskStatus.SUCCESS);
        Path testClasses = layout.testClassesDir();
        assertThat(testClasses.resolve("demo/ProbeMarker.class"))
                .as("a kept file beside an excluded one is processed")
                .isRegularFile();
        assertThat(testClasses.resolve("demo/OtherMarker.class"))
                .as("a directory nothing is excluded from is processed")
                .isRegularFile();
        assertThat(testClasses.resolve("demo/SkippedMarker.class"))
                .as("the excluded file is no input of the round")
                .doesNotExist();
        assertThat(testClasses.resolve("marked/Skipped.txt")).doesNotExist();
        assertThat(layout.buildDir().resolve("ksp-test/classes/demo/SkippedMarker.class"))
                .doesNotExist();
    }

    @Test
    void a_test_only_processor_gets_a_test_round(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("ksp-outputs");
        Path repo = tmp.resolve("repo");
        publishProcessor(repo, tmp.resolve("proc"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        writeSources(project, false);
        write(project.resolve("src/test/kotlin/demo/MarkedTest.kt"), """
                package demo

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                @Marked
                class Probe

                class MarkedTest {
                    @Test
                    fun the_test_rounds_output_is_on_the_test_classpath() {
                        Class.forName("demo.ProbeMarker")
                        val text = requireNotNull(javaClass.classLoader.getResource("marked/Probe.txt")).readText()
                        assertEquals("Probe", text)
                    }
                }
                """);
        BuildLayout layout = lock(project, manifest(repo, TEST_TABLE, ""), cache);

        BuildPlanResult result = build(project, cache);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(step(result, "run-tests").status())
                .as("the test round's output is on the test classpath")
                .isEqualTo(TaskStatus.SUCCESS);
        assertThat(layout.testClassesDir().resolve("demo/ProbeMarker.class")).isRegularFile();
        assertThat(layout.buildDir().resolve("ksp")).as("no main round runs").doesNotExist();
        assertThat(jarEntries(layout.mainJar())).doesNotContain("demo/GreeterMarker.class", "demo/ProbeMarker.class");
    }

    @Test
    void after_a_clean_the_forecast_and_the_build_agree_on_the_rounds_files(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("ksp-outputs");
        Path repo = tmp.resolve("repo");
        publishProcessor(repo, tmp.resolve("proc"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        writeSources(project, false);
        BuildLayout layout = lock(project, manifest(repo, MAIN_TABLE, ""), cache);
        BuildPlanResult first = buildSkippingTests(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(jarEntries(layout.mainJar())).contains("demo/GreeterMarker.class", "marked/Greeter.txt");

        // jk clean: target/ goes, the action cache stays.
        PathUtil.deleteRecursively(layout.buildDir());
        Map<String, TaskForecast.Task> forecast = forecast(project, cache, true);

        BuildPlanResult rebuilt = buildSkippingTests(project, cache);
        assertThat(rebuilt.errors()).isEmpty();
        assertThat(jarEntries(layout.mainJar())).contains("demo/GreeterMarker.class", "marked/Greeter.txt");
        for (String name : List.of(TaskNames.COMPILE_KOTLIN, TaskNames.PACKAGE_JAR)) {
            TaskForecast.Task predicted = requireNonNull(forecast.get(name), () -> "no " + name + " in " + forecast);
            assertThat(step(rebuilt, name).status())
                    .as("%s: the build restores what the forecast said it would (%s)", name, predicted.text())
                    .isEqualTo(TaskStatus.SKIPPED);
            assertThat(predicted.cached())
                    .as("%s: the forecast reads the round's files from its record: %s", name, predicted.text())
                    .isTrue();
        }
    }

    /** The main sources: the annotation and a class it marks; with a test that reads both rounds' output. */
    private static void writeSources(Path project, boolean withTest) throws IOException {
        write(project.resolve("src/main/kotlin/demo/Marked.kt"), """
                package demo

                annotation class Marked
                """);
        write(project.resolve("src/main/kotlin/demo/Greeter.kt"), """
                package demo

                @Marked
                class Greeter
                """);
        if (!withTest) return;
        write(project.resolve("src/test/kotlin/demo/MarkedTest.kt"), """
                package demo

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                @Marked
                class Probe

                class MarkedTest {
                    private fun text(name: String) =
                        requireNotNull(javaClass.classLoader.getResource(name)) { "no " + name }.readText()

                    @Test
                    fun the_main_rounds_output_is_on_the_test_classpath() {
                        Class.forName("demo.GreeterMarker")
                        assertEquals("Greeter", text("marked/Greeter.txt"))
                    }

                    @Test
                    fun the_test_rounds_output_is_on_the_test_classpath() {
                        Class.forName("demo.ProbeMarker")
                        assertEquals("Probe", text("marked/Probe.txt"))
                    }
                }
                """);
    }

    /** The manifest with the processor {@code table} (or none) and {@code extra} tables after it. */
    private static String manifest(Path repo, String table, String extra) {
        return MANIFEST.formatted(table, repo.toUri()) + extra;
    }

    /** The marker processor, published to {@code repo} as {@code com.example:marker-proc:1.0}. */
    private static void publishProcessor(Path repo, Path work) throws Exception {
        KspProjects.publishProcessor(repo, work, "marker-proc", "1.0", "proc.MarkerProvider", PROVIDER);
    }
}
