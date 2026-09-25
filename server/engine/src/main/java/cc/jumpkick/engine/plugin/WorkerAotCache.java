// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.StoreWriteGate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * JEP 514 startup caches for the short-lived {@code java … PluginMain} workers (java-compiler,
 * kotlinc, formatter). One file per tool at rest, recorded only for the JDK jk itself runs on:
 * {@code <state>/aot/<tool>-<jk-version>-<jdk tag>-<key>.aot}, mapped when present, otherwise one
 * background recording per key per engine process. A worker forked on any other JDK runs without a
 * cache. When a cache lands, the tool's other caches go; when the engine starts, every cache for
 * another jk version or another JDK goes ({@link #sweepForeign}). {@code JK_WORKER_AOT=off} (or
 * {@code -Djk.worker.aot=off}) turns mapping and recording off; HotSpot 25+ only.
 */
public final class WorkerAotCache {

    private WorkerAotCache() {}

    /** Builds the trainer's command line; {@code aotOutput} is where the JVM assembles the cache. */
    public interface Trainer {
        List<String> command(Path aotOutput, Path scratch) throws IOException;
    }

    /** Ceiling for one training run (fork, synthetic compile, assembly); typical is a few seconds. */
    static volatile long trainingTimeoutMillis = TimeUnit.SECONDS.toMillis(120);

    /** A trainer's temp sibling older than this belongs to a dead engine. */
    private static final long TMP_TTL_MILLIS = 24L * 60 * 60 * 1_000;

    /** {@code <tool>-<jk-version>-<8 hex jdk tag>-<16 hex key>.aot}; the tool may carry hyphens. */
    private static final Pattern NAME = Pattern.compile(
            "(?<tool>.+)-(?<version>[^-]+(?:-SNAPSHOT)?)-(?<jdk>[0-9a-f]{8})-(?<key>[0-9a-f]{16})\\.aot");

    /** Keys with a trainer running in this process. */
    private static final Set<Path> TRAINING = ConcurrentHashMap.newKeySet();

    /** Keys whose trainer failed in this process; retried after the next engine start. */
    private static final Set<Path> FAILED = ConcurrentHashMap.newKeySet();

    /** Trainer forks alive right now; a store wipe and the engine's shutdown stop them. */
    private static final Set<Process> LIVE = ConcurrentHashMap.newKeySet();

    /** Where worker caches live: {@code <state>/aot/}. */
    public static Path dir() {
        return JkDirs.state().resolve("aot");
    }

    /** False when {@code jk.worker.aot} / {@code JK_WORKER_AOT} is {@code off}, {@code false} or {@code 0}. */
    public static boolean enabled() {
        String raw = System.getProperty("jk.worker.aot");
        if (raw == null || raw.isBlank()) raw = System.getenv("JK_WORKER_AOT");
        return EnvValues.parseBool(raw == null ? "" : raw.trim()).orElse(true);
    }

    /**
     * The JVM flags that map {@code tool}'s cache, or an empty list when the worker runs on a JDK
     * other than jk's own, the switch is off, the host cannot record a cache, or there is none yet.
     * A miss starts the trainer in the background; the build that missed runs cold and the next one
     * maps. Never throws.
     */
    public static List<String> flags(
            String tool, @Nullable Path javaHome, String workerClasspath, List<String> jvmFlags, Trainer trainer) {
        if (!enabled() || javaHome == null) return List.of();
        try {
            Host host = engineHost();
            if (host == null || !host.eligible() || !host.home().equals(normalize(javaHome))) return List.of();
            Path cache = cacheFile(tool, host, workerClasspath, jvmFlags);
            if (usable(cache)) {
                touch(cache);
                return List.of("-XX:AOTCache=" + cache, "-Xlog:aot=off");
            }
            deleteIfEmpty(cache);
            if (!FAILED.contains(cache) && !StoreWriteGate.wipedSinceStart()) {
                trainAsync(tool + " worker (" + host.vendor() + " " + host.version() + ")", cache, trainer);
            }
        } catch (RuntimeException e) {
            // An accelerator, never a dependency: a build must not fail over its startup cache.
            Log.debug("WorkerAotCache.flags: skipped", e);
        }
        return List.of();
    }

    /**
     * Delete every cache this engine can never map: another jk version's, another JDK's, and
     * trainer temp files a dead engine left behind. Runs once at engine start; best-effort.
     */
    public static void sweepForeign() {
        Host host = engineHost();
        if (host == null) return;
        sweepForeign(dir(), host);
    }

    static void sweepForeign(Path dir, Host host) {
        String tag = jdkTag(host);
        long now = Clock.SYSTEM.millis();
        try {
            PathUtil.forEachRegularFile(dir, d -> true, (p, attrs) -> {
                String name = p.getFileName().toString();
                if (name.endsWith(".aot")) {
                    var m = NAME.matcher(name);
                    boolean ours = m.matches()
                            && m.group("version").equals(JkVersion.VERSION)
                            && m.group("jdk").equals(tag);
                    if (!ours) deleteQuietly(p);
                } else if (name.contains(".aot.tmp-")
                        && now - attrs.lastModifiedTime().toMillis() > TMP_TTL_MILLIS) {
                    deleteQuietly(p);
                }
            });
        } catch (IOException ignored) {
            // opportunistic: a leftover cache costs disk, not correctness
        }
    }

    /** The cache file for one key under jk's own JDK. */
    static Path cacheFile(String tool, Host host, String workerClasspath, List<String> jvmFlags) {
        StringBuilder key = new StringBuilder()
                .append(effectiveGc(JvmOptions.batchFlags(1)))
                .append('|')
                .append(workerClasspath);
        for (String flag : jvmFlags) key.append('\n').append(flag);
        String hash = Hashing.sha256Hex(key.toString()).substring(0, 16);
        return dir().resolve(tool + "-" + JkVersion.VERSION + "-" + jdkTag(host) + "-" + hash + ".aot");
    }

    /** Eight hex characters naming the JDK a cache was recorded under. */
    static String jdkTag(Host host) {
        return Hashing.sha256Hex(host.home() + "|" + host.vendor().name() + "|" + host.version())
                .substring(0, 8);
    }

    /** What a JDK's {@code release} file says it is. */
    record Host(Path home, JdkVendor vendor, String version) {
        /** HotSpot 25+ records mappable caches; Graal hosts and older JDKs do not. */
        boolean eligible() {
            if (vendor == JdkVendor.ORACLE_GRAALVM || vendor == JdkVendor.GRAALVM_CE) return false;
            try {
                return Integer.parseInt(version.split("[.+-]")[0]) >= 25;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    /** The JDK this engine runs on, or null when its {@code release} file cannot be read. */
    static @Nullable Host engineHost() {
        return host(JavaHomes.runningJavaHome());
    }

    static @Nullable Host host(Path javaHome) {
        Path release = javaHome.resolve("release");
        if (!Files.isRegularFile(release)) return null;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(release)) {
            props.load(in);
        } catch (IOException e) {
            return null;
        }
        String version = props.getProperty("JAVA_VERSION", "").trim().replace("\"", "");
        if (version.isEmpty()) return null;
        return new Host(normalize(javaHome), JdkVendor.fromProperties(props), version);
    }

    private static Path normalize(Path home) {
        return home.toAbsolutePath().normalize();
    }

    /** The collector named in {@code flags} ({@code parallelgc}, {@code serialgc}, …), or {@code default}. */
    static String effectiveGc(List<String> flags) {
        for (String f : flags) {
            if (f.startsWith("-XX:+Use") && f.endsWith("GC")) {
                return f.substring("-XX:+Use".length()).toLowerCase(Locale.ROOT);
            }
        }
        return "default";
    }

    /** A cache is present only as a non-empty regular file; a zero-byte leftover counts as missing. */
    static boolean usable(Path cache) {
        try {
            return Files.isRegularFile(cache) && Files.size(cache) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteIfEmpty(Path cache) {
        try {
            if (Files.isRegularFile(cache) && Files.size(cache) == 0) Files.deleteIfExists(cache);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void trainAsync(String what, Path cache, Trainer trainer) {
        if (!TRAINING.add(cache)) return;
        // The trainer belongs to the cache, not to the request that missed it: a cancelled build
        // must not kill the recording the next build maps.
        Thread t = new Thread(() -> runTrainer(what, cache, trainer), "jk-worker-aot-train");
        t.setDaemon(true);
        t.start();
    }

    private static void runTrainer(String what, Path cache, Trainer trainer) {
        Path tmp = cache.resolveSibling(
                cache.getFileName() + ".tmp-" + ProcessHandle.current().pid());
        Path scratch = null;
        Process p = null;
        try {
            Files.createDirectories(Objects.requireNonNull(cache.getParent(), "cache dir"));
            scratch = Files.createTempDirectory("jk-worker-aot-");
            Files.createDirectories(scratch.resolve("out"));
            // Not registered for request cancel: a cancelled build must not kill the recording the
            // next build maps. The trainer is still contained.
            p = JobWorkers.startDetached(new ProcessBuilder(trainer.command(tmp, scratch))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD));
            LIVE.add(p);
            Log.info("jk engine: recording a startup cache for the " + what + " (pid " + p.pid() + ")");
            if (!p.waitFor(trainingTimeoutMillis, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                FAILED.add(cache);
                Log.warn("jk engine: the " + what + " startup cache recording overran; killed");
                return;
            }
            if (p.exitValue() == 0 && usable(tmp)) {
                AtomicWrites.moveInto(tmp, cache);
                sweepSiblings(cache);
                Log.info("jk engine: startup cache ready for the " + what + " (" + cache.getFileName() + ")");
            } else {
                FAILED.add(cache);
                Log.warn("jk engine: the " + what + " startup cache recording produced no cache (exit " + p.exitValue()
                        + ")");
            }
        } catch (IOException e) {
            FAILED.add(cache);
            Log.warn("jk engine: startup cache recording for the " + what + " skipped: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (p != null) LIVE.remove(p);
            deleteQuietly(tmp);
            deleteQuietly(tmp.resolveSibling(tmp.getFileName() + ".config")); // an interrupted recording
            if (scratch != null) PathUtil.deleteRecursively(scratch);
            TRAINING.remove(cache);
        }
    }

    /** One file per tool: when a cache lands, the tool's other caches go, whatever their age. */
    static void sweepSiblings(Path landed) {
        var m = NAME.matcher(landed.getFileName().toString());
        if (!m.matches()) return;
        String tool = m.group("tool");
        Path dir = Objects.requireNonNull(landed.getParent(), "cache dir");
        try {
            PathUtil.forEachRegularFile(dir, d -> true, (p, attrs) -> {
                if (p.equals(landed)) return;
                var other = NAME.matcher(p.getFileName().toString());
                if (other.matches() && other.group("tool").equals(tool)) deleteQuietly(p);
            });
        } catch (IOException ignored) {
            // opportunistic: a leftover cache costs disk, not correctness
        }
    }

    /**
     * Kill and reap every trainer this process started, returning their pids. A trainer holds
     * store jars open, and Windows refuses to delete a file another process has open, so the store
     * wipe and the engine's shutdown both call this first.
     */
    public static List<Long> stopTrainers() {
        List<Long> pids = new ArrayList<>();
        for (Process p : LIVE) {
            pids.add(p.pid());
            try {
                p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return pids;
    }

    /** Test seam: whether a trainer is still running in this process. */
    static boolean trainingInFlight() {
        return !TRAINING.isEmpty();
    }

    /** Test seam: forget this process's failed keys. */
    static void forgetFailuresForTests() {
        FAILED.clear();
    }

    private static void touch(Path p) {
        try {
            Files.setLastModifiedTime(p, FileTime.fromMillis(Clock.SYSTEM.millis()));
        } catch (IOException ignored) {
            // best-effort; only a reader of the directory listing sees it
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
