// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same sources through plain {@code javac} and through Zinc, so the two can be compared on one
 * host and across hosts.
 *
 * <p>A module's {@code compile-java} runs about 2.2x slower on Windows than on Linux on the same
 * machine, while {@code guard} — also a forked worker JVM doing CPU over the same trees — runs at
 * parity. Phase timing inside the worker put 97% of that in {@code zinc.compile}, which is javac plus
 * Zinc's incremental machinery in one number. This splits the two: if plain javac carries the whole
 * ratio then the cost is javac's, and if Zinc's wrapper adds it then it is the analysis, the stamping
 * or the file conversion around it.
 *
 * <p>Not a gate. It prints and asserts only that both compilers produced the same classes, so it can
 * run anywhere without a wall-clock budget that would fail on a slow or busy host.
 */
@Tag("bench")
class JavacVsZincBenchTest {

    /** Enough files that per-invocation cost does not dominate, few enough to stay a minute-ish. */
    private static final int FILES = 400;

    private static final int WARMUPS = 3;
    private static final int RUNS = 7;

    @Test
    void javac_and_zinc_compile_the_same_sources(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src");
        List<Path> sources = generate(src, FILES);

        long javac = best(RUNS, WARMUPS, () -> runJavac(dir, sources, false));
        long analysis = best(RUNS, WARMUPS, () -> runJavac(dir, sources, true));
        long zinc = best(RUNS, WARMUPS, () -> runZinc(dir, sources));

        System.out.printf(
                "%njavac-vs-zinc  files=%d  javac=%d ms  analysis-only=%d ms  codegen+write=%d ms"
                        + "  zinc=%d ms  zinc/javac=%.2fx  os=%s%n",
                FILES, javac, analysis, javac - analysis, zinc, zinc / (double) javac, System.getProperty("os.name"));
        System.out.printf(
                "javac-vs-zinc  host: cpus=%d maxHeap=%d MB jdk=%s (%s) gc=%s%n",
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                System.getProperty("java.version"),
                System.getProperty("java.vm.vendor"),
                ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .map(GarbageCollectorMXBean::getName)
                        .reduce((x, y) -> x + "+" + y)
                        .orElse("?"));

        assertThat(countClasses(dir.resolve("out-javac"))).isEqualTo(FILES);
        assertThat(countClasses(dir.resolve("out-zinc"))).isEqualTo(FILES);
    }

    /**
     * Sources with real work in them: generics, a lambda, a stream and a cross-reference to a
     * neighbour, so javac does type inference and Zinc records a dependency edge. Trivial classes
     * would measure file creation and little else.
     */
    private static List<Path> generate(Path src, int n) throws IOException {
        List<Path> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Path f = src.resolve("p/C" + i + ".java");
            Files.createDirectories(f.getParent());
            int peer = (i + 1) % n;
            Files.writeString(f, """
                    package p;
                    import java.util.*;
                    import java.util.stream.*;
                    public class C%d {
                        private final Map<String, List<Integer>> m = new HashMap<>();
                        public int f(int x) {
                            int s = 0;
                            for (int k = 0; k < x; k++) {
                                s += k * %d;
                                m.computeIfAbsent("k" + k, q -> new ArrayList<>()).add(s);
                            }
                            return s;
                        }
                        public String g() {
                            return m.entrySet().stream()
                                .map(e -> e.getKey() + e.getValue().size())
                                .sorted()
                                .collect(Collectors.joining());
                        }
                        public Optional<C%d> peer() { return Optional.of(new C%d()); }
                    }
                    """.formatted(i, i, peer, peer));
            out.add(f);
        }
        return out;
    }

    /**
     * In-process {@code javac} over a clean output directory.
     *
     * <p>{@code analysisOnly} stops the compiler after flow analysis, so it parses and type-checks
     * without generating or writing a single class file. The difference between the two runs is what
     * code generation and output writing cost, which is the split that says whether a platform
     * penalty is in the compiler's CPU or in its file output.
     */
    private static long runJavac(Path dir, List<Path> sources, boolean analysisOnly) throws IOException {
        Path out = fresh(dir.resolve(analysisOnly ? "out-analysis" : "out-javac"));
        List<String> args = new ArrayList<>(List.of("-nowarn", "-d", out.toString()));
        if (analysisOnly) {
            args.add("-proc:none");
            args.add("-XDshould-stop.ifNoError=FLOW");
        }
        for (Path s : sources) args.add(s.toString());
        long t0 = System.nanoTime();
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0]));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (rc != 0) throw new IllegalStateException("javac failed, rc=" + rc);
        return ms;
    }

    /** The same sources through {@link ZincJavaCompiler}, analysis discarded so both start cold. */
    private static long runZinc(Path dir, List<Path> sources) throws IOException {
        Path out = fresh(dir.resolve("out-zinc"));
        Path workdir = fresh(dir.resolve("zinc-work"));
        JavaCompileJob job = new JavaCompileJob(sources, List.of(), out, workdir, null, 25, List.of(), List.of());
        long t0 = System.nanoTime();
        ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(job);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (!r.success()) throw new IllegalStateException("zinc failed: " + r.diagnostics());
        return ms;
    }

    /** Best of {@code runs} after {@code warmups}: the floor is the least noisy statistic here. */
    private static long best(int runs, int warmups, Bench body) throws Exception {
        for (int i = 0; i < warmups; i++) body.run();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < runs; i++) best = Math.min(best, body.run());
        return best;
    }

    private interface Bench {
        long run() throws Exception;
    }

    private static Path fresh(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        Files.createDirectories(dir);
        return dir;
    }

    private static long countClasses(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".class")).count();
        }
    }
}
