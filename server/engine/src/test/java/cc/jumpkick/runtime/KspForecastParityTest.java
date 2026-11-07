// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.KspProjects.build;
import static cc.jumpkick.runtime.KspProjects.buildSkippingTests;
import static cc.jumpkick.runtime.KspProjects.forecast;
import static cc.jumpkick.runtime.KspProjects.lock;
import static cc.jumpkick.runtime.KspProjects.publishPlainJar;
import static cc.jumpkick.runtime.KspProjects.publishProcessor;
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
 * {@code jk explain} and the build agree on a KSP module whose processor writes sources, the way
 * Room's and Hilt's do. After {@code jk clean} the build restores each round from its action record
 * and the compilers then hit their own records, so the forecast must read the generated sources,
 * their content and the test round's output from the same records rather than from the wiped
 * {@code target/}. On a warm tree a processor bump re-runs the round and changes what the compilers
 * and the classes tree get, and a module whose processors are all javac's compiles none of a
 * retired round's sources, even before the build removes them.
 *
 * <p>The processor is compiled here against jk's KSP2 closure and published to a {@code file://}
 * repository. Network test (Maven Central, for the KSP closure, Kotlin and JUnit).
 */
@Tag("slow")
class KspForecastParityTest {

    /**
     * Kotlin {@code demo.<Name>Dao} for each class annotated {@code demo.Marked}, Java {@code
     * demo.<Name>Row} for each {@code demo.Entity}; the Dao names the processor's version.
     */
    private static final String PROVIDER = """
            package gen;

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
            import java.nio.charset.StandardCharsets;
            import java.util.Iterator;
            import java.util.List;

            public final class SourceProvider implements SymbolProcessorProvider {
                private static final String VERSION = "%s";

                @Override
                public SymbolProcessor create(SymbolProcessorEnvironment environment) {
                    CodeGenerator generator = environment.getCodeGenerator();
                    return new SymbolProcessor() {
                        @Override
                        public List<KSAnnotated> process(Resolver resolver) {
                            for (String annotation : List.of("demo.Marked", "demo.Entity")) {
                                Iterator<KSAnnotated> it = resolver.getSymbolsWithAnnotation(annotation, false).iterator();
                                while (it.hasNext()) {
                                    if (it.next() instanceof KSClassDeclaration type) emit(type, annotation.endsWith("Entity"));
                                }
                            }
                            return List.of();
                        }

                        private void emit(KSClassDeclaration type, boolean entity) {
                            String name = type.getSimpleName().asString();
                            KSFile file = type.getContainingFile();
                            Dependencies deps = file == null ? new Dependencies(false) : new Dependencies(false, file);
                            if (entity) {
                                write(generator.createNewFile(deps, "demo", name + "Row", "java"),
                                        "package demo;\\n\\npublic final class " + name + "Row {\\n"
                                                + "    public static final String NAME = \\"" + name + "\\";\\n}\\n");
                            } else {
                                write(generator.createNewFile(deps, "demo", name + "Dao", "kt"),
                                        "package demo\\n\\nobject " + name + "Dao {\\n"
                                                + "    const val NAME = \\"" + name + "\\"\\n"
                                                + "    const val VERSION = \\"" + VERSION + "\\"\\n}\\n");
                            }
                        }

                        @Override
                        public void finish() {}

                        @Override
                        public void onError() {}
                    };
                }

                private static void write(OutputStream out, String text) {
                    try (out) {
                        out.write(text.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
            """;

    private static final String MANIFEST = """
            name    = "generated"
            group   = "com.example"
            version = "1.0.0"
            java    = 25
            kotlin  = "^2.4.10"

            [processor-dependencies]
            gen = "com.example:%s:%s"

            [test-dependencies]
            junit-jupiter = "6.1.3"

            [repositories]
            central = "https://repo.maven.apache.org/maven2/"

            [repositories.local]
            url = "%s"
            """;

