// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZincJavaCompilerTest {

    @Test
    void first_compile_writes_classes_and_analysis(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/B.java", "package a; public class B { public String greet() { return \"hi\"; } }");
        p.write("a/A.java", "package a; public class A { public String use() { return new B().greet(); } }");

        ZincJavaCompiler.Result r = p.compile();
        assertThat(r.success()).isTrue();
        assertThat(p.classFile("a/A.class")).isRegularFile();
        assertThat(p.classFile("a/B.class")).isRegularFile();
        assertThat(p.workdir.resolve("zinc")).isRegularFile();
        assertThat(names(r.compiledSources())).containsExactlyInAnyOrder("A.java", "B.java");
    }

    @Test
    void body_only_edit_recompiles_only_that_source(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/B.java", "package a; public class B { public String greet() { return \"hi\"; } }");
        p.write("a/A.java", "package a; public class A { public String use() { return new B().greet(); } }");
        assertThat(p.compile().success()).isTrue();
        byte[] a1 = Files.readAllBytes(p.classFile("a/A.class"));

        p.write("a/B.java", "package a; public class B { public String greet() { return \"hello there\"; } }");
        ZincJavaCompiler.Result r = p.compile();
        assertThat(r.success()).isTrue();
        assertThat(names(r.compiledSources())).containsExactly("B.java");
        assertThat(Files.readAllBytes(p.classFile("a/A.class"))).isEqualTo(a1);
    }

    @Test
    void abi_change_recompiles_dependents(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/B.java", "package a; public class B { public String greet() { return \"hi\"; } }");
        p.write("a/A.java", "package a; public class A { public String use() { return new B().greet(); } }");
        assertThat(p.compile().success()).isTrue();

        p.write(
                "a/B.java",
                "package a; public class B { public String greet() { return \"hi\"; } public int n() { return 1; } }");
        ZincJavaCompiler.Result r = p.compile();
        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(names(r.compiledSources()))
                .as("compiled=%s diags=%s", names(r.compiledSources()), r.diagnostics())
                .contains("A.java", "B.java");
    }

    @Test
    void plan_after_body_edit_lists_only_the_changed_source(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/B.java", "package a; public class B { public String greet() { return \"hi\"; } }");
        p.write("a/A.java", "package a; public class A { public String use() { return new B().greet(); } }");
        assertThat(p.compile().success()).isTrue();

        p.write("a/B.java", "package a; public class B { public String greet() { return \"hello there\"; } }");
        ZincJavaCompiler.Plan plan = p.plan();
        assertThat(plan.full()).isFalse();
        assertThat(names(plan.sources())).containsExactly("B.java");
        assertThat(plan.reason()).contains("source");
    }

    private static List<String> names(List<Path> sources) {
        return sources.stream().map(p -> p.getFileName().toString()).toList();
    }

    private static final class Project {
        final Path src;
        final Path classes;
        final Path workdir;

        Project(Path dir) throws IOException {
            this.src = dir.resolve("src");
            this.classes = dir.resolve("classes");
            this.workdir = dir.resolve("zinc-work");
            Files.createDirectories(src);
        }

        void write(String rel, String contents) throws IOException {
            Path f = src.resolve(rel);
            Files.createDirectories(f.getParent());
            Files.writeString(f, contents);
        }

        Path classFile(String rel) {
            return classes.resolve(rel);
        }

        ZincJavaCompiler.Result compile() throws IOException {
            List<Path> sources;
            try (var walk = Files.walk(src)) {
                sources = walk.filter(f -> f.toString().endsWith(".java")).toList();
            }
            return ZincJavaCompiler.compileJava(sources, List.of(), classes, workdir, null, 25, List.of(), List.of());
        }

        ZincJavaCompiler.Plan plan() throws IOException {
            List<Path> sources;
            try (var walk = Files.walk(src)) {
                sources = walk.filter(f -> f.toString().endsWith(".java")).toList();
            }
            return ZincJavaCompiler.planJava(sources, List.of(), classes, workdir, null, 25, List.of(), List.of());
        }
    }
}
