// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Host micro-benchmarks for {@link Calibration} (JK-1180 follow-ups).
 *
 * <p>Core suite is offline. Optional:
 *
 * <ul>
 *   <li><b>JUnit Platform</b> — when Jupiter jars are in the local cache (or {@code allowNetwork}
 *       fetches them), compile + run one real {@code @Test} via the Platform Launcher API.
 *   <li><b>Resolve</b> — when {@code allowNetwork}, time an HTTP GET of a tiny known Maven Central
 *       artifact (local I/O/CPU already covered; this fills network RTT + TLS).
 * </ul>
 *
 * Samples aggregate <b>pessimistically</b> (max of warm samples after cold-cache discard).
 */
final class HardwareProbe {

    static final int WARM_SAMPLES = 3;
    static final int JAVAC_SOURCES = 12;
    static final int WORKER_METHODS = 8;
    static final int DISK_BYTES = 4 * 1024 * 1024;
    static final int HASH_BYTES = 8 * 1024 * 1024;

    /** Known small Central artifact for the optional resolve probe (~5 KB). */
    static final String RESOLVE_PROBE_PATH = "org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar";

    static final String CENTRAL_BASE = "https://repo1.maven.org/maven2/";

    /** Pinned Jupiter set for the optional real-JUnit probe (matches first-party jk.toml pins ~6.x). */
    private static final String JUNIT_VER = "5.11.4";

    private static final String PLATFORM_VER = "1.11.4";

    record Options(boolean allowNetwork, Path cacheRoot) {
        static Options offline() {
            return new Options(false, JkDirs.cache());
        }

        static Options of(boolean allowNetwork, Path cacheRoot) {
            return new Options(allowNetwork, cacheRoot != null ? cacheRoot : JkDirs.cache());
        }
    }

    record Result(
            long jvmForkMs,
            long javacMs,
            long diskIoMs,
            long hashCpuMs,
            long junitForkMs,
            long junitRunMs,
            long junitPlatformMs,
            long resolveMs,
            boolean junitPlatformUsed,
            boolean resolveUsed,
            double msPerWeight) {}

    private HardwareProbe() {}

    static Result run(Path javaHome) {
        return run(javaHome, Options.offline());
    }

