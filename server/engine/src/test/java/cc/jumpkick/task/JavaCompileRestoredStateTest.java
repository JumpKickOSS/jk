// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.engine.plugin.WorkerEnv;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A cache hit lays down the incremental state of the compile it restores, whichever checkout ran
 * that compile, so the next edit compiles what it changed and not every source the restored
 * classes and this checkout's last compile differ in.
 */
@Tag("integration")
class JavaCompileRestoredStateTest {

    private static final String A = "package a; public class A { public String use() { return new B().greet(); } }";
    private static final String A_EDITED =
            "package a; public class A { public String use() { return new B().greet() + \"!\"; } }";
    private static final String B = "package a; public class B { public String greet() { return \"hi\"; } }";
    private static final String B_NEXT = "package a; public class B { public String greet() { return \"hello\"; } }";
    private static final String C = "package a; public class C { int v() { return 1; } }";
    private static final String C_NEXT = "package a; public class C { int v() { return 2; } }";

    @Test
    void after_a_hit_from_another_checkout_a_body_edit_compiles_only_the_edited_source(@TempDir Path dir)
            throws Exception {
        Project one = new Project(dir.resolve("one"), dir.resolve("cache"));
        Project two = new Project(dir.resolve("two"), dir.resolve("cache"));
        for (Project p : List.of(one, two)) {
            p.write("a/A.java", A);
            p.write("a/B.java", B);
            p.write("a/C.java", C);
        }
        two.build();
        one.write("a/B.java", B_NEXT);
        one.write("a/C.java", C_NEXT);
        one.build();

        // Two takes one's commits, as a rebase over them would, and the build restores one's classes.
        two.write("a/B.java", B_NEXT);
        two.write("a/C.java", C_NEXT);
        assertThat(two.build().cacheHit()).isTrue();

        two.write("a/A.java", A_EDITED);
        JavaCompile.Prediction forecast = two.predict();
        assertThat(forecast.sources()).as(forecast.reason()).containsExactly(two.source("a/A.java"));
        JavaCompile.Result edit = two.build();

        assertThat(edit.compiledSources()).containsExactly(two.source("a/A.java"));
    }

    @Test
    void the_restored_state_still_sees_an_api_change_through_its_users(@TempDir Path dir) throws Exception {
        Project one = new Project(dir.resolve("one"), dir.resolve("cache"));
        Project two = new Project(dir.resolve("two"), dir.resolve("cache"));
        for (Project p : List.of(one, two)) {
            p.write("a/A.java", A);
            p.write("a/B.java", B);
            p.write("a/C.java", C);
        }
        one.build();
        assertThat(two.build().cacheHit()).isTrue();

        two.write("a/B.java", "package a; public class B { public String greet() { return \"hi\"; } void more() {} }");
        JavaCompile.Result edit = two.build();

        assertThat(edit.compiledSources()).containsExactlyInAnyOrder(two.source("a/A.java"), two.source("a/B.java"));
    }

    /** A module at {@code root} whose action cache lives under {@code cache}, which checkouts share. */
    private static final class Project {
        final Path root;
        final Path srcRoot;
        final Path out;
        final Cas cas;
        final ActionCache actionCache;
        final Path stateDir;
        final Path workerJar;

        Project(Path root, Path cache) throws IOException {
            String prop = System.getProperty("jk.java.plugin.jar");
            Assumptions.assumeTrue(
                    prop != null && Files.isRegularFile(Path.of(prop)),
                    "jk.java.plugin.jar must point at the built worker jar");
            this.root = root;
            this.srcRoot = root.resolve("src/main/java");
            this.out = root.resolve("target/classes");
            this.cas = new Cas(cache.resolve("cas"));
            this.actionCache = new ActionCache(cas, cache.resolve("actions"));
            this.stateDir = root.resolve("state");
            this.workerJar = Path.of(prop);
            Files.createDirectories(srcRoot);
            Files.writeString(root.resolve("jk.toml"), "name = \"app\"\n");
        }

        Path source(String rel) {
            return srcRoot.resolve(rel).toAbsolutePath().normalize();
        }

        void write(String rel, String body) throws IOException {
            Path f = srcRoot.resolve(rel);
            Files.createDirectories(f.getParent());
            Files.writeString(f, body);
        }

        CompileRequest request() throws IOException {
            List<Path> sources = new ArrayList<>();
            try (var s = Files.walk(srcRoot)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.toString().endsWith(".java"))
                        sources.add(p.toAbsolutePath().normalize());
                }
            }
            return CompileRequest.builder()
                    .sources(sources)
                    .classpath(List.of())
                    .outputDir(out)
                    .release(21)
                    .extraOptions(List.of())
                    .javaHome(Path.of(System.getProperty("java.home")))
                    .build();
        }

        JavaCompile.Prediction predict() throws IOException {
            return JavaCompile.predict(
                    "compile-main",
                    request(),
                    "jk-test",
                    actionCache,
                    stateDir,
                    workerJar,
                    root.resolve("gen"),
                    WorkerEnv.strict());
        }

        JavaCompile.Result build() throws IOException {
            JavaCompile.Result result = JavaCompile.run(
                    "compile-main",
                    "test:module compile-main",
                    request(),
                    "jk-test",
                    true,
                    cas,
                    actionCache,
                    stateDir,
                    workerJar,
                    root.resolve("gen"),
                    WorkerEnv.strict());
            assertThat(result.success()).as("compile succeeded").isTrue();
            return result;
        }
    }
}
