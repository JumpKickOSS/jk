// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic coverage of the resolved-closure cache (the warm-build fast path), which
 * {@link KotlinBtaResolver} and {@link GroovyToolResolver} now share. The Maven resolution itself
 * is network-bound and exercised end-to-end by the higher-level Kotlin and Groovy build tests.
 * The per-language halves — the supported-version floors — stay in the two resolver tests.
 */
class ToolClosureTest {

    @Test
    void round_trips_a_recorded_closure(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cache"));
        List<String> shas = seedBlobs(cas, "alpha", "beta", "gamma");
        Path cacheFile = dir.resolve("closure.shas");

        ToolClosure.writeCachedClosure(cacheFile, shas);
        List<Path> jars = requireNonNull(ToolClosure.readCachedClosure(cacheFile, cas));
        assertThat(jars).hasSize(3);
        // Each recorded hash maps back to its CAS path, in order.
        for (int i = 0; i < shas.size(); i++) {
            assertThat(jars.get(i)).isEqualTo(cas.pathFor(shas.get(i)));
            assertThat(jars.get(i)).isRegularFile();
        }
    }

    @Test
    void absent_cache_file_is_a_miss(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cache"));
        assertThat(ToolClosure.readCachedClosure(dir.resolve("nope.shas"), cas)).isNull();
    }

    @Test
    void evicted_blob_invalidates_the_whole_closure(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cache"));
        List<String> shas = seedBlobs(cas, "one", "two");
        Path cacheFile = dir.resolve("closure.shas");
        ToolClosure.writeCachedClosure(cacheFile, shas);

        // Evict one blob from the CAS — a partial closure is unusable, so the
        // whole thing must miss and force a fresh resolve.
        Files.delete(cas.pathFor(shas.get(0)));

        assertThat(ToolClosure.readCachedClosure(cacheFile, cas)).isNull();
    }

    @Test
    void blank_lines_are_ignored(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cache"));
        List<String> shas = seedBlobs(cas, "x");
        Path cacheFile = dir.resolve("closure.shas");
        Files.writeString(cacheFile, "\n" + shas.get(0) + "\n\n", StandardCharsets.UTF_8);

        assertThat(ToolClosure.readCachedClosure(cacheFile, cas)).hasSize(1);
    }

    /** A part that is absent, or does not start with a digit, reads 0 rather than throwing. */
    @Test
    void version_parts_are_leading_integers() {
        assertThat(ToolClosure.versionPart("2.4.0", 0)).isEqualTo(2);
        assertThat(ToolClosure.versionPart("2.4.0", 1)).isEqualTo(4);
        assertThat(ToolClosure.versionPart("5.0.0-alpha-1", 0)).isEqualTo(5);
        assertThat(ToolClosure.versionPart("2.4.0-RC2", 3)).isZero(); // "RC2" has no leading digit
        assertThat(ToolClosure.versionPart("2", 1)).isZero();
        assertThat(ToolClosure.versionPart("latest", 0)).isZero();
    }

    private static List<String> seedBlobs(Cas cas, String... bodies) throws IOException {
        List<String> shas = new ArrayList<>();
        for (String body : bodies) {
            Path p = cas.put(body.getBytes(StandardCharsets.UTF_8));
            shas.add(cas.hashFromPath(p).orElseThrow());
        }
        return shas;
    }
}
