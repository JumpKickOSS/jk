// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.PathUtil;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An analysis one checkout wrote, laid beside the same classes in another checkout, carries that
 * checkout's next compile: a body edit recompiles the edited source alone.
 */
class PortableAnalysisTest {

    private static final String B = "package a; public class B { public String greet() { return \"hi\"; } }";
    private static final String A = "package a; public class A { public String use() { return new B().greet(); } }";
    private static final String B_EDITED =
            "package a; public class B { public String greet() { return \"hello there\"; } }";

    @Test
    void an_analysis_written_in_one_checkout_carries_the_next_compile_of_another(@TempDir Path dir) throws Exception {
        Checkout one = new Checkout(dir.resolve("one"), true);
        one.write("a/B.java", B);
        one.write("a/A.java", A);
        assertThat(one.compile().success()).isTrue();

        Checkout two = one.copyTo(dir.resolve("two"));
        two.write("a/B.java", B_EDITED);

        ZincJavaCompiler.Plan plan = two.plan();
        assertThat(plan.full()).as(plan.reason()).isFalse();
        assertThat(names(plan.sources())).containsExactly("B.java");
        ZincJavaCompiler.Result r = two.compile();
        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(names(r.compiledSources())).containsExactly("B.java");
        assertThat(two.classes.resolve("a/A.class")).isRegularFile();
    }

    @Test
    void the_written_analysis_names_no_path_under_its_root(@TempDir Path dir) throws Exception {
        Checkout one = new Checkout(dir.resolve("one"), true);
        one.write("a/B.java", B);
        one.write("a/A.java", A);
        assertThat(one.compile().success()).isTrue();

        String text;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(one.workdir.resolve("zinc")))) {
            text = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        assertThat(text).contains(PortableAnalysis.MARK).doesNotContain(one.root.toString());
    }

    @Test
    void without_a_root_the_copied_analysis_names_the_other_checkout(@TempDir Path dir) throws Exception {
        Checkout one = new Checkout(dir.resolve("one"), false);
        one.write("a/B.java", B);
        one.write("a/A.java", A);
        assertThat(one.compile().success()).isTrue();

        Checkout two = one.copyTo(dir.resolve("two"));
        two.write("a/B.java", B_EDITED);

        assertThat(names(two.compile().compiledSources())).containsExactlyInAnyOrder("A.java", "B.java");
    }

    @Test
    void a_producer_analysis_is_read_under_its_own_root(@TempDir Path dir) throws Exception {
        Checkout one = new Checkout(dir.resolve("one"), true);
        one.write("a/B.java", B);
        assertThat(one.compile().success()).isTrue();
        Checkout two = one.copyTo(dir.resolve("two"));

        ProducerAnalysisCache cache = new ProducerAnalysisCache(Long.MAX_VALUE);
        var products = cache.load(two.workdir.resolve("zinc"))
                .orElseThrow()
                .relations()
                .allProducts()
                .iterator();
        assertThat(products.next().id())
                .isEqualTo(two.classes.resolve("a/B.class").toString());
    }

    @Test
    void only_a_whole_path_segment_is_rebased() {
        String sep = File.separator;
        String root = sep + "src" + sep + "jk";
        assertThat(PortableAnalysis.replace(root + sep + "a", root, "${root}")).isEqualTo("${root}" + sep + "a");
        assertThat(PortableAnalysis.replace(root, root, "${root}")).isEqualTo("${root}");
        assertThat(PortableAnalysis.replace(root + "2" + sep + "a", root, "${root}"))
                .isEqualTo(root + "2" + sep + "a");
        assertThat(PortableAnalysis.replace(
                        "-s" + root + sep + "gen" + Classpaths.SEPARATOR + root + sep + "b", root, "${root}"))
                .isEqualTo("-s${root}" + sep + "gen" + File.pathSeparator + "${root}" + sep + "b");
    }

    private static List<String> names(List<Path> paths) {
        return paths.stream().map(p -> p.getFileName().toString()).toList();
    }

    /** A module checked out at {@code root}, its workdir naming that root when {@code rooted}. */
    private static final class Checkout {
        final Path root;
        final Path src;
        final Path classes;
        final Path workdir;
        final boolean rooted;

        Checkout(Path root, boolean rooted) throws IOException {
            this.root = root;
            this.src = root.resolve("src");
            this.classes = root.resolve("target/classes");
            this.workdir = root.resolve("state");
            this.rooted = rooted;
            Files.createDirectories(src);
            Files.createDirectories(workdir);
            if (rooted) Files.writeString(workdir.resolve(PortableAnalysis.ROOT_FILE), root + "\n");
        }

        void write(String rel, String contents) throws IOException {
            Path f = src.resolve(rel);
            Files.createDirectories(f.getParent());
            Files.writeString(f, contents);
        }

        /** The sources, classes and analysis copied to a checkout at {@code to}, whose workdir names its own root. */
        Checkout copyTo(Path to) throws IOException {
            Checkout copy = new Checkout(to, rooted);
            copyTree(src, copy.src);
            copyTree(classes, copy.classes);
            Files.copy(workdir.resolve("zinc"), copy.workdir.resolve("zinc"));
            return copy;
        }

        private static void copyTree(Path from, Path to) throws IOException {
            PathUtil.forEachRegularFile(from, (file, attrs) -> {
                Path target = to.resolve(from.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            });
        }

        ZincJavaCompiler.Result compile() throws IOException {
            return ZincJavaCompiler.compileJava(job());
        }

        ZincJavaCompiler.Plan plan() throws IOException {
            return ZincJavaCompiler.planJava(job());
        }

        private JavaCompileJob job() throws IOException {
            List<Path> sources;
            try (var walk = Files.walk(src)) {
                sources = walk.filter(f -> f.toString().endsWith(".java")).toList();
            }
            return new JavaCompileJob(
                    sources, List.of(), classes, workdir, null, 25, List.of(), List.of(), null, Map.of());
        }
    }
}
