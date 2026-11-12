// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sbt.internal.inc.PlainVirtualFileConverter;
import sbt.internal.inc.Stamps;
import xsbti.compile.analysis.ReadStamps;

/**
 * Producer analyses are decoded once per worker, not once per compile, while each compile still
 * checks the decoded analysis against the producer's classes on disk.
 */
class ProducerAnalysisCacheTest {

    private static final String T = "package lib; public class T { public int a() { return 1; } }";
    private static final String T2 =
            "package lib; public class T { public int a() { return 1; } public int b() { return 2; } }";

    @Test
    void two_compiles_over_one_unchanged_analysis_decode_it_once(@TempDir Path dir) throws IOException {
        Producer lib = Producer.compiled(dir.resolve("lib"), T);
        ProducerAnalysisCache cache = new ProducerAnalysisCache(1L << 30);

        assertThat(lib.analyses(cache).analyzedClass("lib.T").isDefined()).isTrue();
        assertThat(lib.analyses(cache).analyzedClass("lib.T").isDefined()).isTrue();
        assertThat(cache.decodes()).isEqualTo(1);
    }

    @Test
    void a_rewritten_analysis_is_decoded_fresh(@TempDir Path dir) throws IOException {
        Producer lib = Producer.compiled(dir.resolve("lib"), T);
        ProducerAnalysisCache cache = new ProducerAnalysisCache(1L << 30);
        int before = lib.analyses(cache).analyzedClass("lib.T").get().apiHash();
        FileTime mtime = Files.getLastModifiedTime(lib.analysis);

        lib.compile(T2);
        // Same mtime, new bytes: the key is the content, not the file's metadata.
        Files.setLastModifiedTime(lib.analysis, mtime);
        int after = lib.analyses(cache).analyzedClass("lib.T").get().apiHash();

        assertThat(cache.decodes()).isEqualTo(2);
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void a_product_rewritten_under_a_cached_analysis_is_still_rejected(@TempDir Path dir) throws IOException {
        Producer lib = Producer.compiled(dir.resolve("lib"), T);
        ProducerAnalysisCache cache = new ProducerAnalysisCache(1L << 30);
        assertThat(lib.analyses(cache).analyzedClass("lib.T").isDefined()).isTrue();

        // What an action-cache restore does: the classes change, the analysis file does not.
        Path product = lib.classes.resolve("lib/T.class");
        Files.write(product, new byte[] {(byte) 0xCA, (byte) 0xFE});
        Files.setLastModifiedTime(
                product, FileTime.fromMillis(Files.getLastModifiedTime(product).toMillis() + 5_000));

        assertThat(lib.analyses(cache).analyzedClass("lib.T").isDefined()).isFalse();
        assertThat(cache.decodes()).isEqualTo(1);
    }

    @Test
    void the_bound_evicts_the_least_recently_used_analysis(@TempDir Path dir) throws IOException {
        Producer a = Producer.compiled(dir.resolve("a"), T);
        Producer b = Producer.compiled(dir.resolve("b"), T2);
        long weight =
                Math.max(Files.size(a.analysis), Files.size(b.analysis)) * ProducerAnalysisCache.DECODED_PER_COMPRESSED;
        // Room for one of the two analyses, not both.
        ProducerAnalysisCache cache = new ProducerAnalysisCache(weight + weight / 2);

        assertThat(cache.load(a.analysis)).isPresent();
        assertThat(cache.load(b.analysis)).isPresent();
        assertThat(cache.load(b.analysis)).isPresent();
        assertThat(cache.decodes()).isEqualTo(2);
        assertThat(cache.load(a.analysis)).isPresent();
        assertThat(cache.decodes()).isEqualTo(3);
    }

    @Test
    void the_idle_trim_empties_it(@TempDir Path dir) throws IOException {
        Producer lib = Producer.compiled(dir.resolve("lib"), T);
        ProducerAnalysisCache cache = new ProducerAnalysisCache(1L << 30);
        assertThat(cache.load(lib.analysis)).isPresent();

        cache.clear();

        assertThat(cache.load(lib.analysis)).isPresent();
        assertThat(cache.decodes()).isEqualTo(2);
    }

    @Test
    void a_missing_or_corrupt_file_is_no_analysis(@TempDir Path dir) throws IOException {
        ProducerAnalysisCache cache = new ProducerAnalysisCache(1L << 30);
        Path garbage = Files.writeString(dir.resolve("zinc"), "not a zinc analysis");

        assertThat(cache.load(dir.resolve("absent"))).isEmpty();
        assertThat(cache.load(garbage)).isEmpty();
        assertThat(cache.load(dir)).isEmpty();
    }

    /** A compiled module whose analysis a consumer would be handed. */
    private static final class Producer {
        final Path src;
        final Path classes;
        final Path workdir;
        final Path analysis;

        private Producer(Path dir) {
            this.src = dir.resolve("src");
            this.classes = dir.resolve("classes");
            this.workdir = dir.resolve("zinc-work");
            this.analysis = workdir.resolve("zinc");
        }

        static Producer compiled(Path dir, String t) throws IOException {
            Producer p = new Producer(dir);
            p.compile(t);
            return p;
        }

        void compile(String t) throws IOException {
            Path f = src.resolve("lib/T.java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, t);
            ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(
                    new JavaCompileJob(List.of(f), List.of(), classes, workdir, null, 25, List.of(), List.of()));
            assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        }

        /** What one consumer compile builds over this producer's classes dir. */
        ClasspathAnalyses analyses(ProducerAnalysisCache cache) {
            return ClasspathAnalyses.of(Map.of(classes, analysis), freshStamper(), cache);
        }
    }

    /** A stamper of its own per compile, so no mtime-cached hash hides a rewritten product. */
    private static ReadStamps freshStamper() {
        return Stamps.timeWrapBinaryStamps(PlainVirtualFileConverter.converter());
    }
}
