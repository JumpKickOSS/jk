// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZincJavaCompilerMixedTest {

    @Test
    void scala_hello_writes_classes_and_analysis(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("Hello.scala", "object Hello { def greet: String = \"hi\" }\n");
        ZincJavaCompiler.Result r = p.compileMixed();
        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(p.classFile("Hello.class")).isRegularFile();
        assertThat(p.workdir.resolve("zinc")).isRegularFile();
    }

    @Test
    void stdlib_comes_from_the_compiler_closure_when_compile_cp_is_empty(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("Hello.scala", "object Hello { def greet: String = \"hi\" }\n");
        ZincJavaCompiler.Result r = p.compileMixed(List.of());
        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(p.classFile("Hello.class")).isRegularFile();
    }

    @Test
    void named_classpath_jars_are_visible_to_scalac(@TempDir Path dir) throws Exception {
        // Keep the named jar outside @TempDir: Zinc holds jar locks on Windows and JUnit's
        // TempDir cleanup would fail deleting a file still open by the compiler.
        Path named = Files.createTempFile("jk-named-junit-jupiter-api-", ".jar");
        named.toFile().deleteOnExit();
        Files.copy(junitJupiterApiJar(), named, StandardCopyOption.REPLACE_EXISTING);
        Project p = new Project(dir);
        p.write("T.scala", """
                import org.junit.jupiter.api.Test
                class T:
                  @Test def ok(): Unit = ()
                """);
        List<Path> cp = new ArrayList<>(p.compileCp);
        cp.add(named);
        ZincJavaCompiler.Result r = p.compileMixed(cp);
        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(p.classFile("T.class")).isRegularFile();
    }

    @Test
    void circular_java_and_scala_compile_together(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("A.scala", """
                class A {
                  def ping: String = "a"
                  def fromB(b: B): String = b.pong
                }
                """);
        p.write("B.java", """
                public class B {
                  public String pong() { return "b"; }
                  public String fromA() { return new A().ping(); }
                }
                """);
        ZincJavaCompiler.Result r = p.compileMixed();
        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(p.classFile("A.class")).isRegularFile();
        assertThat(p.classFile("B.class")).isRegularFile();
    }

    @Test
    void body_only_scala_edit_does_not_rebuild_java(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("A.scala", """
                class A {
                  def greet: String = "hi"
                }
                """);
        p.write("B.java", """
                public class B {
                  public String use() { return new A().greet(); }
                }
                """);
        assertThat(p.compileMixed().success()).isTrue();
        byte[] b1 = Files.readAllBytes(p.classFile("B.class"));

        p.write("A.scala", """
                class A {
                  def greet: String = "hello there"
                }
                """);
        ZincJavaCompiler.Result r = p.compileMixed();
        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(Files.readAllBytes(p.classFile("B.class"))).isEqualTo(b1);
        assertThat(p.classFile("A.class")).isRegularFile();
    }

    @Test
    void scala_abi_change_recompiles_java_dependent(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("A.scala", """
                class A {
                  def greet: String = "hi"
                }
                """);
        p.write("B.java", """
                public class B {
                  public String use() { return new A().greet(); }
                }
                """);
        assertThat(p.compileMixed().success()).isTrue();

        p.write("A.scala", """
                class A {
                  def greet: String = "hi"
                  def extra: Int = 1
                }
                """);
        ZincJavaCompiler.Result r = p.compileMixed();
        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(names(r.compiledSources()))
                .as("compiled=%s diags=%s", names(r.compiledSources()), r.diagnostics())
                .contains("B.java");
    }

    private static List<String> names(List<Path> sources) {
        return sources.stream().map(p -> p.getFileName().toString()).toList();
    }

    private static final class Project {
        final Path src;
        final Path classes;
        final Path workdir;
        final List<Path> compilerCp;
        final List<Path> compileCp;

        Project(Path dir) throws IOException {
            this.src = dir.resolve("src");
            this.classes = dir.resolve("classes");
            this.workdir = dir.resolve("zinc-work");
            Files.createDirectories(src);
            this.compilerCp = scalaCompilerJars();
            this.compileCp = scalaLibraryJars(compilerCp);
        }

        void write(String rel, String contents) throws IOException {
            Path f = src.resolve(rel);
            Files.createDirectories(f.getParent());
            Files.writeString(f, contents);
        }

        Path classFile(String rel) {
            return classes.resolve(rel);
        }

        ZincJavaCompiler.Result compileMixed() throws IOException {
            return compileMixed(compileCp);
        }

        ZincJavaCompiler.Result compileMixed(List<Path> compileClasspath) throws IOException {
            List<Path> sources;
            try (var walk = Files.walk(src)) {
                sources = walk.filter(Files::isRegularFile)
                        .filter(f -> {
                            String n = f.getFileName().toString();
                            return n.endsWith(".scala") || n.endsWith(".java");
                        })
                        .toList();
            }
            return ZincJavaCompiler.compileMixed(
                    sources,
                    compileClasspath,
                    classes,
                    workdir,
                    null,
                    25,
                    List.of(),
                    List.of(),
                    "3.8.4",
                    compilerCp,
                    null,
                    null,
                    null);
        }
    }

    /**
     * Gradle puts {@code scala3-compiler_3-*.jar} on {@code java.class.path}. JumpKick's test
     * worker uses content-addressed blobs with hash filenames (no {@code .jar} suffix). Identify
     * tools by zip entries and give Zinc the artifact-prefixed names it looks up.
     */
    static List<Path> scalaCompilerJars() {
        return ToolJars.compilerClasspath();
    }

    static Path junitJupiterApiJar() {
        return ToolJars.junitJupiterApi();
    }

    static List<Path> scalaLibraryJars(List<Path> compilerCp) {
        List<Path> out = new ArrayList<>();
        for (Path p : compilerCp) {
            String n = p.getFileName().toString();
            if (n.startsWith("scala-library") || n.startsWith("scala3-library_3")) out.add(p);
        }
        return out;
    }

    private static final class ToolJars {
        private static final Path DIR = toolDir();
        private static final List<Path> COMPILER = loadCompiler();
        private static final Path JUNIT = loadJunit();

        static List<Path> compilerClasspath() {
            return COMPILER;
        }

        static Path junitJupiterApi() {
            if (JUNIT == null) throw new IllegalStateException("junit-jupiter-api not on the test classpath");
            return JUNIT;
        }

        private static Path toolDir() {
            try {
                return Files.createTempDirectory("jk-scala-tools-");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static List<Path> loadCompiler() {
            List<Path> out = new ArrayList<>();
            Path compiler = null, bridge = null, library = null;
            for (Path p : classpathFiles()) {
                String n = p.getFileName().toString();
                if (named(n, "scala3-compiler_3")
                        || named(n, "scala3-sbt-bridge")
                        || named(n, "scala-library")
                        || named(n, "scala3-library_3")
                        || named(n, "compiler-interface")
                        || n.startsWith("jline")
                        || n.startsWith("jansi")
                        || n.startsWith("tasty-core")
                        || n.startsWith("scala")) {
                    out.add(asJar(p));
                    continue;
                }
                Kind k = kind(p);
                switch (k) {
                    case COMPILER -> compiler = p;
                    case BRIDGE -> bridge = p;
                    case LIBRARY -> library = p;
                    case TOOL -> out.add(asJar(p));
                    case OTHER -> {
                        // not a Scala compiler jar
                    }
                }
            }
            if (compiler != null) out.add(namedCopy(compiler, "scala3-compiler_3.jar"));
            if (bridge != null) out.add(namedCopy(bridge, "scala3-sbt-bridge.jar"));
            if (library != null) out.add(namedCopy(library, "scala-library.jar"));
            boolean haveCompiler = false, haveBridge = false, haveLib = false;
            for (Path p : out) {
                String n = p.getFileName().toString();
                if (named(n, "scala3-compiler_3")) haveCompiler = true;
                if (named(n, "scala3-sbt-bridge")) haveBridge = true;
                if (named(n, "scala-library")) haveLib = true;
            }
            if (!haveCompiler || !haveBridge || !haveLib) {
                throw new IllegalStateException("Scala compiler jars missing on test classpath (compiler="
                        + haveCompiler
                        + " bridge="
                        + haveBridge
                        + " library="
                        + haveLib
                        + ")");
            }
            return List.copyOf(out);
        }

        private static Path loadJunit() {
            for (Path p : classpathFiles()) {
                String n = p.getFileName().toString();
                if (n.startsWith("junit-jupiter-api-") && n.endsWith(".jar")) return p;
                if (hasEntry(p, "org/junit/jupiter/api/Test.class")) {
                    return n.endsWith(".jar") ? p : namedCopy(p, "junit-jupiter-api.jar");
                }
            }
            return null;
        }

        private static List<Path> classpathFiles() {
            List<Path> out = new ArrayList<>();
            String cp = System.getProperty("java.class.path", "");
            for (String e : cp.split(File.pathSeparator)) {
                if (e == null || e.isBlank()) continue;
                Path p = Path.of(e);
                if (Files.isRegularFile(p)) out.add(p);
            }
            return out;
        }

        private static boolean named(String filename, String artifactPrefix) {
            return filename.startsWith(artifactPrefix + "-")
                    || filename.startsWith(artifactPrefix + ".")
                    || filename.equals(artifactPrefix + ".jar");
        }

        private enum Kind {
            COMPILER,
            BRIDGE,
            LIBRARY,
            TOOL,
            OTHER
        }

        private static Kind kind(Path jar) {
            try (ZipFile z = new ZipFile(jar.toFile())) {
                if (z.getEntry("dotty/tools/dotc/Compiler.class") != null) return Kind.COMPILER;
                if (z.getEntry("dotty/tools/xsbt/CompilerBridge.class") != null) return Kind.BRIDGE;
                if (z.getEntry("scala/Predef.class") != null) return Kind.LIBRARY;
                // Everything scalac itself links against: the instance loader no longer sees the
                // test JVM's classpath through a parent, so the closure has to be complete here.
                if (z.getEntry("org/jline/terminal/Terminal.class") != null
                        || z.getEntry("org/fusesource/jansi/Ansi.class") != null
                        || z.getEntry("xsbti/compile/CompilerInterface2.class") != null
                        || z.getEntry("dotty/tools/tasty/TastyReader.class") != null
                        || z.getEntry("dotty/tools/dotc/interfaces/Diagnostic.class") != null
                        || z.getEntry("scala/tools/asm/ClassReader.class") != null) {
                    return Kind.TOOL;
                }
                return Kind.OTHER;
            } catch (IOException e) {
                return Kind.OTHER;
            }
        }

        private static boolean hasEntry(Path jar, String entry) {
            try (ZipFile z = new ZipFile(jar.toFile())) {
                return z.getEntry(entry) != null;
            } catch (IOException e) {
                return false;
            }
        }

        private static Path asJar(Path p) {
            String n = p.getFileName().toString();
            if (n.endsWith(".jar")) return p;
            return namedCopy(p, Integer.toHexString(p.hashCode()) + ".jar");
        }

        private static Path namedCopy(Path src, String filename) {
            try {
                Path dest = DIR.resolve(filename);
                if (!Files.isRegularFile(dest) || Files.size(dest) != Files.size(src)) {
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                return dest;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
