// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.groovy.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real Groovy 5 compiler (on the test classpath) through the plugin's compile path. */
class GroovyCompilerTest {

    @Test
    void compiles_groovy_to_classes(@TempDir Path dir) throws Exception {
        Path src = write(dir.resolve("src/Greeter.groovy"), """
                class Greeter {
                    String greet(String name) { "hi " + name }
                }
                """);
        Path out = dir.resolve("classes");

        Run run = compile(dir, sw -> sw.layout(Map.of("classesDir", out))
                .configString("jvmTarget", "25")
                .source(src));

        assertThat(run.exit).as("diagnostics: %s", run.protocol).isZero();
        assertThat(out.resolve("Greeter.class")).isRegularFile();
        assertThat(run.protocol).contains("COMPILATION_SUCCESS");
    }

    @Test
    void joint_mode_resolves_java_both_ways_and_populates_stubs(@TempDir Path dir) throws Exception {
        // groovy → java: Wraps uses J; java → groovy: JUser uses Wraps (via the generated stub).
        Path j = write(dir.resolve("src/J.java"), """
                public class J {
                    public String name() { return "j"; }
                }
                """);
        Path jUser = write(dir.resolve("src/JUser.java"), """
                public class JUser {
                    public String via(Wraps w) { return w.name(); }
                }
                """);
        Path g = write(dir.resolve("src/Wraps.groovy"), """
                class Wraps {
                    J j = new J()
                    String name() { j.name() }
                }
                """);
        Path out = dir.resolve("classes");
        Path stubs = dir.resolve("stubs");
        Path work = dir.resolve("work");

        Run run = compile(dir, sw -> sw.layout(Map.of("classesDir", out, "workdir", work))
                .extra("stubsOut", stubs)
                .configString("jvmTarget", "25")
                .source(g)
                .source(j)
                .source(jUser));

        assertThat(run.exit).as("diagnostics: %s", run.protocol).isZero();
        assertThat(out.resolve("Wraps.class")).isRegularFile();
        // The stub for the groovy class is retained in stubsOut.
        assertThat(stubs.resolve("Wraps.java")).isRegularFile();
        // javac's .class output for the Java sources goes to the discard dir, never OUTPUT —
        // jk's javac step owns the real Java outputs.
        assertThat(out.resolve("J.class")).doesNotExist();
        assertThat(out.resolve("JUser.class")).doesNotExist();
        assertThat(work.resolve("javac-classes").resolve("J.class")).isRegularFile();
    }

    @Test
    void the_engine_listed_java_neighborhood_feeds_joint_resolution(@TempDir Path dir) throws Exception {
        // The engine puts the roots' .java on SOURCE (GroovycInputs) — the worker compiles the
        // list verbatim, so a listed neighbor resolves…
        Path j = write(dir.resolve("src/java/dep/Helper.java"), """
                package dep;
                public class Helper {
                    public static int answer() { return 42; }
                }
                """);
        Path g = write(dir.resolve("src/groovy/Uses.groovy"), """
                @groovy.transform.CompileStatic
                class Uses {
                    int answer() { dep.Helper.answer() }
                }
                """);
        Path out = dir.resolve("classes");

        Run run = compile(dir, sw -> sw.layout(Map.of("classesDir", out, "workdir", dir.resolve("work")))
                .configString("jvmTarget", "25")
                .source(g)
                .source(j));

        assertThat(run.exit).as("diagnostics: %s", run.protocol).isZero();
        assertThat(out.resolve("Uses.class")).isRegularFile();
        assertThat(out.resolve("dep/Helper.class")).doesNotExist();
    }

    @Test
    void a_java_file_on_disk_but_not_on_source_is_not_compiled(@TempDir Path dir) throws Exception {
        // …and an unlisted one does not: the worker never walks a tree, or it would compile
        // files the engine's action key never hashed.
        write(dir.resolve("src/java/dep/Helper.java"), """
                package dep;
                public class Helper {
                    public static int answer() { return 42; }
                }
                """);
        Path g = write(dir.resolve("src/groovy/Uses.groovy"), """
                @groovy.transform.CompileStatic
                class Uses {
                    int answer() { dep.Helper.answer() }
                }
                """);
        Path out = dir.resolve("classes");

        Run run = compile(dir, sw -> sw.layout(Map.of("classesDir", out, "workdir", dir.resolve("work")))
                .configString("jvmTarget", "25")
                .source(g));

        assertThat(run.exit)
                .as("an unlisted neighbor must be unresolvable, not silently swept in")
                .isNotZero();
    }

