// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.HostProcessors;
import cc.jumpkick.host.Os;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whole-tree format wall at each worker thread count, so {@link CodeFormatter#concurrency}'s cap
 * is a number someone measured. Check mode over the named tree in place (nothing is written), no
 * stamp store, the FQCN pass on: every file pays the full pipeline on every run. Prints a table
 * with cores, heap and OS, and appends it row by row to {@code out}; asserts only that every run
 * visited every file.
 *
 * <p>Knobs come from {@code ~/.jk-format-bench.properties} — a forked test JVM gets an
 * allow-listed environment. {@code tree} (a source root; absent skips the bench), {@code jars}
 * (the palantir-java-format classpath, {@link File#pathSeparator}-separated), {@code gjf-jars}
 * (google-java-format's, for remove-unused-imports; absent turns that step off), {@code threads}
 * (default {@code 4,8,12,16,24}), {@code runs} (default 3; the best counts) and {@code out} (the
 * table file, default {@code ~/.jk-format-bench.txt}: a passing test's stdout is not kept).
 */
@Tag("bench")
class FormatThreadsBenchTest {

    private static final int[] DEFAULT_THREADS = {4, 8, 12, 16, 24};

    private static final Properties KNOBS = knobs();

    private static Properties knobs() {
        var props = new Properties();
        Path file = Path.of(System.getProperty("user.home"), ".jk-format-bench.properties");
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
    void whole_tree_at_each_thread_count(@TempDir Path tmp) throws Exception {
        String tree = KNOBS.getProperty("tree", "");
        String jars = KNOBS.getProperty("jars", "");
        if (tree.isBlank() || !Files.isDirectory(Path.of(tree)) || jars.isBlank()) {
            System.out.println("format-bench: no `tree` and `jars` in ~/.jk-format-bench.properties; skipped");
            return;
        }
        List<String> sources = javaSources(Path.of(tree));
        String gjf = KNOBS.getProperty("gjf-jars", "");
        Path spec = tmp.resolve("bench.spec");
        SpecWriter w = new SpecWriter()
                .op(PluginProtocol.OP_COMMAND, "format", "jk-formatter")
                .configBool("apply", false)
                .configString("javaStyle", "palantir")
                .configString("javaVersion", "2.80.0")
                .configList("javaJars", split(jars))
                .configList("javaFiles", sources)
                .configBool("importOrder", true)
                .configBool("removeUnusedImports", !gjf.isBlank())
                .configBool("optimizeImports", true)
                .configList("indexFiles", sources);
        if (!gjf.isBlank()) w.configList("removeUnusedJars", split(gjf));
        Files.write(spec, w.lines(), StandardCharsets.UTF_8);

        Path table = Path.of(KNOBS.getProperty(
                "out",
                Path.of(System.getProperty("user.home"), ".jk-format-bench.txt").toString()));
        report(
                table,
                String.format(
                        "format-bench: files=%d cores=%d heap=%dMiB os=%s jdk=%s runs=%d remove-unused=%s",
                        sources.size(),
                        HostProcessors.count(),
                        Runtime.getRuntime().maxMemory() / (1024 * 1024),
                        Os.name(),
                        System.getProperty("java.version"),
                        runs(),
                        !gjf.isBlank()));
        report(table, "format-bench threads | best ms | files/s");
        String previous = System.getProperty("jk.format.threads");
        try {
            for (int threads : threads()) {
                System.setProperty("jk.format.threads", String.valueOf(threads));
                long best = Long.MAX_VALUE;
                for (int run = 0; run < runs(); run++) {
                    Counting out = new Counting();
                    long t0 = System.nanoTime();
                    new CodeFormatter().run(List.of(spec.toString()), out.writer());
                    best = Math.min(best, (System.nanoTime() - t0) / 1_000_000L);
                    assertThat(out.files()).as("every file visited").isEqualTo(sources.size());
                }
                long perSecond = best == 0 ? sources.size() : sources.size() * 1000L / best;
                report(table, String.format("format-bench %7d | %7d | %d", threads, best, perSecond));
            }
        } finally {
            if (previous == null) System.clearProperty("jk.format.threads");
            else System.setProperty("jk.format.threads", previous);
        }
    }

    private static void report(Path out, String line) throws IOException {
        System.out.println(line);
        Files.writeString(
                out,
                line + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static List<String> javaSources(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(File.separator + "target" + File.separator))
                    .filter(p -> !p.toString().contains(File.separator + ".git" + File.separator))
                    .filter(Files::isRegularFile)
                    .map(p -> p.toAbsolutePath().toString())
                    .sorted()
                    .toList();
        }
    }

    private static List<String> split(String classpath) {
        return Arrays.stream(classpath.split(File.pathSeparator))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static int[] threads() {
        String knob = KNOBS.getProperty("threads");
        if (knob == null || knob.isBlank()) return DEFAULT_THREADS;
        return Arrays.stream(knob.split(","))
                .mapToInt(s -> Integer.parseInt(s.strip()))
                .toArray();
    }

    private static int runs() {
        String v = KNOBS.getProperty("runs");
        return v == null || v.isBlank() ? 3 : Integer.parseInt(v.strip());
    }

    /** Counts the per-file verdicts a run emits and throws the rest away. */
    private static final class Counting {

        private final List<String> lines = new ArrayList<>();
        private final ProtocolWriter writer = new ProtocolWriter(
                new PrintStream(
                        new OutputStream() {
                            private final StringBuilder line = new StringBuilder();

                            @Override
                            public void write(int b) {
                                if (b == '\n') {
                                    synchronized (lines) {
                                        lines.add(line.toString());
                                    }
                                    line.setLength(0);
                                } else {
                                    line.append((char) b);
                                }
                            }
                        },
                        true,
                        StandardCharsets.UTF_8),
                "##JKFMT:");

        ProtocolWriter writer() {
            return writer;
        }

        long files() {
            synchronized (lines) {
                return lines.stream()
                        .filter(l -> l.contains("\"status\"") && !l.contains("\"slow\""))
                        .count();
            }
        }
    }
}
