// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.AotManifest;
import cc.jumpkick.util.AotSettings;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * JEP 514 AOT caches for short-lived <em>{@code java … PluginMain}</em> workers (kotlin-compiler,
 * java-compiler ToolProvider host, formatter). <strong>Not</strong> used for bare {@code javac}
 * launcher forks — that path saw no win and no longer trains or maps caches. Background train on
 * first miss; later forks map the cache. Key includes JDK home/vendor/version, GC, and plugin
 * classpath. Switches: {@link cc.jumpkick.util.AotSettings} — {@code JK_WORKER_AOT=off} disables
 * map+train; {@code JK_AOT_TRAIN=off} disables train-on-miss only. HotSpot 25+ only (Graal
 * ineligible).
 */
public final class PluginAot {

    private PluginAot() {}

    /**
     * Ceiling for one training run (fork + synthetic compile + assembly); typical is ~2s.
     * Non-final for tests only (simulated overrun without a two-minute wait).
     */
    static volatile long trainingTimeoutMillis = TimeUnit.SECONDS.toMillis(120);

    /**
     * A sticky {@code .noaot} marker older than this is treated as absent at read time and
     * removed, giving the key a fresh training attempt. Failures within the window still back
     * off (no retry storm); without an expiry, the sole key of a tool that once failed would
     * never retrain — {@link #sweepTool}'s orphan sweep only runs from a <em>successful</em>
     * train of a sibling key.
     */
    static final long NOAOT_RETRY_MILLIS = 7L * 24 * 60 * 60 * 1_000;

    /** A claim file older than this is a crashed trainer's leftover — reclaimable. */
    private static final long CLAIM_STALE_MILLIS = 10 * 60 * 1_000;

    /**
     * Retention per tool: the newest caches by last-use mtime survive a publish sweep. Multiple
     * keys are legitimately live at once (two projects on different toolchain JDKs, two Kotlin
     * versions) — a keep-only-current sweep would make alternating builds retrain forever.
     */
    private static final int KEEP_PER_TOOL = 4;

    /** Caches (and failure markers) untouched this long are dead keys — reclaim the disk. */
    private static final long UNUSED_TTL_MILLIS = 30L * 24 * 60 * 60 * 1_000;

    /**
     * Relatime-style window for the manifest {@code last_used} refresh: a cache hit inside this
     * window skips the {@code aot.toml} lock/read/rewrite entirely. The cache file's own mtime
     * ({@link #touch}) is what retention reads and is refreshed on <em>every</em> hit; the
     * manifest timestamp is a human index and hour granularity is plenty.
     */
    static final long LAST_USED_REFRESH_MILLIS = 60L * 60 * 1_000;

    /** When this JVM last rewrote a cache's manifest {@code last_used} (throttle memory). */
    private static final ConcurrentMap<Path, Long> LAST_USED_WRITTEN = new ConcurrentHashMap<>();

    /** In-JVM double-spawn guard (the claim file guards across processes). */
    private static final Set<Path> TRAINING = ConcurrentHashMap.newKeySet();

    /** Builds the full trainer command line; {@code aotOutput} is where the JVM assembles the cache. */
    public interface TrainerCommand {
        List<String> build(Path aotOutput, Path scratchDir) throws IOException;
    }

    static boolean enabled() {
        return AotSettings.workerAotEnabled();
    }

    /** Train-on-miss for workers; see {@link cc.jumpkick.util.AotSettings#trainingEnabled()}. */
    static boolean trainingEnabled() {
        return AotSettings.trainingEnabled();
    }

    /** Where plugin AOT caches live: {@code <state>/aot/}. */
    public static Path dir() {
        return JkDirs.state().resolve("aot");
    }

    // ---- plugin workers (java -cp … PluginMain) -------------------------------------------