    @Test
    void after_a_clean_the_forecast_and_the_build_agree_on_the_generated_sources(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("ksp-forecast-parity");
        Path repo = publish(tmp, "1.0");
        Path project = Files.createDirectories(tmp.resolve("app"));
        writeSources(project, true, false);
        BuildLayout layout = lock(project, manifest(repo, "gen-proc", "1.0"), cache);
        BuildPlanResult first = buildSkippingTests(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(layout.buildDir().resolve("ksp/kotlin/demo/GreeterDao.kt")).isRegularFile();

        PathUtil.deleteRecursively(layout.buildDir());
        Map<String, TaskForecast.Task> forecast = forecast(project, cache, true);
        BuildPlanResult rebuilt = buildSkippingTests(project, cache);

        assertThat(rebuilt.errors()).isEmpty();
        assertAgree(forecast, rebuilt, TaskNames.COMPILE_KOTLIN, TaskNames.COMPILE_JAVA, TaskNames.PACKAGE_JAR);
    }

    @Test
    void after_a_clean_the_forecast_and_the_build_agree_on_the_test_round(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("ksp-forecast-parity");
        Path repo = publish(tmp, "1.0");
        Path project = Files.createDirectories(tmp.resolve("app"));
        writeSources(project, true, true);
        BuildLayout layout = lock(project, manifest(repo, "gen-proc", "1.0"), cache);
        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(step(first, TaskNames.RUN_TESTS).status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(layout.buildDir().resolve("ksp-test/kotlin/demo/ProbeDao.kt"))
                .isRegularFile();

        PathUtil.deleteRecursively(layout.buildDir());
        Map<String, TaskForecast.Task> forecast = forecast(project, cache, false);
        BuildPlanResult rebuilt = build(project, cache);

        assertThat(rebuilt.errors()).isEmpty();
        // compile-test restores the round and the Kotlin test classes, which it reports as work.
        TaskForecast.Task compileTest = requireNonNull(forecast.get(TaskNames.COMPILE_TEST), "compile-test");
        assertThat(compileTest.cached())
                .as("the test round comes back from its record: %s", compileTest.text())
                .isTrue();
        assertAgree(forecast, rebuilt, TaskNames.COMPILE_KOTLIN, TaskNames.RUN_TESTS);
    }

    @Test
    void a_processor_bump_on_a_warm_tree_changes_the_forecast(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("ksp-forecast-parity");
        Path repo = publish(tmp, "1.0");
        publishProcessor(
                repo, tmp.resolve("proc-1.1"), "gen-proc", "1.1", "gen.SourceProvider", PROVIDER.formatted("1.1"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        writeSources(project, true, false);
        lock(project, manifest(repo, "gen-proc", "1.0"), cache);
        assertThat(buildSkippingTests(project, cache).errors()).isEmpty();
        Map<String, TaskForecast.Task> warm = forecast(project, cache, true);
        TaskForecast.Task packaged = warm.get(TaskNames.PACKAGE_JAR);
        assertThat(packaged == null || packaged.cached())
                .as("nothing moved: %s", warm)
                .isTrue();

        // Only the processor changes: same sources, a jar whose generated Dao says 1.1.
        lock(project, manifest(repo, "gen-proc", "1.1"), cache);
        Map<String, TaskForecast.Task> bumped = forecast(project, cache, true);
        BuildPlanResult rebuilt = buildSkippingTests(project, cache);

        assertThat(rebuilt.errors()).isEmpty();
        for (String name : List.of(TaskNames.KSP, TaskNames.COMPILE_KOTLIN, TaskNames.PACKAGE_JAR)) {
            TaskForecast.Task predicted = requireNonNull(bumped.get(name), () -> "no " + name + " in " + bumped);
            assertThat(predicted.cached())
                    .as("%s: the processor path is a round input: %s", name, predicted.text())
                    .isFalse();
            assertThat(step(rebuilt, name).status()).as(name).isNotEqualTo(TaskStatus.SKIPPED);
        }
    }

    @Test
    void a_module_whose_processors_are_all_javacs_compiles_none_of_a_retired_round(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("ksp-forecast-parity");
        Path repo = tmp.resolve("repo");
        publishPlainJar(repo, "plain-proc", "1.0");
        Path project = Files.createDirectories(tmp.resolve("app"));
        writeSources(project, false, false);
        BuildLayout layout = lock(project, manifest(repo, "plain-proc", "1.0"), cache);
        assertThat(buildSkippingTests(project, cache).errors()).isEmpty();

        // What a retired round left behind, which the build removes before anything compiles.
        write(layout.buildDir().resolve("ksp/kotlin/demo/Stale.kt"), "package demo\n\nthis does not compile\n");
        Map<String, TaskForecast.Task> forecast = forecast(project, cache, true);
        BuildPlanResult rebuilt = buildSkippingTests(project, cache);

        assertThat(rebuilt.errors()).isEmpty();
        assertThat(layout.buildDir().resolve("ksp")).doesNotExist();
        assertAgree(forecast, rebuilt, TaskNames.COMPILE_KOTLIN);
    }

    /** Each step the build restored is one the forecast said would come from the cache. */
    private static void assertAgree(Map<String, TaskForecast.Task> forecast, BuildPlanResult build, String... steps) {
        for (String name : steps) {
            TaskForecast.Task predicted = requireNonNull(forecast.get(name), () -> "no " + name + " in " + forecast);
            assertThat(step(build, name).status())
                    .as("%s: the build restores what the forecast said it would (%s)", name, predicted.text())
                    .isEqualTo(TaskStatus.SKIPPED);
            assertThat(predicted.cached())
                    .as(
                            "%s: the forecast reads the generated files from the rounds' records: %s",
                            name, predicted.text())
                    .isTrue();
        }
    }

    /** The source processor at {@code version}, published to a repository under {@code tmp}. */
    private static Path publish(Path tmp, String version) throws Exception {
        Path repo = tmp.resolve("repo");
        publishProcessor(
                repo,
                tmp.resolve("proc-" + version),
                "gen-proc",
                version,
                "gen.SourceProvider",
                PROVIDER.formatted(version));
        return repo;
    }

    /**
     * The annotation and a marked class, which reads its generated Dao when {@code readsGenerated};
     * with a test that marks a class of its own and reads both rounds' sources when {@code withTest}.
     */
    private static void writeSources(Path project, boolean readsGenerated, boolean withTest) throws IOException {
        write(project.resolve("src/main/kotlin/demo/Marked.kt"), """
                package demo

                annotation class Marked

                annotation class Entity
                """);
        write(project.resolve("src/main/kotlin/demo/Greeter.kt"), readsGenerated ? """
                package demo

                @Marked
                @Entity
                class Greeter {
                    fun name() = GreeterDao.NAME
                }
                """ : """
                package demo

                @Marked
                @Entity
                class Greeter
                """);
        if (!withTest) return;
        write(project.resolve("src/test/kotlin/demo/GreeterTest.kt"), """
                package demo

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                @Marked
                class Probe

                class GreeterTest {
                    @Test
                    fun the_main_rounds_source_compiled() {
                        assertEquals("Greeter", Greeter().name())
                    }

                    @Test
                    fun the_test_rounds_source_compiled() {
                        assertEquals("Probe", ProbeDao.NAME)
                    }
                }
                """);
    }

    private static String manifest(Path repo, String artifact, String version) {
        return MANIFEST.formatted(artifact, version, repo.toUri());
    }
}
