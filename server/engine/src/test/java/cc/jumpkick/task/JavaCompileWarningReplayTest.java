// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.PathUtil;
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
 * A compile that does not run javac on a source still says that source's warnings: a cache hit
 * says every warning its compile said, and an incremental compile says those of the sources it left
 * alone, as their last compile said them.
 */
@Tag("integration")
class JavaCompileWarningReplayTest {

    private static final String OLD = "package a; public class Old { @Deprecated public static void f() {} }";
    private static final String USES_OLD = "package a; public class A { void use() { Old.f(); } }";
    private static final String CLEAN_B = "package a; public class B { int v() { return 1; } }";

    @Test
    void a_cache_hit_says_the_warnings_its_compile_said(@TempDir Path dir) throws Exception {
        Project p = new Project(dir.resolve("app"), dir.resolve("cache"));
        p.write("a/Old.java", OLD);
        p.write("a/A.java", USES_OLD);
        JavaCompile.Result first = p.build();
        assertThat(texts(first.diagnostics())).singleElement().asString().contains("f() in a.Old has been deprecated");

        p.wipeOutputs();
        JavaCompile.Result hit = p.build();

        assertThat(hit.cacheHit()).isTrue();
        assertThat(hit.diagnostics()).isEmpty();
        assertThat(texts(hit.replayed())).isEqualTo(texts(first.diagnostics()));
        assertThat(hit.replayed().getFirst().source()).isEqualTo(p.source("a/A.java"));
    }

    @Test
    void an_incremental_compile_keeps_the_warnings_of_the_sources_it_left_alone(@TempDir Path dir) throws Exception {
        Project p = new Project(dir.resolve("app"), dir.resolve("cache"));
        p.write("a/Old.java", OLD);
        p.write("a/A.java", USES_OLD);
        p.write("a/B.java", CLEAN_B);
        JavaCompile.Result first = p.build();

        p.write("a/B.java", "package a; public class B { int v() { return 2; } }");
        JavaCompile.Result second = p.build();

        assertThat(second.compiledSources()).containsExactly(p.source("a/B.java"));
        assertThat(second.diagnostics()).isEmpty();
        assertThat(texts(second.replayed())).isEqualTo(texts(first.diagnostics()));
        assertThat(texts(second.warnings())).isEqualTo(texts(first.diagnostics()));
    }

    @Test
    void fixing_the_source_drops_its_warning(@TempDir Path dir) throws Exception {
        Project p = new Project(dir.resolve("app"), dir.resolve("cache"));
        p.write("a/Old.java", OLD);
        p.write("a/A.java", USES_OLD);
        p.write("a/B.java", CLEAN_B);
        p.build();

        p.write("a/A.java", "package a; public class A { void use() {} }");
        JavaCompile.Result fixed = p.build();

        assertThat(fixed.warnings()).isEmpty();
        p.write("a/B.java", "package a; public class B { int v() { return 3; } }");
        assertThat(p.build().warnings()).as("not brought back by a later pass").isEmpty();
    }

    @Test
    void deleting_the_source_drops_its_warning(@TempDir Path dir) throws Exception {
        Project p = new Project(dir.resolve("app"), dir.resolve("cache"));
        p.write("a/Old.java", OLD);
        p.write("a/A.java", USES_OLD);
        p.write("a/B.java", CLEAN_B);
        p.build();

        Files.delete(p.source("a/A.java"));
        assertThat(p.build().warnings()).isEmpty();
    }

    @Test
    void a_record_written_in_another_checkout_names_this_checkouts_file(@TempDir Path dir) throws Exception {
        Project one = new Project(dir.resolve("one"), dir.resolve("cache"));
        Project two = new Project(dir.resolve("two"), dir.resolve("cache"));
        for (Project p : List.of(one, two)) {
            p.write("a/Old.java", OLD);
            p.write("a/A.java", USES_OLD);
        }
        one.build();

        JavaCompile.Result hit = two.build();

        assertThat(hit.cacheHit()).isTrue();
        CompileResult.Diagnostic warning = hit.replayed().getFirst();
        assertThat(warning.source()).isEqualTo(two.source("a/A.java"));
        assertThat(warning.message())
                .contains(two.source("a/A.java").toString())
                .doesNotContain(one.root.toString());
    }

    @Test
    void state_without_its_ledger_compiles_every_source(@TempDir Path dir) throws Exception {
        Project p = new Project(dir.resolve("app"), dir.resolve("cache"));
        p.write("a/Old.java", OLD);
        p.write("a/A.java", USES_OLD);
        p.write("a/B.java", CLEAN_B);
        JavaCompile.Result first = p.build();
        Files.delete(p.stateDir.resolve(CompileWarnings.LEDGER));

        p.write("a/B.java", "package a; public class B { int v() { return 2; } }");
        JavaCompile.Result second = p.build();

        assertThat(second.compiledSources()).hasSize(3);
        assertThat(texts(second.diagnostics())).isEqualTo(texts(first.diagnostics()));
    }

    private static List<String> texts(List<CompileResult.Diagnostic> diagnostics) {
        List<String> out = new ArrayList<>();
        for (CompileResult.Diagnostic d : diagnostics) out.add(d.message());
        return out;
    }

    /** A module at {@code root} whose action cache lives under {@code cache}, which checkouts may share. */
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

        void wipeOutputs() throws IOException {
            PathUtil.deleteRecursively(out);
            PathUtil.deleteRecursively(stateDir);
        }

        JavaCompile.Result build() throws IOException {
            List<Path> sources = new ArrayList<>();
            try (var s = Files.walk(srcRoot)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.toString().endsWith(".java"))
                        sources.add(p.toAbsolutePath().normalize());
                }
            }
            CompileRequest request = CompileRequest.builder()
                    .sources(sources)
                    .classpath(List.of())
                    .outputDir(out)
                    .release(21)
                    .extraOptions(List.of("-Xlint:deprecation"))
                    .javaHome(Path.of(System.getProperty("java.home")))
                    .build();
            JavaCompile.Result result = JavaCompile.run(
                    "compile-main",
                    "test:module compile-main",
                    request,
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