    /**
     * JVM flags for a forked plugin worker ({@code java … -cp <plugin> PluginMain …}): {@code
     * -XX:AOTCache=…} when a cache exists for (host JDK, GC, classpath + tool tag), else empty —
     * and kick off a background trainer when the host is HotSpot 25+. The classpath is part of the
     * key because the plugin <em>is</em> the app (Kotlin compiler, java-compiler ToolProvider host,
     * …). Never blocks, never throws.
     *
     * <p>{@code tool} is a short prefix ({@code kotlinc}, {@code java-compiler}, {@code formatter})
     * so caches do not collide across plugin kinds that share a jar path shape. File names are
     * {@code <tool>-<jk-version>-<16hex>.aot} so a primary wipe can keep the live product line.
     */
    public static List<String> pluginWorkerFlags(
            String tool, Path javaHome, String workerClasspath, TrainerCommand trainer) {
        if (!enabled() || javaHome == null) return List.of();
        try {
            JdkId id = jdkId(javaHome);
            if (id == null) return List.of();
            List<String> batch = JvmOptions.batchFlags(1);
            String gc = effectiveGc(batch); // must match javaCommand / PluginLoader
            String prefix = (tool == null || tool.isBlank()) ? "plugin" : tool;
            String cacheKey = key(id, gc, workerClasspath);
            Path cache = cacheFile(prefix, cacheKey);
            CacheMeta meta = new CacheMeta(prefix, cacheKey, id, gc, workerClasspath, batch);
            if (usableCache(cache)) {
                touch(cache); // retention is by last use; the JVM mapping a cache never updates mtime
                recordUse(cache, meta);
                return List.of("-XX:AOTCache=" + cache, "-Xlog:aot=off");
            }
            deleteIfEmpty(cache); // truncated leftover: treat as missing so it can retrain
            if (eligible(id) && trainingEnabled() && !noAotBlocked(cache)) {
                trainAsync(prefix + " worker (" + id.vendor() + " " + id.version() + ")", cache, trainer, meta);
            }
        } catch (RuntimeException e) {
            // AOT is an accelerator, never a dependency — never fail the build.
        }
        return List.of();
    }

    /**
     * JVM flags to prepend to the kotlinc plugin's {@code java} spawn. Delegates to {@link
     * #pluginWorkerFlags} with tool tag {@code kotlinc}.
     */
    public static List<String> kotlincFlags(Path javaHome, String workerClasspath, TrainerCommand trainer) {
        return pluginWorkerFlags("kotlinc", javaHome, workerClasspath, trainer);
    }

    /**
     * JVM flags for the {@code jk-java-compiler} plugin spawn ({@code java -cp worker PluginMain}).
     * Hosts ToolProvider/javac <em>inside</em> a short-lived JVM (not the bare {@code javac}
     * launcher). Tool tag {@code java-compiler}.
     */
    public static List<String> javaCompilerFlags(Path javaHome, String workerClasspath, TrainerCommand trainer) {
        return pluginWorkerFlags("java-compiler", javaHome, workerClasspath, trainer);
    }

    /**
     * JVM flags for the {@code jk-formatter} plugin spawn. Tool tag {@code formatter}. Trains on
     * first miss (same as kotlinc) — the worker classpath is host-stable, but Palantir/ktfmt are
     * resolved per run and loaded from the spec, so idle bootstrap would record a thinner set
     * than the first real {@code jk format}.
     */
    public static List<String> formatterFlags(Path javaHome, String workerClasspath, TrainerCommand trainer) {
        return pluginWorkerFlags("formatter", javaHome, workerClasspath, trainer);
    }

