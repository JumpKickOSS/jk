// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wall-clock budgets for the FQCN pass alone (no Spotless, no worker fork). These exist so we
 * iterate on the shortener without a full {@code jk} dist.
 */
class FqcnShortenerBenchTest {

    @Test
    void sixteen_files_all_shorten(@TempDir Path tmp) throws Exception {
        // Correctness only — the wall-clock budget lives behind @Tag("bench") below, because a
        // load-dependent clock assertion in the default tier fails whenever the machine is busy
        // (observed under a concurrent integration run) and proves nothing when it passes.
        Fixture fx = fixture(tmp, 16);
        int changed = 0;
        for (Path f : fx.files) {
            FqcnShortener.Result r = FqcnShortener.shorten(Files.readString(f), fx.index);
            if (r.changed()) changed++;
        }
        assertThat(changed).isEqualTo(16);
    }

    @Test
    @Tag("bench")
    void sixteen_files_shorten_in_well_under_a_second(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp, 16);
        long t0 = System.nanoTime();
        int changed = 0;
        for (Path f : fx.files) {
            FqcnShortener.Result r = FqcnShortener.shorten(Files.readString(f), fx.index);
            if (r.changed()) changed++;
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(changed).isEqualTo(16);
        assertThat(ms).as("16-file FQCN pass took %d ms", ms).isLessThan(200);
    }

    @Test
    @Tag("bench")
    void two_hundred_files_shorten_in_a_couple_of_seconds(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp, 200);
        long t0 = System.nanoTime();
        int changed = 0;
        for (Path f : fx.files) {
            FqcnShortener.Result r = FqcnShortener.shorten(Files.readString(f), fx.index);
            if (r.changed()) changed++;
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(changed).isEqualTo(200);
        assertThat(ms).as("200-file FQCN pass took %d ms", ms).isLessThan(2_000);
    }

    private static Fixture fixture(Path tmp, int n) throws Exception {
        Path lib = tmp.resolve("lib");
        Files.createDirectories(lib);
        Path bar = lib.resolve("Bar.java");
        Files.writeString(
                bar, "package cc.jumpkick.foo;\npublic final class Bar { public static int n() { return 1; } }\n");
        Path callers = tmp.resolve("callers");
        Files.createDirectories(callers);
        List<Path> files = new ArrayList<>();
        List<Path> indexFiles = new ArrayList<>();
        indexFiles.add(bar);
        for (int i = 0; i < n; i++) {
            Path f = callers.resolve("Uses" + i + ".java");
            Files.writeString(
                    f, "package demo;\n\npublic class Uses" + i + " {\n  int n = cc.jumpkick.foo.Bar.n();\n}\n");
            files.add(f);
            indexFiles.add(f);
        }
        TypeIndex index = TypeIndex.scan(indexFiles, true);
        return new Fixture(files, index);
    }

    private record Fixture(List<Path> files, TypeIndex index) {}
}
