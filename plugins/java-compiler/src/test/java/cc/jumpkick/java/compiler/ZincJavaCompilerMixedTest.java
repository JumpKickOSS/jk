// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
                    null);
        }
    }

    static List<Path> scalaCompilerJars() {
        List<Path> out = new ArrayList<>();
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path p = Path.of(e);
            if (!Files.isRegularFile(p)) continue;
            String n = p.getFileName().toString();
            if (!n.endsWith(".jar")) continue;
            if (n.startsWith("scala")
                    || n.startsWith("compiler-interface")
                    || n.contains("sbt-bridge")
                    || n.startsWith("jline")
                    || n.startsWith("jansi")) {
                out.add(p);
            }
        }
        return out;
    }

    static List<Path> scalaLibraryJars(List<Path> compilerCp) {
        List<Path> out = new ArrayList<>();
        for (Path p : compilerCp) {
            String n = p.getFileName().toString();
            if (n.startsWith("scala3-library_3-") || n.startsWith("scala-library-")) out.add(p);
        }
        return out;
    }
}