    /** True when this host JDK can record AOT caches (HotSpot 25+, not Graal). */
    public static boolean hostEligible(Path javaHome) {
        if (javaHome == null) return false;
        try {
            JdkId id = jdkId(javaHome);
            return id != null && eligible(id);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Cache path for a tool/host/classpath key, or {@code null} when the host is ineligible / unreadable.
     */
    public static Path cachePath(String tool, Path javaHome, String workerClasspath) {
        if (javaHome == null) return null;
        try {
            JdkId id = jdkId(javaHome);
            if (id == null) return null;
            String gc = effectiveGc(JvmOptions.batchFlags(1));
            String prefix = (tool == null || tool.isBlank()) ? "plugin" : tool;
            return cacheFile(prefix, key(id, gc, workerClasspath));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * On-disk name for a worker cache: {@code <tool>-<JkVersion>-<16hex>.aot}. Version is in the
     * path so {@link cc.jumpkick.cache.EngineInstall#wipeAotDirectory} can keep the live product
     * line and drop every other generation's worker + engine caches.
     */
    static Path cacheFile(String toolPrefix, String cacheKey) {
        return dir().resolve(toolPrefix + "-" + JkVersion.VERSION + "-" + cacheKey + ".aot");
    }

    /**
     * Ensure a worker AOT cache exists for {@code tool}: if missing and the host is HotSpot 25+,
     * train <em>synchronously</em> (install {@code jk optimize} path). Returns whether a cache file
     * is present after this call. Never throws.
     */
    public static boolean ensureTrained(
            String tool, Path javaHome, String workerClasspath, TrainerCommand trainer, long timeoutMs) {
        return ensureTrained(tool, javaHome, workerClasspath, trainer, timeoutMs, false);
    }

    /**
     * Like {@link #ensureTrained(String, Path, String, TrainerCommand, long)} with optional
     * {@code force} retrain (delete existing cache / noaot marker first).
     */
    public static boolean ensureTrained(
            String tool, Path javaHome, String workerClasspath, TrainerCommand trainer, long timeoutMs, boolean force) {
        if (!enabled() || javaHome == null || trainer == null) return false;
        try {
            JdkId id = jdkId(javaHome);
            if (id == null || !eligible(id)) return false;
            List<String> batch = JvmOptions.batchFlags(1);
            String gc = effectiveGc(batch);
            String prefix = (tool == null || tool.isBlank()) ? "plugin" : tool;
            String cacheKey = key(id, gc, workerClasspath);
            Path cache = cacheFile(prefix, cacheKey);
            CacheMeta meta = new CacheMeta(prefix, cacheKey, id, gc, workerClasspath, batch);
            if (force) {
                try {
                    Files.deleteIfExists(cache);
                    Files.deleteIfExists(noaotMarker(cache));
                } catch (IOException ignored) {
                }
            } else if (usableCache(cache)) {
                touch(cache);
                recordUse(cache, meta);
                return true;
            } else {
                deleteIfEmpty(cache); // truncated leftover: retrain below
            }
            if (!trainingEnabled() || noAotBlocked(cache)) return false;
            String what = prefix + " worker (" + id.vendor() + " " + id.version() + ")";
            trainBlocking(what, cache, trainer, Math.max(1_000L, timeoutMs), meta);
            return usableCache(cache);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Wait until {@code cache} is usable or {@code timeoutMs} elapses (async train join). */
    public static boolean waitForCache(Path cache, long timeoutMs) {
        if (cache == null) return false;
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (usableCache(cache)) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return usableCache(cache);
            }
        }
        return usableCache(cache);
    }

    /**
     * The one definition of "cache present": a non-empty regular file. Zero-byte leftovers
     * (disk-full truncation, interrupted copy) count as missing everywhere, or the warmup gate
     * ({@code HostWarmup.needsWorkerAot}) and the train paths disagree forever.
     */
    public static boolean usableCache(Path cache) {
        try {
            return cache != null && Files.isRegularFile(cache) && Files.size(cache) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteIfEmpty(Path cache) {
        try {
            if (cache != null && Files.isRegularFile(cache) && Files.size(cache) == 0) {
                Files.deleteIfExists(cache);
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    // ---- keying ---------------------------------------------------------------------------

    /** What the release file says a JDK is; {@code null} when it can't be read. */
    record JdkId(Path home, JdkVendor vendor, String version) {}

    static JdkId jdkId(Path javaHome) {
        Path release = javaHome.resolve("release");
        if (!Files.isRegularFile(release)) return null;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(release)) {
            props.load(in);
        } catch (IOException e) {
            return null;
        }
        String version = unquote(props.getProperty("JAVA_VERSION", ""));
        if (version.isEmpty()) return null;
        return new JdkId(javaHome.toAbsolutePath().normalize(), JdkVendor.fromProperties(props), version);
    }

    private static String unquote(String v) {
        String s = v.trim();
        return (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) ? s.substring(1, s.length() - 1) : s;
    }

    /** HotSpot 25+ records mappable caches; Graal hosts and older JDKs never train. */
    static boolean eligible(JdkId id) {
        if (id.vendor() == JdkVendor.ORACLE_GRAALVM || id.vendor() == JdkVendor.GRAALVM_CE) return false;
        try {
            int feature = Integer.parseInt(id.version().split("[.+-]")[0]);
            return feature >= 25;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * The GC selection visible in a flag list ({@code -J}-prefixed or bare), lowercased — {@code
     * "zgc"}, {@code "g1gc"}, {@code "serialgc"}, … — or {@code "default"} when no {@code
     * -XX:+Use*GC} flag is present. Part of the cache key: AOT code is compiled against the
     * training GC's barriers, and a mismatch maps nothing.
     */
    static String effectiveGc(List<String> flags) {
        for (String f : flags) {
            String bare = f.startsWith("-J") ? f.substring(2) : f;
            if (bare.startsWith("-XX:+Use") && bare.endsWith("GC")) {
                return bare.substring("-XX:+Use".length()).toLowerCase(Locale.ROOT);
            }
        }
        return "default";
    }

    static String key(JdkId id, String gc, String extra) {
        return Hashing.sha256Hex(id.home() + "|" + id.vendor().name() + "|" + id.version() + "|" + gc + "|" + extra)
                .substring(0, 16);
    }

    // ---- training -------------------------------------------------------------------------

    /** Inputs that identify a worker cache key — recorded in {@link AotManifest}. */
    record CacheMeta(String tool, String key, JdkId id, String gc, String classpath, List<String> jvmFlags) {
        CacheMeta {
            jvmFlags = jvmFlags == null ? List.of() : List.copyOf(jvmFlags);
        }
    }

    /**
     * Kick off one background training run for {@code cache}, claim-guarded twice over: an in-JVM
     * set (this engine) and a sibling {@code .training} claim file (other processes; stale claims
     * from a crashed trainer are reclaimed after {@link #CLAIM_STALE_MILLIS}). The trainer records
     * to a temp sibling and the assembled cache is atomically renamed into place, so readers only
     * ever see a complete file. Trainer output is discarded — success is the cache appearing, and
     * both outcomes get an engine-log line.
     */
    static void trainAsync(String what, Path cache, TrainerCommand trainer) {
        trainAsync(what, cache, trainer, null);
    }

    static void trainAsync(String what, Path cache, TrainerCommand trainer, CacheMeta meta) {
        if (trainer == null || !TRAINING.add(cache)) return;
        Path claim = cache.resolveSibling(cache.getFileName() + ".training");
        try {
            Files.createDirectories(cache.getParent());
            if (!claimed(claim)) {
                TRAINING.remove(cache);
                return;
            }
        } catch (IOException e) {
            TRAINING.remove(cache);
            return;
        }
        Thread t = new Thread(() -> runTrainer(what, cache, claim, trainer, meta), "jk-worker-aot-train");
        t.setDaemon(true);
        t.start();
    }

    /** Synchronous train for {@link #ensureTrained} (install optimize). */
    private static void trainBlocking(String what, Path cache, TrainerCommand trainer, long timeoutMs, CacheMeta meta) {
        if (trainer == null || !TRAINING.add(cache)) {
            // Another train in flight — wait for the cache file.
            waitForCache(cache, timeoutMs);
            return;
        }
        Path claim = cache.resolveSibling(cache.getFileName() + ".training");
        try {
            Files.createDirectories(cache.getParent());
            if (!claimed(claim)) {
                TRAINING.remove(cache);
                waitForCache(cache, timeoutMs);
                return;
            }
        } catch (IOException e) {
            TRAINING.remove(cache);
            return;
        }
        Thread t = new Thread(() -> runTrainer(what, cache, claim, trainer, meta), "jk-worker-aot-train-sync");
        t.start();
        try {
            t.join(timeoutMs);
            if (t.isAlive()) {
                // Trainer still running; leave it daemon-like by not interrupting (runTrainer has its own timeout).
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Atomically create the claim file; a fresh existing claim loses, a stale one is replaced. */
    private static boolean claimed(Path claim) throws IOException {
        try {
            Files.createFile(claim);
            return true;
        } catch (FileAlreadyExistsException e) {
            try {
                long age = System.currentTimeMillis()
                        - Files.getLastModifiedTime(claim).toMillis();
                if (age < CLAIM_STALE_MILLIS) return false;
                Files.deleteIfExists(claim);
                Files.createFile(claim);
                return true;
            } catch (IOException race) {
                return false; // someone else won the reclaim race — their trainer serves us both
            }
        }
    }

    private static void runTrainer(String what, Path cache, Path claim, TrainerCommand trainer, CacheMeta meta) {
        Path scratch = null;
        boolean keepClaim = false;
        Path tmp = cache.resolveSibling(
                cache.getFileName() + ".tmp-" + ProcessHandle.current().pid());
        try {
            scratch = Files.createTempDirectory("jk-worker-aot-");
            Files.createDirectories(scratch.resolve("out"));
            List<String> cmd = trainer.build(tmp, scratch);
            Process p = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            System.err.println("jk engine: AOT-training " + what + " in the background (pid " + p.pid() + ")");
            if (!p.waitFor(trainingTimeoutMillis, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                // NO sticky marker: an overrun is usually transient (first Kotlin compile on a
                // loaded machine), and a sticky .noaot here would disable AOT for the key
                // permanently. Refresh and KEEP the claim file instead — fresh claims
                // block retrains until CLAIM_STALE_MILLIS, a bounded backoff, not a life sentence.
                touch(claim);
                keepClaim = true;
                System.err.println("jk engine: AOT training for " + what + " overran; killed (will retry later)");
                return;
            }
            if (p.exitValue() == 0 && Files.exists(tmp)) {
                AtomicWrites.moveInto(tmp, cache);
                if (meta != null) recordReady(cache, meta, false);
                else recordReadyBare(cache);
                sweepTool(cache);
                System.err.println("jk engine: AOT cache ready for " + what + " (" + cache.getFileName() + ")");
            } else {
                markNoAot(cache, meta); // sticky per key — a JDK/Kotlin/GC bump mints a new key and retries
                System.err.println(
                        "jk engine: AOT training for " + what + " produced no cache (exit " + p.exitValue() + ")");
            }
        } catch (IOException e) {
            System.err.println("jk engine: AOT training for " + what + " skipped: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            deleteQuietly(tmp);
            deleteQuietly(tmp.resolveSibling(tmp.getFileName() + ".config")); // interrupted recording
            if (!keepClaim) deleteQuietly(claim);
            if (scratch != null) deleteRecursivelyQuietly(scratch);
            TRAINING.remove(cache);
        }
    }

    /**
     * Retention sweep for one tool's caches, run at publish time. Keeps the {@link #KEEP_PER_TOOL}
     * most-recently-used caches (use = {@link #touch} at flag hand-out) and drops the rest, plus
     * anything — cache or orphaned {@code .noaot} failure marker — untouched for
     * {@link #UNUSED_TTL_MILLIS}. Several keys are legitimately live at once (different toolchain
     * JDKs, Kotlin versions, GC pins); expiring a stale {@code .noaot} also gives a once-failed key
     * a fresh training attempt. Engine caches ({@code engine-<version>-…}) share this directory but
     * are version-lifecycle-owned (EngineClient sweep + EngineInstall.gc) — never touched here.
     *
     * <p>Names are {@code <tool>-<jk-version>-<16hex>.aot}; the pool is one product version of one
     * tool (e.g. {@code java-compiler-0.12.0-*}).
     */
    private static void sweepTool(Path cache) {
        // "<tool>-<version>-<16 hex>.aot" → strip fixed-width key suffix, not up to the first
        // hyphen (tool tags may contain hyphens: java-compiler).
        String name = cache.getFileName().toString();
        if (!name.endsWith(".aot") || name.length() < 22) return;
        String tool = name.substring(0, name.length() - 20); // 16-hex key + ".aot"
        if (!tool.endsWith("-")) return;
        long now = System.currentTimeMillis();
        List<Path> primaries = new ArrayList<>();
        List<Path> markers = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(cache.getParent(), tool + "*")) {
            for (Path p : entries) {
                String n = p.getFileName().toString();
                if (n.endsWith(".aot")) primaries.add(p);
                else if (n.endsWith(".aot.noaot")) markers.add(p);
            }
        } catch (IOException ignored) {
            return; // opportunistic: a leftover cache costs disk, not correctness
        }
        primaries.sort(Comparator.comparingLong(PluginAot::mtime).reversed());
        List<String> removed = new ArrayList<>();
        for (int i = 0; i < primaries.size(); i++) {
            Path p = primaries.get(i);
            if (p.equals(cache)) continue; // the cache that just landed always survives
            if (i >= KEEP_PER_TOOL || now - mtime(p) > UNUSED_TTL_MILLIS) {
                removed.add(p.getFileName().toString());
                deleteQuietly(p);
                deleteQuietly(noaotMarker(p));
                deleteQuietly(p.resolveSibling(p.getFileName() + ".config"));
            }
        }
        for (Path m : markers) {
            Path primary = m.resolveSibling(m.getFileName()
                    .toString()
                    .substring(0, m.getFileName().toString().length() - ".noaot".length()));
            if (!Files.exists(primary) && now - mtime(m) > UNUSED_TTL_MILLIS) {
                removed.add(primary.getFileName().toString());
                deleteQuietly(m);
            }
        }
        if (!removed.isEmpty()) {
            Path aotDir = cache.getParent();
            if (aotDir != null) {
                AotManifest.remove(aotDir, removed);
                AotManifest.reconcile(aotDir);
            }
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0; // unreadable sorts oldest — first in line to be reclaimed
        }
    }

    private static void touch(Path p) {
        try {
            Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // best-effort; worst case the cache looks colder than it is
        }
    }

    /** Sticky "training failed for this key" marker sibling (skip retrain-on-miss until cleared). */
    public static Path noaotMarker(Path cache) {
        return cache.resolveSibling(cache.getFileName() + ".noaot");
    }

    /**
     * Is training for {@code cache} blocked by its {@code .noaot} marker? Markers older than
     * {@link #NOAOT_RETRY_MILLIS} are expired at read time — deleted, and the key retrains. The
     * sweep-side expiry ({@link #sweepTool}) only runs from a successful sibling train, so a
     * tool whose sole key failed would otherwise never retry.
     */
    static boolean noAotBlocked(Path cache) {
        Path marker = noaotMarker(cache);
        try {
            if (!Files.exists(marker)) return false;
            long age = System.currentTimeMillis()
                    - Files.getLastModifiedTime(marker).toMillis();
            if (age <= NOAOT_RETRY_MILLIS) return true;
            deleteQuietly(marker); // expired: one bad day must not disable AOT forever
            return false;
        } catch (IOException e) {
            return true; // unreadable marker: skip training, never fail the build
        }
    }

    private static void markNoAot(Path cache) {
        markNoAot(cache, null);
    }

    private static void markNoAot(Path cache, CacheMeta meta) {
        try {
            Files.createFile(noaotMarker(cache));
        } catch (IOException ignored) {
            // best-effort; worst case the next compile retries training
        }
        recordNoAot(cache, meta);
    }

    // ---- aot.toml -------------------------------------------------------------------------

    /**
     * Record a cache hit in the manifest, throttled: the full {@code aot.toml} lock/read/rewrite
     * runs at most once per {@link #LAST_USED_REFRESH_MILLIS} per cache (per javac/kotlinc fork
     * would mean once per module compile — pure churn, and the main source of concurrent-write
     * collisions). First hit after an engine restart consults the manifest's recorded
     * {@code last_used} so a fresh value on disk is not rewritten either.
     */
    static void recordUse(Path cache, CacheMeta meta) {
        if (cache == null || meta == null) return;
        long now = System.currentTimeMillis();
        Long prev = LAST_USED_WRITTEN.get(cache);
        if (prev != null && now - prev < LAST_USED_REFRESH_MILLIS) return;
        if (prev == null && manifestLastUsedFresh(cache, now)) {
            LAST_USED_WRITTEN.putIfAbsent(cache, now);
            return;
        }
        recordReady(cache, meta, true);
    }

    /** Does the manifest already carry a {@code last_used} inside the refresh window? */
    private static boolean manifestLastUsedFresh(Path cache, long now) {
        Path aotDir = cache.getParent();
        if (aotDir == null) return false;
        String name = cache.getFileName().toString();
        for (AotManifest.Entry e : AotManifest.load(aotDir)) {
            if (!name.equals(e.file())) continue;
            String lastUsed = e.lastUsed();
            if (lastUsed == null || lastUsed.isBlank()) return false;
            try {
                long t = OffsetDateTime.parse(lastUsed).toInstant().toEpochMilli();
                return now - t < LAST_USED_REFRESH_MILLIS;
            } catch (RuntimeException parse) {
                return false;
            }
        }
        return false;
    }

    private static void recordReady(Path cache, CacheMeta meta, boolean touchLastUsed) {
        if (cache == null || meta == null) return;
        Path aotDir = cache.getParent();
        if (aotDir == null) return;
        String now = AotManifest.nowIso();
        AotManifest.Entry.Builder b = AotManifest.Entry.builder(
                        cache.getFileName().toString())
                .tool(meta.tool())
                .key(meta.key())
                .jkVersion(JkVersion.VERSION)
                .status("ready")
                .sizeBytes(AotManifest.sizeOf(cache))
                .jdkHome(meta.id().home().toString())
                .jdkVendor(meta.id().vendor().name())
                .jdkVersion(meta.id().version())
                .gc(meta.gc())
                .classpathString(meta.classpath())
                .jvmFlags(meta.jvmFlags());
        if (touchLastUsed) b.lastUsed(now);
        else b.created(now).lastUsed(now);
        AotManifest.upsert(aotDir, b.build());
        LAST_USED_WRITTEN.put(cache, System.currentTimeMillis()); // seed the recordUse throttle
    }

    private static void recordReadyBare(Path cache) {
        if (cache == null) return;
        Path aotDir = cache.getParent();
        if (aotDir == null) return;
        String name = cache.getFileName().toString();
        String now = AotManifest.nowIso();
        AotManifest.Entry.Builder b = AotManifest.Entry.builder(name)
                .status("ready")
                .sizeBytes(AotManifest.sizeOf(cache))
                .created(now)
                .lastUsed(now);
        AotManifest.fillToolKey(b, name);
        AotManifest.upsert(aotDir, b.build());
    }

    private static void recordNoAot(Path cache, CacheMeta meta) {
        if (cache == null) return;
        Path aotDir = cache.getParent();
        if (aotDir == null) return;
        String name = cache.getFileName().toString();
        AotManifest.Entry.Builder b = AotManifest.Entry.builder(name).status("noaot");
        if (meta != null) {
            b.tool(meta.tool())
                    .key(meta.key())
                    .jkVersion(JkVersion.VERSION)
                    .jdkHome(meta.id().home().toString())
                    .jdkVendor(meta.id().vendor().name())
                    .jdkVersion(meta.id().version())
                    .gc(meta.gc())
                    .classpathString(meta.classpath())
                    .jvmFlags(meta.jvmFlags());
        } else {
            AotManifest.fillToolKey(b, name);
        }
        AotManifest.upsert(aotDir, b.build());
    }

    // ---- small helpers ----------------------------------------------------------------------

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(PluginAot::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
