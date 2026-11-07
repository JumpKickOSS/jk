// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.base.KspResolver;
import cc.jumpkick.testing.TestCaches;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A KSP processor that writes bytecode and a resource rather than sources, the way Micronaut's
 * writes its bean definitions. Nothing compiles that output, so it reaches the module only through
 * the classes tree: the main round's {@code .class} and {@code .txt} must be in the jar and on the
 * test classpath, and a test round over the test sources must put its own on the test classpath
 * and nowhere else.
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

    private static final String MANIFEST = """
            name    = "marked"
            group   = "com.example"
            version = "1.0.0"
            java    = 25
            kotlin  = "^2.4.10"

            [processor-dependencies]
            marker = "com.example:marker-proc:1.0"

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
        Files.writeString(project.resolve("jk.toml"), MANIFEST.formatted(repo.toUri()));
        write(project.resolve("src/main/kotlin/demo/Marked.kt"), """
                package demo

                annotation class Marked
                """);
        Path greeter = project.resolve("src/main/kotlin/demo/Greeter.kt");
        write(greeter, """
                package demo

                @Marked
                class Greeter
                """);
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
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlanResult lock = LockPlans.lockBuildPlan(
                        project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(lock.errors()).isEmpty();
        BuildLayout layout = BuildLayout.of(project, build);

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

    /** {@link #PROVIDER} compiled against the KSP2 closure, jarred with its service entry, published to {@code repo}. */
    private static void publishProcessor(Path repo, Path work) throws Exception {
        JkBuild any = JkBuildParser.parse(MANIFEST.formatted(repo.toUri()));
        Cas cas = JkStores.storeCas();
        List<Path> kspClasspath = KspResolver.resolveClasspath(
                RepoGroupBuilder.buildFor(any, null, cas), cas, KspResolver.DEFAULT_VERSION);
        Path src = work.resolve("src/proc/MarkerProvider.java");
        write(src, PROVIDER);
        Path classes = Files.createDirectories(work.resolve("classes"));
        List<String> args = new ArrayList<>(List.of(
                "-d",
                classes.toString(),
                "-cp",
                kspClasspath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)),
                src.toString()));
        int exit =
                requireNonNull(ToolProvider.getSystemJavaCompiler()).run(null, null, null, args.toArray(String[]::new));
        assertThat(exit).as("the processor compiles against the KSP API").isZero();

        Path vDir = Files.createDirectories(repo.resolve("com/example/marker-proc/1.0"));
        try (OutputStream file = Files.newOutputStream(vDir.resolve("marker-proc-1.0.jar"));
                JarOutputStream out = new JarOutputStream(file)) {
            try (var walk = Files.walk(classes)) {
                for (Path c : walk.filter(Files::isRegularFile).sorted().toList()) {
                    out.putNextEntry(
                            new JarEntry(classes.relativize(c).toString().replace('\\', '/')));
                    out.write(Files.readAllBytes(c));
                    out.closeEntry();
                }
            }
            out.putNextEntry(
                    new JarEntry("META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"));
            out.write("proc.MarkerProvider\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Files.writeString(repo.resolve("com/example/marker-proc/maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>marker-proc</artifactId>
                  <versioning>
                    <latest>1.0</latest>
                    <release>1.0</release>
                    <versions><version>1.0</version></versions>
                  </versioning>
                </metadata>
                """);
        Files.writeString(vDir.resolve("marker-proc-1.0.pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>marker-proc</artifactId>
                  <version>1.0</version>
                </project>
                """);
    }

    private static Set<String> jarEntries(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.stream().map(JarEntry::getName).collect(Collectors.toSet());
        }
    }

    private static BuildPlanResult build(Path project, Path cache) throws Exception {
        return BuildPlanner.fullPlan(inputs(project, cache, false)).run();
    }

    private static BuildPlanResult buildSkippingTests(Path project, Path cache) throws Exception {
        return BuildPlanner.fullPlan(inputs(project, cache, true)).run();
    }

    private static BuildPlanner.Inputs inputs(Path project, Path cache, boolean skipTests) {
        return new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                0,
                null,
                null,
                skipTests,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
    }

    private static BuildPlanResult.StepReport step(BuildPlanResult result, String name) {
        return result.steps().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " step in "
                        + result.steps().stream()
                                .map(BuildPlanResult.StepReport::name)
                                .toList()));
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, text);
    }
}