    static Result run(Path javaHome, Options opts) {
        try {
            if (javaHome == null) return null;
            Path javaExe = javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
            Path javacExe = javaHome.resolve("bin").resolve(isWindows() ? "javac.exe" : "javac");
            if (!Files.isRegularFile(javaExe) || !Files.isRegularFile(javacExe)) return null;
            Options o = opts == null ? Options.offline() : opts;

            long forkMs = maxWarm(sample(javaExe, "-version"));
            long javacMs = measureJavac(javacExe);
            long diskMs = measureDiskIo();
            long hashMs = measureHashCpu();
            WorkerTimes worker = measureWorkerJvm(javaExe, javacExe);
            if (forkMs <= 0 || javacMs <= 0) return null;

            long synthFork = worker != null ? worker.forkMs : 0;
            long synthRun = worker != null ? worker.runMs : 0;

            JunitPlatformTimes junit = measureJunitPlatform(javaExe, javacExe, o);
            long junitPlatformMs = junit != null ? junit.wallMs : 0;
            boolean junitUsed = junit != null;

            // Prefer real JUnit Platform wall for the test slot when available.
            long testFork = junitUsed ? junit.wallMs : synthFork;
            long testRun = junitUsed ? 0 : synthRun;

            long resolveMs = 0;
            boolean resolveUsed = false;
            if (o.allowNetwork()) {
                long r = measureResolve(o.cacheRoot());
                if (r > 0) {
                    resolveMs = r;
                    resolveUsed = true;
                }
            }

            double mpw = deriveMsPerWeight(
                    forkMs, javacMs, diskMs, hashMs, testFork, testRun, junitPlatformMs, resolveMs, resolveUsed);
            // Component floors: a slow network or heavy JUnit Platform init must lift the anchor
            // even when the bulk model weight would dilute them below the historical constant.
            if (junitUsed && junitPlatformMs > 0) {
                mpw = Math.max(mpw, junitPlatformMs / (double) Math.max(1, EffortWeights.runTestsWeight(1)));
            }
            if (resolveUsed && resolveMs > 0) {
                mpw = Math.max(mpw, resolveMs / (double) Math.max(1, EffortWeights.ARTIFACT_FETCH));
            }
            mpw = Math.max(mpw, EffortWeights.MS_PER_WEIGHT * 0.85);
            return new Result(
                    forkMs,
                    javacMs,
                    Math.max(0, diskMs),
                    Math.max(0, hashMs),
                    synthFork,
                    synthRun,
                    junitPlatformMs,
                    resolveMs,
                    junitUsed,
                    resolveUsed,
                    mpw);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    static double deriveMsPerWeight(
            long forkMs,
            long javacMs,
            long diskMs,
            long hashMs,
            long junitForkMs,
            long junitRunMs,
            long junitPlatformMs,
            long resolveMs,
            boolean resolveUsed) {
        long wall = forkMs + javacMs + Math.max(0, diskMs) + Math.max(0, hashMs);
        if (junitPlatformMs > 0) {
            wall += junitPlatformMs;
        } else if (junitForkMs > 0 || junitRunMs > 0) {
            wall += junitForkMs + junitRunMs;
        } else {
            wall += forkMs;
        }
        if (resolveUsed && resolveMs > 0) wall += resolveMs;
        return wall / (double) Math.max(1, modelWeight(resolveUsed));
    }

    /** Legacy overload for unit tests. */
    static double deriveMsPerWeight(
            long forkMs, long javacMs, long diskMs, long hashMs, long junitForkMs, long junitRunMs) {
        return deriveMsPerWeight(forkMs, javacMs, diskMs, hashMs, junitForkMs, junitRunMs, 0, 0, false);
    }

    static int modelWeight() {
        return modelWeight(false);
    }

    static int modelWeight(boolean withResolve) {
        int compile = EffortWeights.COMPILE_FLOOR + EffortWeights.compileWeight(JAVAC_SOURCES);
        int tests = EffortWeights.runTestsWeight(WORKER_METHODS);
        int ioTokens = 4;
        int pkg = EffortWeights.PACKAGE_JAR;
        int base = EffortWeights.TEST_STARTUP + compile + tests + pkg + ioTokens;
        if (withResolve) base += EffortWeights.ARTIFACT_FETCH;
        return base;
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
            List<Long> samples = new ArrayList<>();
            for (int i = 0; i < WARM_SAMPLES + 1; i++) {
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

    private static WorkerTimes measureWorkerJvm(Path javaExe, Path javacExe)
            throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("jk-calib-worker");
        try {
            Path out = Files.createDirectory(dir.resolve("out"));
            Path empty = dir.resolve("EmptyMain.java");
            Files.writeString(
                    empty, "public class EmptyMain { public static void main(String[] a) {} }\n", StandardCharsets.UTF_8);
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
                        if (acc == 42) System.out.print("");
                      }
                    }
                    """
                            .formatted(WORKER_METHODS),
                    StandardCharsets.UTF_8);
            if (timeProcess(new ProcessBuilder(
                            javacExe.toString(), "-d", out.toString(), empty.toString(), work.toString()))
                    < 0) return null;

            long fork = maxWarm(sample(javaExe, "-cp", out.toString(), "EmptyMain"));
            List<Long> workSamples = new ArrayList<>();
            for (int i = 0; i < WARM_SAMPLES + 1; i++) {
                long ms = timeProcess(new ProcessBuilder(javaExe.toString(), "-cp", out.toString(), "WorkMain"));
                if (ms >= 0) workSamples.add(ms);
            }
            long workWall = maxWarm(workSamples);
            if (fork <= 0 || workWall <= 0) return null;
            return new WorkerTimes(fork, Math.max(1, workWall - fork));
        } finally {
            deleteTree(dir);
        }
    }

    private record JunitPlatformTimes(long wallMs) {}

    /**
     * Real JUnit Platform: one {@code @Test} method via LauncherFactory. Requires Jupiter jars
     * locally or {@code allowNetwork} to pull them from Central.
     */
    private static JunitPlatformTimes measureJunitPlatform(Path javaExe, Path javacExe, Options opts) {
        try {
            List<Path> jars = resolveJunitClasspath(opts);
            if (jars.isEmpty()) return null;

            Path dir = Files.createTempDirectory("jk-calib-junit");
            try {
                Path out = Files.createDirectory(dir.resolve("out"));
                Path testSrc = dir.resolve("CalibTest.java");
                Path mainSrc = dir.resolve("ProbeJunitMain.java");
                Files.writeString(
                        testSrc,
                        """
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.*;
                        public class CalibTest {
                          @Test void known_method() {
                            int s = 0;
                            for (int i = 0; i < 10_000; i++) s += i;
                            assertTrue(s > 0);
                          }
                        }
                        """,
                        StandardCharsets.UTF_8);
                Files.writeString(
                        mainSrc,
                        """
                        import org.junit.platform.engine.discovery.DiscoverySelectors;
                        import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
                        import org.junit.platform.launcher.core.LauncherFactory;
                        import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
                        public class ProbeJunitMain {
                          public static void main(String[] args) {
                            var req = LauncherDiscoveryRequestBuilder.request()
                                .selectors(DiscoverySelectors.selectClass(CalibTest.class))
                                .build();
                            var launcher = LauncherFactory.create();
                            var summary = new SummaryGeneratingListener();
                            launcher.registerTestExecutionListeners(summary);
                            launcher.execute(req);
                            if (summary.getSummary().getTestsFailedCount() > 0
                                || summary.getSummary().getTestsSucceededCount() < 1) {
                              System.exit(1);
                            }
                          }
                        }
                        """,
                        StandardCharsets.UTF_8);

                String cp = joinCp(jars);
                List<String> compile = new ArrayList<>();
                compile.add(javacExe.toString());
                compile.add("-cp");
                compile.add(cp);
                compile.add("-d");
                compile.add(out.toString());
                compile.add(testSrc.toString());
                compile.add(mainSrc.toString());
                if (timeProcess(new ProcessBuilder(compile)) < 0) return null;

                String runCp = out + pathSep() + cp;
                List<Long> samples = new ArrayList<>();
                for (int i = 0; i < WARM_SAMPLES + 1; i++) {
                    long ms = timeProcess(new ProcessBuilder(javaExe.toString(), "-cp", runCp, "ProbeJunitMain"));
                    if (ms >= 0) samples.add(ms);
                }
                long wall = maxWarm(samples);
                return wall > 0 ? new JunitPlatformTimes(wall) : null;
            } finally {
                deleteTree(dir);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Locate or fetch the minimal Jupiter + Platform jars needed to run one test. */
    private static List<Path> resolveJunitClasspath(Options opts) {
        // artifactId → (group path, version)
        Map<String, String[]> coords = new LinkedHashMap<>();
        coords.put("junit-jupiter-api", new String[] {"org/junit/jupiter", JUNIT_VER});
        coords.put("junit-jupiter-engine", new String[] {"org/junit/jupiter", JUNIT_VER});
        coords.put("junit-platform-launcher", new String[] {"org/junit/platform", PLATFORM_VER});
        coords.put("junit-platform-engine", new String[] {"org/junit/platform", PLATFORM_VER});
        coords.put("junit-platform-commons", new String[] {"org/junit/platform", PLATFORM_VER});
        coords.put("opentest4j", new String[] {"org/opentest4j", "1.3.0"});
        coords.put("apiguardian-api", new String[] {"org/apiguardian", "1.1.2"});

        List<Path> out = new ArrayList<>();
        for (var e : coords.entrySet()) {
            String artifact = e.getKey();
            String groupPath = e.getValue()[0];
            String ver = e.getValue()[1];
            String rel = groupPath + "/" + artifact + "/" + ver + "/" + artifact + "-" + ver + ".jar";
            Path found = findInCache(opts.cacheRoot(), rel);
            if (found == null && opts.allowNetwork()) {
                found = downloadToCache(opts.cacheRoot(), rel);
            }
            if (found == null) {
                // Try any version already on disk (dogfood machines often have newer 6.x).
                found = findAnyVersion(opts.cacheRoot(), groupPath, artifact);
            }
            if (found == null) return List.of(); // incomplete set → skip probe
            out.add(found);
        }
        return out;
    }

    private static Path findInCache(Path cacheRoot, String relativeMavenPath) {
        if (cacheRoot == null) return null;
        Path repos = cacheRoot.resolve("repos");
        if (!Files.isDirectory(repos)) return null;
        try (Stream<Path> stream = Files.list(repos)) {
            for (Path repo : (Iterable<Path>) stream::iterator) {
                Path jar = repo.resolve(relativeMavenPath);
                if (Files.isRegularFile(jar)) return jar;
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return null;
    }

    private static Path findAnyVersion(Path cacheRoot, String groupPath, String artifact) {
        if (cacheRoot == null) return null;
        Path repos = cacheRoot.resolve("repos");
        if (!Files.isDirectory(repos)) return null;
        try (Stream<Path> reposStream = Files.list(repos)) {
            for (Path repo : (Iterable<Path>) reposStream::iterator) {
                Path artDir = repo.resolve(groupPath).resolve(artifact);
                if (!Files.isDirectory(artDir)) continue;
                Path best = null;
                try (Stream<Path> vers = Files.list(artDir)) {
                    for (Path verDir : (Iterable<Path>) vers::iterator) {
                        if (!Files.isDirectory(verDir)) continue;
                        Path jar = verDir.resolve(artifact + "-" + verDir.getFileName() + ".jar");
                        if (Files.isRegularFile(jar)) {
                            // Prefer highest path name lexicographically (rough newest for dotted versions).
                            if (best == null || jar.getParent().getFileName().toString()
                                    .compareTo(best.getParent().getFileName().toString())
                                    > 0) {
                                best = jar;
                            }
                        }
                    }
                }
                if (best != null) return best;
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return null;
    }

    private static Path downloadToCache(Path cacheRoot, String relativeMavenPath) {
        try {
            byte[] body = httpGet(CENTRAL_BASE + relativeMavenPath);
            if (body == null || body.length == 0) return null;
            Path dest = cacheRoot.resolve("repos").resolve("central").resolve(relativeMavenPath);
            Files.createDirectories(dest.getParent());
            Files.write(dest, body);
            return dest;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Time a cold-ish HTTP GET of a tiny Central artifact. Uses a unique cache-buster query so we
     * do not measure only a fully warm CDN path when the object is already popular — still network.
     */
    private static long measureResolve(Path cacheRoot) {
        try {
            // Prefer timing a real download into a temp file (not the shared cache) so we always hit network.
            String url = CENTRAL_BASE + RESOLVE_PROBE_PATH + "?jk_calib=" + System.nanoTime();
            List<Long> samples = new ArrayList<>();
            for (int i = 0; i < WARM_SAMPLES + 1; i++) {
                long t0 = System.nanoTime();
                byte[] body = httpGet(url);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (body != null && body.length > 0 && ms >= 0) samples.add(ms);
            }
            return maxWarm(samples);
        } catch (Exception e) {
            return -1;
        }
    }

    private static byte[] httpGet(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() >= 400) return null;
        return resp.body();
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

    private static String pathSep() {
        return System.getProperty("path.separator", ":");
    }

    private static String joinCp(List<Path> jars) {
        StringBuilder sb = new StringBuilder();
        for (Path j : jars) {
            if (sb.length() > 0) sb.append(pathSep());
            sb.append(j);
        }
        return sb.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void deleteContents(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) deleteTree(p);
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
