// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How fast one {@code target/}-shaped tree comes off this disk at each unlink width, so {@link
 * DeleteParallelism}'s per-OS defaults are numbers someone measured. Prints a table; asserts only
 * that every delete removed its tree.
 *
 * <p>Two arms. The synthetic arm plants about as many files and directories as this repo's own
 * {@code target/} holds after a build (twenty thousand files in a few thousand directories, most
 * the size of a class file, a few the size of a jar). The real arm, when {@code JK_BENCH_TREE}
 * names a directory, copies that tree for each run and times only the delete of the copy.
 *
 * <p>Knobs come from {@code ~/.jk-delete-bench.properties} when that file exists — a forked test
 * JVM gets an allow-listed environment, so a variable set in the shell would never arrive:
 * {@code widths} (default {@code 1,2,4,8,16,32,64}), {@code runs} (default 3; the best counts),
 * {@code files} (default 20000) and {@code tree} (the real arm's source; absent skips that arm).
 */
@Tag("bench")
class DeleteTreeBenchTest {

    private static final int[] DEFAULT_WIDTHS = {1, 2, 4, 8, 16, 32, 64};

    private static final Properties KNOBS = knobs();

    private static Properties knobs() {
        var props = new Properties();
        Path file = Path.of(System.getProperty("user.home"), ".jk-delete-bench.properties");
        if (Files.isRegularFile(file)) {
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return props;
    }

    @Test
    void synthetic_target_tree(@TempDir Path dir) throws Exception {
        int files = knobInt("files", 20_000);
        Path first = plant(dir.resolve("warm"), files, new Random(7));
        Shape shape = Shape.of(first);
        PathUtil.deleteTrees(List.of(first), new PathUtil.Removed(), 1);
        header("synthetic", dir, shape);
        for (int width : widths()) {
            long best = Long.MAX_VALUE;
            for (int run = 0; run < runs(); run++) {
                Path tree = plant(dir.resolve("t" + width + "-" + run), files, new Random(7));
                best = Math.min(best, timeDelete(tree, width, shape.files));
            }
            row(width, best, shape.files);
        }
    }

    @Test
    void real_tree_when_named(@TempDir Path dir) throws Exception {
        String named = KNOBS.getProperty("tree");
        if (named == null || named.isBlank() || !Files.isDirectory(Path.of(named))) {
            System.out.println("delete-bench real: no `tree` directory in ~/.jk-delete-bench.properties; skipped");
            return;
        }
        Path source = Path.of(named);
        Shape shape = Shape.of(source);
        header("real " + source, dir, shape);
        for (int width : widths()) {
            long best = Long.MAX_VALUE;
            for (int run = 0; run < runs(); run++) {
                Path copy = dir.resolve("r" + width + "-" + run);
                PathUtil.copyTree(source, copy);
                best = Math.min(best, timeDelete(copy, width, shape.files));
            }
            row(width, best, shape.files);
        }
    }

    private static long timeDelete(Path tree, int width, long expectedFiles) throws IOException {
        var tally = new PathUtil.Removed();
        long t0 = System.nanoTime();
        PathUtil.deleteTrees(List.of(tree), tally, width);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertThat(tree).doesNotExist();
        assertThat(tally.files()).isEqualTo(expectedFiles);
        return ms;
    }

    /**
     * A tree shaped like a built {@code target/}: module directories holding {@code classes/} with
     * packages several levels deep and many small files, a few large jars beside them, and some
     * medium resources — the mix that a class-file-heavy build leaves behind.
     */
    private static Path plant(Path root, int files, Random rnd) throws IOException {
        Files.createDirectories(root);
        int modules = 40;
        int perModule = Math.max(1, files / modules);
        byte[] small = new byte[16 * 1024];
        byte[] big = new byte[4 * 1024 * 1024];
        rnd.nextBytes(small);
        for (int m = 0; m < modules; m++) {
            Path module = root.resolve("module-" + m);
            Path classes = module.resolve("classes").resolve("main");
            Files.createDirectories(classes);
            // Packages: a dozen leaf directories, each 2-6 levels down.
            List<Path> leaves = new ArrayList<>();
            for (int p = 0; p < 12; p++) {
                Path leaf = classes;
                int depth = 2 + rnd.nextInt(5);
                for (int d = 0; d < depth; d++) leaf = leaf.resolve("pkg" + p + "_" + d);
                Files.createDirectories(leaf);
                leaves.add(leaf);
            }
            for (int f = 0; f < perModule; f++) {
                Path leaf = leaves.get(f % leaves.size());
                int size = 1024 + rnd.nextInt(15 * 1024);
                Files.write(leaf.resolve("Class" + f + ".class"), Arrays.copyOf(small, size));
            }
            // A jar and a sources jar the size of a real one; a test-results directory.
            Files.write(module.resolve("module-" + m + ".jar"), big);
            Files.write(module.resolve("module-" + m + "-sources.jar"), small);
            Path reports = module.resolve("reports").resolve("test-results");
            Files.createDirectories(reports);
            for (int r = 0; r < 5; r++) Files.write(reports.resolve("TEST-" + r + ".xml"), Arrays.copyOf(small, 4096));
        }
        return root;
    }

    private static int[] widths() {
        String knob = KNOBS.getProperty("widths");
        if (knob == null || knob.isBlank()) return DEFAULT_WIDTHS;
        String[] parts = knob.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }

    private static int runs() {
        return knobInt("runs", 3);
    }

    private static int knobInt(String name, int dflt) {
        String v = KNOBS.getProperty(name);
        if (v == null || v.isBlank()) return dflt;
        return Integer.parseInt(v.trim());
    }

    private static void header(String arm, Path dir, Shape shape) throws IOException {
        System.out.printf(
                "%ndelete-bench %s: files=%d dirs=%d bytes=%dMiB os=%s cpus=%d fs=%s jdk=%s runs=%d%n",
                arm,
                shape.files,
                shape.dirs,
                shape.bytes / (1024 * 1024),
                Os.name(),
                HostProcessors.count(),
                Files.getFileStore(dir).type(),
                System.getProperty("java.version"),
                runs());
        System.out.println("delete-bench width | best ms | files/s");
    }

    private static void row(int width, long bestMs, long files) {
        long perSecond = bestMs == 0 ? files : files * 1000L / bestMs;
        System.out.printf("delete-bench %5d | %7d | %d%n", width, bestMs, perSecond);
    }

    private record Shape(long files, long dirs, long bytes) {
        static Shape of(Path root) throws IOException {
            long[] acc = new long[3];
            try (var walk = Files.walk(root)) {
                walk.forEach(p -> {
                    if (Files.isDirectory(p)) {
                        acc[1]++;
                    } else if (Files.isRegularFile(p)) {
                        acc[0]++;
                        try {
                            acc[2] += Files.size(p);
                        } catch (IOException ignored) {
                            // a vanished file contributes nothing to the shape
                        }
                    }
                });
            }
            return new Shape(acc[0], acc[1], acc[2]);
        }
    }
}
