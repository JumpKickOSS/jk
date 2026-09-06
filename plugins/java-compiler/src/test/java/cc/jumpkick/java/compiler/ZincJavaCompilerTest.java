// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    /**
     * The dependent compile sees the dependency through a classpath <em>directory</em>, the way a
     * test compile sees its module's own main classes. Zinc hashes such an entry to a constant, so
     * without jk's own library-change detection an in-place rewrite of a class inside it never
     * invalidates the classes compiled against it — a test that inlined a main constant kept the old
     * value forever.
     */
    @Test
    void a_class_rewritten_inside_a_classpath_directory_recompiles_its_users(@TempDir Path dir) throws Exception {
        Project main = new Project(dir.resolve("main"));
        main.write("k/K.java", "package k; public class K { public static final int SALT = 1; }");
        assertThat(main.compile().success()).isTrue();

        Project test = new Project(dir.resolve("test"));
        test.write("k/KTest.java", "package k; public class KTest { public static int salt() { return K.SALT; } }");
        ZincJavaCompiler.Result first = test.compile(List.of(main.classes));
        assertThat(first.success()).as(first.diagnostics().toString()).isTrue();
        byte[] before = Files.readAllBytes(test.classFile("k/KTest.class"));

        main.write("k/K.java", "package k; public class K { public static final int SALT = 2; }");
        assertThat(names(main.compile().compiledSources())).containsExactly("K.java");

        ZincJavaCompiler.Plan plan = test.plan(List.of(main.classes));
        assertThat(names(plan.sources())).as(plan.reason()).containsExactly("KTest.java");

        ZincJavaCompiler.Result second = test.compile(List.of(main.classes));
        assertThat(second.success()).as(second.diagnostics().toString()).isTrue();
        assertThat(names(second.compiledSources())).containsExactly("KTest.java");
        assertThat(Files.readAllBytes(test.classFile("k/KTest.class"))).isNotEqualTo(before);

        // And nothing changed → nothing recompiled: the detection is not a blanket invalidation.
        ZincJavaCompiler.Result third = test.compile(List.of(main.classes));
        assertThat(third.success()).isTrue();
        assertThat(third.compiledSources()).isEmpty();
    }

    /** The same dependency at a new path (a version bump): Zinc's origin lookup must still run. */
    @Test
    void a_dependency_moved_to_another_classpath_directory_recompiles_its_users(@TempDir Path dir) throws Exception {
        Project v1 = new Project(dir.resolve("v1"));
        v1.write("d/Lib.java", "package d; public class Lib { public void f(Object o) {} }");
        assertThat(v1.compile().success()).isTrue();
        Project v2 = new Project(dir.resolve("v2"));
        v2.write("d/Lib.java", "package d; public class Lib { public void f(Object o) {} public void f(String s) {} }");
        assertThat(v2.compile().success()).isTrue();

        Project user = new Project(dir.resolve("user"));
        user.write("u/U.java", "package u; public class U { public void call(d.Lib lib) { lib.f(\"hi\"); } }");
        assertThat(user.compile(List.of(v1.classes)).success()).isTrue();

        ZincJavaCompiler.Result bumped = user.compile(List.of(v2.classes));
        assertThat(bumped.success()).as(bumped.diagnostics().toString()).isTrue();
        assertThat(names(bumped.compiledSources())).containsExactly("U.java");
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

    @Test
    void full_recompile_without_analysis_deletes_a_removed_sources_class(@TempDir Path dir) throws Exception {
        // An analysis-less full compile (aggregating-AP wipe, or a jk-version bump that
        // cleared state) must start from a clean class output, or a removed source's .class lingers.
        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A {}");
        p.write("a/B.java", "package a; public class B {}");
        assertThat(p.compile().success()).isTrue();
        assertThat(p.classFile("a/B.class")).isRegularFile();

        Files.delete(p.src.resolve("a/B.java"));
        Files.delete(p.workdir.resolve("zinc")); // force an analysis-less full compile

        assertThat(p.compile().success()).isTrue();
        assertThat(p.classFile("a/A.class")).isRegularFile();
        assertThat(p.classFile("a/B.class")).doesNotExist();
    }

    @Test
    void corrupt_analysis_falls_back_to_a_full_compile(@TempDir Path dir) throws Exception {
        // A truncated/incompatible analysis file must not fail every build persistently.
        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A {}");
        assertThat(p.compile().success()).isTrue();

        Path zinc = p.workdir.resolve("zinc");
        Files.delete(zinc);
        Files.writeString(zinc, "not a valid zinc analysis store");

        ZincJavaCompiler.Result r = p.compile();
        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(p.classFile("a/A.class")).isRegularFile();
        assertThat(p.workdir.resolve("zinc")).isRegularFile(); // a fresh analysis was written

        // plan() over the recovered analysis must also not throw
        ZincJavaCompiler.Plan plan = p.plan();
        assertThat(plan).isNotNull();
    }

    @Test
    void unreadable_analysis_is_removed_so_the_next_compile_can_replace_it(@TempDir Path dir) throws Exception {
        Project p = new Project(dir);
        p.write("a/A.java", "package a; public class A {}");
        assertThat(p.compile().success()).isTrue();

        Path zinc = p.workdir.resolve("zinc");
        byte[] garbage = "not a valid zinc analysis store".getBytes(StandardCharsets.UTF_8);
        Files.delete(zinc);
        Files.write(zinc, garbage);

        // Reading is what removes it. Left in place, a file that cannot be parsed still has to be
        // replaced by the next store.set — the write Windows can refuse while a handle lingers.
        assertThat(p.plan().full()).isTrue();
        assertThat(zinc).doesNotExist();

        ZincJavaCompiler.Result r = p.compile();
        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(Files.readAllBytes(zinc)).isNotEqualTo(garbage);
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
            return compile(List.of());
        }

        ZincJavaCompiler.Result compile(List<Path> classpath) throws IOException {
            return ZincJavaCompiler.compileJava(job(classpath));
        }

        ZincJavaCompiler.Plan plan() throws IOException {
            return plan(List.of());
        }

        ZincJavaCompiler.Plan plan(List<Path> classpath) throws IOException {
            return ZincJavaCompiler.planJava(job(classpath));
        }

        private JavaCompileJob job(List<Path> classpath) throws IOException {
            List<Path> sources;
            try (var walk = Files.walk(src)) {
                sources = walk.filter(f -> f.toString().endsWith(".java")).toList();
            }
            return new JavaCompileJob(sources, classpath, classes, workdir, null, 25, List.of(), List.of());
        }
    }
}
