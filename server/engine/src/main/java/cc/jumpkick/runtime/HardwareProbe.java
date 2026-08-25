// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Host micro-benchmarks for {@link Calibration} follow-ups).
 *
 * <p>Core suite is always offline (JVM fork, javac, disk, hash, synthetic worker). When {@code
 * allowNetwork} (default for {@code ensure} / {@code jk engine calibrate}; false under global {@code
 * --offline}):
 *
 * <ul>
 * <li><b>JUnit Platform</b> — when the Jupiter jars are in the artifact store or the Maven local
 * repository (or network fetches them into the store), compile + run one real {@code @Test} via the
 * Platform Launcher API.
 * <li><b>Resolve</b> — time an HTTP GET of a tiny known Maven Central artifact.
 * </ul>
 *
 * Samples aggregate <b>pessimistically</b> (max of warm samples after cold-cache discard).
 */
final class HardwareProbe {

    static final int WARM_SAMPLES = 3;
    static final int JAVAC_SOURCES = 12;
    static final int WORKER_METHODS = 8;
    /** Trivial empty {@code @Test} methods in the real JUnit Platform probe (startup vs method slope). */
    static final int PLATFORM_METHODS = 8;

    static final int DISK_BYTES = 4 * 1024 * 1024;
    static final int HASH_BYTES = 8 * 1024 * 1024;

    /** Known small Central artifact for the optional resolve probe (~5 KB). */
    static final String RESOLVE_PROBE_PATH = "org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar";

    static final String CENTRAL_BASE = RepositorySpec.MAVEN_CENTRAL.url().toString();

    private static final Http HTTP = new Http();

    /** Pinned Jupiter set for the optional real-JUnit probe (matches first-party jk.toml pins ~6.x). */
    private static final String JUNIT_VER = "5.11.4";

    private static final String PLATFORM_VER = "1.11.4";

    /**
     * Where the probe may read and write Maven-layout artifacts. {@code storeRoot} is the
     * <em>artifact</em> store ({@link JkDirs#store()}), never the cache root: a jar the probe
     * fetches is an ordinary Central download that a later resolve can reuse, and the cache root
     * is the tree {@code jk cache nuke} removes wholesale and {@code jk status} reports as the
     * user's cache size. Neither factory takes a path, so no caller can hand this a cache root the
     * way {@code Calibration.probe} used to (JK-2456) — the root comes from {@link JkDirs}, which
     * a test redirects wholesale rather than per call site.
     */
    record Options(boolean allowNetwork, Path storeRoot) {
        static Options offline() {
            return new Options(false, JkDirs.store());
        }

