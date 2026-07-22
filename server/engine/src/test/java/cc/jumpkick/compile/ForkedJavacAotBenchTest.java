// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Microbench: {@code java … jk-java-compiler PluginMain} with PluginAot on vs off (not bare {@code
 * javac}). Prints medians; does not fail on deltas (CI noise). Run:
 *
 * <pre>
 *   ./gradlew :engine:test --tests cc.jumpkick.compile.ForkedJavacAotBenchTest
 * </pre>
 *
 * Engine host must be HotSpot 25+ ({@code PluginAot.eligible}); Graal never trains/maps.
 */
class ForkedJavacAotBenchTest {

    private static final int RUNS = 7;
    private static final int WARMUP = 2;

    @Test
    void aot_on_vs_off_java_compiler_worker(@TempDir Path dir) throws Exception {
        String workerProp = System.getProperty("jk.java.plugin.jar");
        assumeTrue(
                workerProp != null && Files.isRegularFile(Path.of(workerProp)),
                "jk.java.plugin.jar must point at the built worker jar");
        Path workerJar = Path.of(workerProp);
        Path javaHome = Path.of(System.getProperty("java.home"));
        // ToolProvider home is often jre; prefer parent if javac missing
        if (!Files.isRegularFile(javaHome.resolve("bin/javac"))
                && Files.isRegularFile(javaHome.getParent().resolve("bin/javac"))) {
            javaHome = javaHome.getParent();
        }

        Path src = dir.resolve("src/demo/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package demo;
                public class Hello {
                  public static int add(int a, int b) { return a + b; }
                }
                """);
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        Path gen = dir.resolve("gen");
        Files.createDirectories(gen);

        ForkedJavac.Request req = new ForkedJavac.Request(
                javaHome, workerJar, List.of(src), List.of(), List.of(), classes, gen, 25, List.of());

        // Train + settle (AOT-on)
        System.clearProperty("jk.worker.aot");
        for (int i = 0; i < WARMUP; i++) {
            ForkedJavac.compile(req);
            Thread.sleep(1500); // background trainer
        }

        List<Long> on = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            ForkedJavac.Result r = ForkedJavac.compile(req);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assumeTrue(r.success(), "compile failed under AOT-on");
            on.add(ms);
        }

        System.setProperty("jk.worker.aot", "off");
        try {
            for (int i = 0; i < WARMUP; i++) {
                ForkedJavac.compile(req);
            }
            List<Long> off = new ArrayList<>();
            for (int i = 0; i < RUNS; i++) {
                long t0 = System.nanoTime();
                ForkedJavac.Result r = ForkedJavac.compile(req);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                assumeTrue(r.success(), "compile failed under AOT-off");
                off.add(ms);
            }
            System.out.printf(
                    "ForkedJavac (java PluginMain)  host=%s%n  AOT-on  median=%d ms  samples=%s%n  AOT-off median=%d ms  samples=%s%n",
                    javaHome, median(on), on, median(off), off);
        } finally {
            System.clearProperty("jk.worker.aot");
        }
    }

    private static long median(List<Long> xs) {
        List<Long> s = xs.stream().sorted().toList();
        int n = s.size();
        if (n == 0) return 0;
        if (n % 2 == 1) return s.get(n / 2);
        return (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }
}
