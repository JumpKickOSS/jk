// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.DiskGuard;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every store path keeps a heavy output out of a cache whose volume is nearly full. */
class ActionCacheDiskGuardTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @TempDir
    Path temp;

    private Cas cas;
    private ActionCache cache;
    private Path out;

    @BeforeEach
    void setUp() throws IOException {
        cas = new Cas(temp.resolve("cas"));
        cache = new ActionCache(cas, temp.resolve("actions"));
        out = temp.resolve("out");
        Files.createDirectories(out);
    }

    @Test
    void a_heavy_output_directory_is_not_stored() throws IOException {
        Path heavy = heavy("app.bin");

        nearlyFull();
        cache.store("plugin-transform", "k-dir", Map.of(), out);

        assertThat(cache.lookup("k-dir")).isEmpty();
        assertThat(cas.pathFor(Hashing.sha256Hex(heavy))).doesNotExist();
    }

    @Test
    void heavy_artifacts_are_not_stored() throws IOException {
        Path heavy = heavy("app.jar");

        nearlyFull();
        cache.storeArtifacts("node-step", "k-art", Map.of(), out, List.of(heavy));

        assertThat(cache.lookup("k-art")).isEmpty();
        assertThat(cas.pathFor(Hashing.sha256Hex(heavy))).doesNotExist();
    }

    @Test
    void heavy_prewritten_outputs_get_no_record() throws IOException {
        Path heavy = heavy("Big.class");
        String hex = Hashing.sha256Hex(heavy);
        cas.putFile(heavy, hex);

        nearlyFull();
        cache.storeWithOutputs("compile-java", "k-pre", Map.of(), Map.of("Big.class", hex));

        assertThat(cache.lookup("k-pre")).isEmpty();
    }

    @Test
    void a_small_output_is_stored_on_a_nearly_full_volume() throws IOException {
        Files.writeString(out.resolve("A.class"), "small");

        nearlyFull();
        cache.store("compile-java", "k-small", Map.of(), out);

        assertThat(cache.lookup("k-small")).isPresent();
    }

    /** The cache's volume reads as critical; a probe is trusted for seconds, so set just before the store. */
    private void nearlyFull() {
        DiskGuard.putProbeForTest(cache.root(), new JkCacheConfig.DiskSpace(100 * GIB, GIB));
    }

    /** A sparse file of exactly {@link DiskGuard#HEAVY_BYTES}: heavy by size, cheap on disk. */
    private Path heavy(String name) throws IOException {
        Path file = out.resolve(name);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(DiskGuard.HEAVY_BYTES);
        }
        return file;
    }
}