        static Options of(boolean allowNetwork) {
            return new Options(allowNetwork, JkDirs.store());
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
            int junitPlatformMethods,
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
            Path javaExe = JdkFingerprint.java(javaHome);
            Path javacExe = JdkFingerprint.javac(javaHome);
            if (!Files.isRegularFile(javaExe) || !Files.isRegularFile(javacExe)) return null;
            Options o = opts == null ? Options.offline() : opts;

            long forkMs = maxWarm(sample(javaExe, "-version"));
            long javacMs = measureJavac(javacExe);
            // Validity gate BEFORE the expensive probesa broken javac used to pay
            // disk I/O + hash CPU + the full worker-JVM suite just to discard the result.
            if (forkMs <= 0 || javacMs <= 0) return null;
            long diskMs = measureDiskIo();
            long hashMs = measureHashCpu();
            WorkerTimes worker = measureWorkerJvm(javaExe, javacExe);

            long synthFork = worker != null ? worker.forkMs : 0;
            long synthRun = worker != null ? worker.runMs : 0;

            JunitPlatformTimes junit = measureJunitPlatform(javaExe, javacExe, o);
            long junitPlatformMs = junit != null ? junit.wallMs : 0;
            int junitPlatformMethods = junit != null ? junit.methodCount : 0;
            boolean junitUsed = junit != null;

            // Prefer real JUnit Platform wall for the test slot when available.
            long testFork = junitUsed ? junit.wallMs : synthFork;
            long testRun = junitUsed ? 0 : synthRun;

            long resolveMs = 0;
            boolean resolveUsed = false;
            if (o.allowNetwork()) {
                long r = measureResolve();
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
                    junitPlatformMethods,
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
                Files.writeString(src, """
                        package probe;
                        final class Probe%d {
                          static int f(int x) { return x * %d + %d; }
                          static String s() { return "probe-%d"; }
                        }
                        """.formatted(i, i + 1, i * 3, i), StandardCharsets.UTF_8);
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

    private static WorkerTimes measureWorkerJvm(Path javaExe, Path javacExe) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("jk-calib-worker");
        try {
            Path out = Files.createDirectory(dir.resolve("out"));
            Path empty = dir.resolve("EmptyMain.java");
            Files.writeString(
                    empty,
                    "public class EmptyMain { public static void main(String[] a) {} }\n",
                    StandardCharsets.UTF_8);
            Path work = dir.resolve("WorkMain.java");
            Files.writeString(work, """
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
                    """.formatted(WORKER_METHODS), StandardCharsets.UTF_8);
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

    private record JunitPlatformTimes(long wallMs, int methodCount) {}

    /**
     * Real JUnit Platform: {@link #PLATFORM_METHODS} trivial {@code @Test} methods via
     * LauncherFactory so cold ETA can separate suite startup from per-method cost. Requires Jupiter
     * jars locally or {@code allowNetwork} to pull them from Central.
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
                StringBuilder tests = new StringBuilder();
                tests.append("import org.junit.jupiter.api.Test;\n");
                tests.append("public class CalibTest {\n");
                for (int i = 0; i < PLATFORM_METHODS; i++) {
                    tests.append("  @Test void m").append(i).append("() {}\n");
                }
                tests.append("}\n");
                Files.writeString(testSrc, tests.toString(), StandardCharsets.UTF_8);
                Files.writeString(mainSrc, """
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
                            long ok = summary.getSummary().getTestsSucceededCount();
                            if (summary.getSummary().getTestsFailedCount() > 0 || ok < %d) {
                              System.exit(1);
                            }
                          }
                        }
                        """.formatted(PLATFORM_METHODS), StandardCharsets.UTF_8);

                String cp = Classpaths.join(jars);
                List<String> compile = new ArrayList<>();
                compile.add(javacExe.toString());
                compile.add("-cp");
                compile.add(cp);
                compile.add("-d");
                compile.add(out.toString());
                compile.add(testSrc.toString());
                compile.add(mainSrc.toString());
                if (timeProcess(new ProcessBuilder(compile)) < 0) return null;

                String runCp = out + Classpaths.SEPARATOR + cp;
                List<Long> samples = new ArrayList<>();
                for (int i = 0; i < WARM_SAMPLES + 1; i++) {
                    long ms = timeProcess(new ProcessBuilder(javaExe.toString(), "-cp", runCp, "ProbeJunitMain"));
                    if (ms >= 0) samples.add(ms);
                }
                long wall = maxWarm(samples);
                return wall > 0 ? new JunitPlatformTimes(wall, PLATFORM_METHODS) : null;
            } finally {
                deleteTree(dir);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One Central GET. Production is {@link #httpGet}; the injection point exists so a test can
     * drive the whole fetch-and-publish route — the part JK-2456 got wrong — without a network
     * and without a mutable static base URL.
     */
    @FunctionalInterface
    interface CentralFetch {
        byte[] get(String url) throws IOException, InterruptedException;
    }

    /** Locate or fetch the minimal Jupiter + Platform jars needed to run one test. */
    static List<Path> resolveJunitClasspath(Options opts) {
        return resolveJunitClasspath(opts, HardwareProbe::httpGet);
    }

    /**
     * As {@link #resolveJunitClasspath(Options)} with the Central transport supplied.
     *
     * <p>Reads the artifact store's per-repo Maven views then the Maven local repository, and on a
     * miss with network fetches into the <em>store</em>. It used to read and write {@code
     * <cache>/repos/}, a fourth Maven tree that no resolver consults, that {@code jk status} counts
     * as cache and {@code jk cache nuke} deletes — so calibration both inflated the cache figure
     * and re-downloaded the same seven jars after every nuke.
     */
    static List<Path> resolveJunitClasspath(Options opts, CentralFetch fetch) {
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
            // Re-listed per artifact: a fetch publishes a new repo view under the store.
            List<Path> roots = artifactRoots(opts.storeRoot());
            Path found = findLocal(roots, rel);
            if (found == null && opts.allowNetwork()) {
                found = fetchFromCentral(opts.storeRoot(), rel, fetch);
            }
            if (found == null) {
                // Try any version already on disk (dogfood machines often have newer 6.x in ~/.m2).
                found = findAnyVersion(roots, groupPath, artifact);
            }
            if (found == null) return List.of(); // incomplete set → skip probe
            out.add(found);
        }
        return out;
    }

    /**
     * Maven-layout roots the probe may read, in preference order: each named repository view under
     * {@code <store>/repos/}, then the Maven local repository. Both are trees jk already resolves
     * against, so a hit here costs no network and no new bytes anywhere.
     */
    private static List<Path> artifactRoots(Path storeRoot) {
        List<Path> roots = new ArrayList<>();
        if (storeRoot != null) {
            Path repos = storeRoot.resolve("repos");
            if (Files.isDirectory(repos)) {
                try (Stream<Path> stream = Files.list(repos)) {
                    stream.filter(Files::isDirectory).sorted().forEach(roots::add);
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
        Path m2 = M2Dirs.localRepository();
        if (Files.isDirectory(m2)) roots.add(m2);
        return roots;
    }

    private static Path findLocal(List<Path> roots, String relativeMavenPath) {
        for (Path root : roots) {
            Path jar = root.resolve(relativeMavenPath);
            if (Files.isRegularFile(jar)) return jar;
        }
        return null;
    }

    private static Path findAnyVersion(List<Path> roots, String groupPath, String artifact) {
        for (Path root : roots) {
            Path artDir = root.resolve(groupPath).resolve(artifact);
            if (!Files.isDirectory(artDir)) continue;
            Path best = null;
            try (Stream<Path> vers = Files.list(artDir)) {
                for (Path verDir : (Iterable<Path>) vers::iterator) {
                    if (!Files.isDirectory(verDir)) continue;
                    Path jar = verDir.resolve(artifact + "-" + verDir.getFileName() + ".jar");
                    if (!Files.isRegularFile(jar)) continue;
                    // Prefer highest path name lexicographically (rough newest for dotted versions).
                    if (best == null
                            || jar.getParent()
                                            .getFileName()
                                            .toString()
                                            .compareTo(best.getParent()
                                                    .getFileName()
                                                    .toString())
                                    > 0) {
                        best = jar;
                    }
                }
            } catch (IOException ignored) {
                // best-effort
            }
            if (best != null) return best;
        }
        return null;
    }

    private static Path fetchFromCentral(Path storeRoot, String relativeMavenPath, CentralFetch fetch) {
        try {
            byte[] body = fetch.get(CENTRAL_BASE + relativeMavenPath);
            if (body == null || body.length == 0) return null;
            // Never persist unverified bytes: no .sha1, no store entry.
            byte[] sha1 = fetch.get(CENTRAL_BASE + relativeMavenPath + ".sha1");
            if (sha1 == null || sha1.length == 0) return null;
            // SHA-1 because that is what Central publishes beside the artifact; the sidecar names
            // the algorithm, so the call site does too.
            Optional<String> expected = Hashing.checksumFromSidecar(new String(sha1, StandardCharsets.US_ASCII), 40);
            if (expected.isEmpty() || !expected.get().equals(Hashing.hashHex("SHA-1", body))) return null;
            return storeCentralJar(storeRoot, relativeMavenPath, body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Publish verified Central bytes as an ordinary store entry — {@code <store>/repos/central/…}
     * plus the {@code .jk} memo, written through {@link RepoArtifactStore} exactly as a resolve
     * would. The memo is the difference between an artifact a later resolve can hash-verify and
     * reuse, and the seven orphan jars this probe used to leave under the cache root.
     */
    private static Path storeCentralJar(Path storeRoot, String relativeMavenPath, byte[] body) throws IOException {
        if (storeRoot == null) return null;
        Path tmp = Files.createTempFile("jk-calib-artifact", ".jar");
        try {
            Files.write(tmp, body);
            RepoArtifactStore store = RepoArtifactStore.forRepoName(storeRoot, RepositorySpec.CENTRAL);
            store.materialize(relativeMavenPath, tmp, Hashing.sha256Hex(body));
            return store.locate(relativeMavenPath).orElse(null);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Time a cold-ish HTTP GET of a tiny Central artifact. Uses a unique cache-buster query so we
     * do not measure only a fully warm CDN path when the object is already popular — still network.
     */
    private static long measureResolve() {
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

    /**
     * Through {@link Http}, not a private client. Calibration issues four Central GETs with a
     * cache-buster, which is exactly the traffic a rate-limit window has to see: a private client
     * missed the mirror and the per-host cooldown, so a probe could spend a 429 the resolver then
     * hit again without warning. When the host is already cooling, {@link Http} refuses and the
     * probe reports "unavailable" rather than adding to the pile.
     */
    private static byte[] httpGet(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> resp = HTTP.get(URI.create(url));
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
                MessageDigest md = Hashing.newSha256();
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

    private static void deleteContents(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) deleteTree(p);
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
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
