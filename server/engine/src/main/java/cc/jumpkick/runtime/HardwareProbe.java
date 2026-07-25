// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Offline host micro-benchmarks for {@link Calibration} (JK-1180).
 *
 * <p>Measures local CPU, disk, JVM fork, compile, and a synthetic "test worker" JVM — no network.
 * Aggregates samples <b>pessimistically</b> (max of warm samples) so cold ETA overshoots slightly
 * rather than under-promising.
 */
final class HardwareProbe {

    /** Warm samples after discarding the first cold-cache run. */
    static final int WARM_SAMPLES = 3;

    /** Sources in the javac probe (slightly larger than the historical 5-file probe). */
    static final int JAVAC_SOURCES = 12;

    /** Synthetic "test methods" the worker JVM executes. */
    static final int WORKER_METHODS = 8;

    /** Disk probe size — enough to touch buffer cache + some real I/O without multi-second writes. */
    static final int DISK_BYTES = 4 * 1024 * 1024; // 4 MiB

    /** CPU hash size. */
    static final int HASH_BYTES = 8 * 1024 * 1024; // 8 MiB

    record Result(
            long jvmForkMs,
            long javacMs,
            long diskIoMs,
            long hashCpuMs,
            long junitForkMs,
            long junitRunMs,
            double msPerWeight) {}

    private HardwareProbe() {}

    /**
     * Run the full offline suite against {@code javaHome}. Returns null on hard failure (no JDK,
     * etc.). Never throws.
     */
    static Result run(Path javaHome) {
        try {
            if (javaHome == null) return null;
            Path javaExe = javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
            Path javacExe = javaHome.resolve("bin").resolve(isWindows() ? "javac.exe" : "javac");
            if (!Files.isRegularFile(javaExe) || !Files.isRegularFile(javacExe)) return null;

            long forkMs = maxWarm(sample(javaExe, "-version"));
            long javacMs = measureJavac(javacExe);
            long diskMs = measureDiskIo();
            long hashMs = measureHashCpu();
            WorkerTimes worker = measureWorkerJvm(javaExe, javacExe);
            if (forkMs <= 0 || javacMs <= 0) return null;

            long junitFork = worker != null ? worker.forkMs : 0;
            long junitRun = worker != null ? worker.runMs : 0;
            double mpw = deriveMsPerWeight(forkMs, javacMs, diskMs, hashMs, junitFork, junitRun);
            // Floor: never optimistic vs the historical constant (JK-1180 pessimistic bias).
            mpw = Math.max(mpw, EffortWeights.MS_PER_WEIGHT * 0.85);
            return new Result(forkMs, javacMs, Math.max(0, diskMs), Math.max(0, hashMs), junitFork, junitRun, mpw);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /**
     * Map measured walls onto the static weight model EffortWeights would assign an equivalent
     * micro-module (compile + package touch + test worker with known methods + I/O/hash tokens).
     */
    static double deriveMsPerWeight(
            long forkMs, long javacMs, long diskMs, long hashMs, long junitForkMs, long junitRunMs) {
        long wall = forkMs + javacMs + Math.max(0, diskMs) + Math.max(0, hashMs);
        // Prefer worker JVM when measured; else fall back to java -version as fork proxy.
        if (junitForkMs > 0 || junitRunMs > 0) {
            wall += junitForkMs + junitRunMs;
        } else {
            wall += forkMs; // second fork standing in for test JVM
        }
        int weight = modelWeight();
        return wall / (double) Math.max(1, weight);
    }

    /** Static weight of the synthetic micro-suite (must stay aligned with EffortWeights constants). */
    static int modelWeight() {
        int compile = EffortWeights.COMPILE_FLOOR + EffortWeights.compileWeight(JAVAC_SOURCES);
        int tests = EffortWeights.runTestsWeight(WORKER_METHODS);
        // Disk + hash are not first-class steps; budget a few tokens so they scale ms/weight.
        int ioTokens = 4; // ~4× TOKEN units of "always there" fixed cost
        int pkg = EffortWeights.PACKAGE_JAR;
        return EffortWeights.TEST_STARTUP + compile + tests + pkg + ioTokens;
    }

    // --- samples -------------------------------------------------------------

    private static List<Long> sample(Path javaExe, String... args) throws IOException, InterruptedException {
        List<Long> all = new ArrayList<>();
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toString());
        cmd.addAll(List.of(args));
        for (int i = 0; i < WARM_SAMPLES + 1; i++) {
            long ms = timeProcess(new ProcessBuilder(cmd));
            if (ms >= 0) all.add(ms);
        }
        return all;
    }

    /** Max of warm samples (drop first); -1 if none. */
    static long maxWarm(List<Long> samples) {
        if (samples == null || samples.isEmpty()) return -1;
        List<Long> warm = samples.size() == 1 ? samples : samples.subList(1, samples.size());
        long max = -1;
        for (long s : warm) if (s > max) max = s;
        return max;
    }

    private static long measureJavac(Path javacExe) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("jk-calib-javac");
        try {
            Path out = Files.createDirectory(dir.resolve("out"));
            List<String> cmd = new ArrayList<>();
            cmd.add(javacExe.toString());
            cmd.add("-d");
            cmd.add(out.toString());
            for (int i = 0; i < JAVAC_SOURCES; i++) {
                Path src = dir.resolve("Probe" + i + ".java");
                Files.writeString(
                        src,
                        """
                        package probe;
                        final class Probe%d {
                          static int f(int x) { return x * %d + %d; }
                          static String s() { return "probe-%d"; }
                        }
                        """
                                .formatted(i, i + 1, i * 3, i),
                        StandardCharsets.UTF_8);
                cmd.add(src.toString());
            }
            // Warm once, then take worst of remaining.
            List<Long> samples = new ArrayList<>();
            for (int i = 0; i < WARM_SAMPLES + 1; i++) {
                // Fresh out dir each run so javac always does real work.
                deleteContents(out);
                long ms = timeProcess(new ProcessBuilder(cmd));
                if (ms >= 0) samples.add(ms);
            }
            return maxWarm(samples);
        } finally {
            deleteTree(dir);
        }
    }