    @Test
    void parameters_flag_is_reflected_in_emitted_bytecode(@TempDir Path dir) throws Exception {
        Path src = write(dir.resolve("src/Adder.groovy"), """
                class Adder {
                    int add(int alpha, int beta) { alpha + beta }
                }
                """);
        Path out = dir.resolve("classes");

        Run run = compile(dir, sw -> sw.layout(Map.of("classesDir", out))
                .configString("jvmTarget", "25")
                .source(src)
                .arg("--parameters"));
        assertThat(run.exit).as("diagnostics: %s", run.protocol).isZero();

        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {out.toUri().toURL()}, GroovyCompilerTest.class.getClassLoader())) {
            Method add = loader.loadClass("Adder").getDeclaredMethod("add", int.class, int.class);
            Parameter[] params = add.getParameters();
            assertThat(params[0].isNamePresent()).isTrue();
            assertThat(params[0].getName()).isEqualTo("alpha");
            assertThat(params[1].getName()).isEqualTo("beta");
        }
    }

    @Test
    void without_parameters_flag_names_are_absent(@TempDir Path dir) throws Exception {
        Path src = write(dir.resolve("src/Adder.groovy"), """
                class Adder {
                    int add(int alpha, int beta) { alpha + beta }
                }
                """);
        Path out = dir.resolve("classes");
        Run run = compile(dir, sw -> sw.layout(Map.of("classesDir", out))
                .configString("jvmTarget", "25")
                .source(src));
        assertThat(run.exit).isZero();

        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {out.toUri().toURL()}, GroovyCompilerTest.class.getClassLoader())) {
            Method add = loader.loadClass("Adder").getDeclaredMethod("add", int.class, int.class);
            assertThat(add.getParameters()[0].isNamePresent()).isFalse();
        }
    }

    /**
     * A joint compile's stubs and swept javac output are side products. With no workdir in the spec
     * they go to a temp directory that exists only for the compile.
     */
    @Test
    void a_joint_compile_without_a_workdir_leaves_no_scratch_directory_behind(@TempDir Path dir) throws Exception {
        Path j = write(dir.resolve("src/J.java"), """
                public class J {
                    public String name() { return "j"; }
                }
                """);
        Path g = write(dir.resolve("src/Wraps.groovy"), """
                class Wraps {
                    String name() { new J().name() }
                }
                """);
        Path out = dir.resolve("classes");
        Set<Path> before = scratchDirs();

        Run run = compile(dir, sw -> sw.layout(Map.of("classesDir", out))
                .configString("jvmTarget", "25")
                .source(g)
                .source(j));

        assertThat(run.exit).as("diagnostics: %s", run.protocol).isZero();
        assertThat(out.resolve("Wraps.class")).isRegularFile();
        assertThat(scratchDirs()).isEqualTo(before);
    }

    /** Every {@code jk-groovyc-*} directory in the JVM's temp dir right now. */
    private static Set<Path> scratchDirs() throws IOException {
        Path tmpdir = Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir")));
        try (var children = Files.list(tmpdir)) {
            return children.filter(p -> p.getFileName().toString().startsWith("jk-groovyc-"))
                    .collect(Collectors.toSet());
        }
    }

    @Test
    void compile_error_reports_diagnostic_and_exits_1(@TempDir Path dir) throws Exception {
        Path src = write(dir.resolve("src/Broken.groovy"), """
                class Broken {
                    def f( { }
                }
                """);
        Path out = dir.resolve("classes");

        Run run = compile(dir, sw -> sw.layout(Map.of("classesDir", out))
                .configString("jvmTarget", "25")
                .source(src));

        assertThat(run.exit).isEqualTo(1);
        assertThat(run.protocol).contains("\"sev\":\"ERROR\"");
        assertThat(run.protocol).contains("COMPILATION_ERROR");
    }

    // ---------------------------------------------------------------------------------------

    private record Run(int exit, String protocol) {}

    private static Run compile(Path dir, Consumer<SpecWriter> customize) throws Exception {
        SpecWriter sw = new SpecWriter().op(PluginProtocol.OP_COMPILE, null, "jk-groovy-compiler");
        customize.accept(sw);
        Path spec = dir.resolve("spec-" + System.nanoTime() + ".spec");
        Files.write(spec, sw.lines());

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ProtocolWriter writer = new ProtocolWriter(new PrintStream(captured, true, StandardCharsets.UTF_8), "##JKGC:");
        int exit = new GroovyCompiler().run(List.of("@" + spec), writer);
        return new Run(exit, captured.toString(StandardCharsets.UTF_8));
    }

    private static Path write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        return file;
    }
}