    private record WorkerTimes(long forkMs, long runMs) {}

    /**
     * Compile + run a tiny worker main that does known method-count work in a fresh JVM. Separates
     * process startup ({@code forkMs}) from in-process work ({@code runMs}) via a wall split: first
     * run is empty main (fork proxy), second is work main.
     */
    private static WorkerTimes measureWorkerJvm(Path javaExe, Path javacExe)
            throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("jk-calib-worker");
        try {
            Path out = Files.createDirectory(dir.resolve("out"));
            Path empty = dir.resolve("EmptyMain.java");
            Files.writeString(
                    empty,
                    "public class EmptyMain { public static void main(String[] a) {} }\n",
                    StandardCharsets.UTF_8);
            Path work = dir.resolve("WorkMain.java");
            Files.writeString(
                    work,
                    """
                    public class WorkMain {
                      public static void main(String[] a) {
                        int n = %d;
                        long acc = 0;
                        for (int m = 0; m < n; m++) {
                          for (int i = 0; i < 50_000; i++) acc += (i * 31L + m) ^ (acc >>> 3);
                        }
                        if (acc == 42) System.out.print(""); // keep side-effect
                      }
                    }
                    """
                            .formatted(WORKER_METHODS),
                    StandardCharsets.UTF_8);
            // compile both
            long compile = timeProcess(new ProcessBuilder(
                    javacExe.toString(), "-d", out.toString(), empty.toString(), work.toString()));
            if (compile < 0) return null;

            long fork = maxWarm(sample(javaExe, "-cp", out.toString(), "EmptyMain"));
            List<Long> workSamples = new ArrayList<>();
            for (int i = 0; i < WARM_SAMPLES + 1; i++) {
                long ms = timeProcess(new ProcessBuilder(javaExe.toString(), "-cp", out.toString(), "WorkMain"));
                if (ms >= 0) workSamples.add(ms);
            }
            long workWall = maxWarm(workSamples);
            if (fork <= 0 || workWall <= 0) return null;
            // run ≈ work wall minus fork (floor at 1ms)
            long run = Math.max(1, workWall - fork);
            return new WorkerTimes(fork, run);
        } finally {
            deleteTree(dir);
        }
    }

    private static long measureDiskIo() throws IOException {
        Path dir = Files.createTempDirectory("jk-calib-disk");
        try {
            Path f = dir.resolve("blob.bin");
            byte[] chunk = new byte[64 * 1024];
            for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (i * 31);
            List<Long> samples = new ArrayList<>();
            for (int s = 0; s < WARM_SAMPLES + 1; s++) {
                Files.deleteIfExists(f);
                long t0 = System.nanoTime();
                try (FileChannel ch = FileChannel.open(
                        f, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    int left = DISK_BYTES;
                    while (left > 0) {
                        int n = Math.min(left, chunk.length);
                        ch.write(ByteBuffer.wrap(chunk, 0, n));
                        left -= n;
                    }
                    ch.force(true);
                }
                // read back
                try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
                    ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
                    while (ch.read(buf) > 0) buf.clear();
                }
                samples.add((System.nanoTime() - t0) / 1_000_000);
            }
            return maxWarm(samples);
        } finally {
            deleteTree(dir);
        }
    }

    private static long measureHashCpu() {
        try {
            byte[] data = new byte[HASH_BYTES];
            for (int i = 0; i < data.length; i += 64) data[i] = (byte) i;
            List<Long> samples = new ArrayList<>();
            for (int s = 0; s < WARM_SAMPLES + 1; s++) {
                long t0 = System.nanoTime();
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(data);
                md.digest();
                samples.add((System.nanoTime() - t0) / 1_000_000);
            }
            return maxWarm(samples);
        } catch (Exception e) {
            return -1;
        }
    }

    // --- process / fs helpers ------------------------------------------------

    private static long timeProcess(ProcessBuilder pb) throws IOException, InterruptedException {
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        long t0 = System.nanoTime();
        Process p = pb.start();
        if (!p.waitFor(90, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return p.exitValue() == 0 ? ms : -1;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static void deleteContents(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                deleteTree(p);
            }
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
