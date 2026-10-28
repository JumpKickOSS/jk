// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.compile.JavacTrainingSources;
import cc.jumpkick.config.EngineAotCache;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.engine.plugin.WarmLinger;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Records the engine's {@link EngineAotCache} once per install, from an idle engine.
 *
 * <p>The JVM records a cache over a run and assembles it when that run exits, and a resident
 * engine runs until it is replaced, by which time its cache is for the wrong jar. So the recording
 * is a trainer engine of its own: this engine runs the jk CLI beside it against a scratch state and
 * cache, with {@link EngineAotCache#RECORD_ENV} set so the spawner starts the trainer recording.
 * The CLI builds a small Java module ({@link JavacTrainingSources}), edits it and builds again,
 * builds once more as a no-op, asks for status and stops the trainer, whose exit assembles the
 * cache. The store is shared, so nothing is downloaded twice. Skipped under CI, where the engine
 * leaves with the job, and whenever {@code JK_ENGINE_AOT} is off.
 *
 * <p>The recording competes with the user's builds for the CPU, so it stands down the moment a job
 * arrives: the step in flight is killed, the trainer engine stopped, and the next settled idle
 * tries again. A recording that fails for any other reason is not retried by this engine.
 *
 * <p>The trainer engine never outlives this one: it is started owned by this engine's pid
 * ({@link OwnerWatchdog#ENV}), and this engine's shutdown {@linkplain #stop stops} a recording in
 * flight, which stops the trainer and removes its scratch.
 */
final class EngineAotTrainer {

    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();

    /** The recording in flight, or null. */
    private static volatile @Nullable Thread recording;

    /** Set by {@link #stop}: the recording stands down as it does for a job, and none starts again. */
    private static volatile boolean stopping;

    /** How often a step in flight checks that the engine is still idle. */
    private static final long IDLE_POLL_MILLIS = 100;

    /** Ceiling for each CLI step; the whole recording is a few seconds of builds plus assembly. */
    private static final long STEP_TIMEOUT_SECONDS = 120;

    /** How long the trainer's exit may take to assemble the cache after {@code engine stop}. */
    private static final long ASSEMBLY_TIMEOUT_MILLIS = 120_000;

    /** How long a killed straggler may take to exit before its scratch is deleted anyway. */
    private static final long STRAGGLER_EXIT_SECONDS = 5;

    /** How long {@link #stop} waits for the recording to stop its trainer and remove its scratch. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    private EngineAotTrainer() {}

    /** Start the recording when this install has none and the engine is idle; returns at once. */
    static void maybeTrain(BooleanSupplier idle, Consumer<String> log) {
        if (stopping || !EngineAotCache.enabled(ProcessEnv::get) || EnvValues.isCi(ProcessEnv::get)) return;
        String own = ProcessEnv.get(EngineAotCache.RECORD_ENV);
        if (own != null && !own.isBlank()) return;
        Path cli = JkDirs.binDir().resolve("jk");
        Path jar = Path.of(System.getProperty("java.class.path", ""));
        Path javaHome = JavaHomes.runningJavaHome();
        if (javaHome == null || !Files.isRegularFile(cli) || !Files.isRegularFile(jar)) return;
        Path cache = EngineAotCache.cacheFile(JkDirs.state(), javaHome, jar);
        if (cache == null || Files.isRegularFile(cache) || !idle.getAsBoolean()) return;
        if (!ATTEMPTED.compareAndSet(false, true)) return;
        // Idle-time recording beside the resident engine; reads no session.
        BooleanSupplier go = () -> !stopping && idle.getAsBoolean();
        recording = Thread.ofVirtual().name("jk-engine-aot-train").start(() -> train(cli, cache, go, log));
    }

    /**
     * Stand a recording in flight down, as a job arriving does, and wait for it to stop the trainer
     * engine and remove its scratch. Called once, on this engine's shutdown; true when nothing was
     * left running.
     */
    static boolean stop() {
        stopping = true;
        Thread t = recording;
        if (t == null) return true;
        try {
            return t.join(STOP_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A job arrived while a recording step was in flight. */
    static final class Yielded extends Exception {
        Yielded() {
            super(null, null, false, false);
        }
    }

    private static void train(Path cli, Path cache, BooleanSupplier idle, Consumer<String> log) {
        long start = Clock.SYSTEM.millis();
        Path scratch = null;
        try {
            Path tmpRoot = Files.createDirectories(JkDirs.tmp());
            dropStaleScratch(tmpRoot);
            scratch = Files.createTempDirectory(tmpRoot, SCRATCH_PREFIX);
            Path project = fixture(scratch.resolve("project"));
            Path recorded = scratch.resolve("engine.aot");
            Map<String, String> env = env(scratch, recorded);
            Path main = project.resolve("src/main/java/demo/train/Main.java");
            boolean ok;
            try {
                ok = step(cli, project, env, idle, "build", "--skip-tests", "--no-ansi");
                if (ok) {
                    Files.writeString(main, Files.readString(main) + "\n// edited\n", StandardCharsets.UTF_8);
                    ok = step(cli, project, env, idle, "build", "--skip-tests", "--no-ansi")
                            && step(cli, project, env, idle, "build", "--skip-tests", "--no-ansi")
                            && step(cli, project, env, idle, "status", "--no-ansi");
                }
            } catch (Yielded y) {
                run(cli, project, env, "engine", "stop", "--now");
                ATTEMPTED.set(false);
                Log.debug("EngineAotTrainer: stood down; the next settled idle records again");
                return;
            }
            run(cli, project, env, "engine", "stop");
            if (!ok || !awaitAssembled(recorded)) {
                log.accept("jk engine: engine startup cache not recorded; the next engine starts without it");
                return;
            }
            Files.createDirectories(cache.getParent());
            Path tmp = cache.resolveSibling(cache.getFileName() + ".tmp");
            Files.copy(recorded, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dropOthers(cache);
            log.accept("jk engine: recorded engine startup cache (" + (Files.size(cache) >> 20) + " MiB, "
                    + (Clock.SYSTEM.millis() - start) / 1000 + " s); the next engine maps it");
        } catch (IOException | RuntimeException e) {
            Log.debug("EngineAotTrainer: recording failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (scratch != null) killStragglers(scratch);
            PathUtil.deleteRecursively(scratch);
            recording = null;
        }
    }

    /** A recording's scratch under {@code JkDirs.tmp()}: a killed engine's is left for the next recording. */
    private static final String SCRATCH_PREFIX = "engine-aot-train-";

    /** Remove the scratch of a recording whose engine was killed, and anything still running from it. */
    static void dropStaleScratch(Path tmp) throws IOException {
        List<Path> stale = new ArrayList<>();
        PathUtil.forEachChild(tmp, (p, attrs) -> {
            if (p.getFileName().toString().startsWith(SCRATCH_PREFIX)) stale.add(p);
            return true;
        });
        for (Path p : stale) {
            killStragglers(p);
            PathUtil.deleteRecursively(p);
        }
    }

    /**
     * Kill what still runs out of {@code scratch} before it is deleted: a trainer engine still
     * starting is not listening yet, so {@code engine stop} cannot reach it, and it would recreate
     * the scratch it was spawned into. Matched by the scratch path among its arguments, which every
     * trainer engine carries in its heap-dump path; where a platform gives no arguments, the owner
     * watch still ends it.
     */
    static void killStragglers(Path scratch) {
        String mark = scratch.toString();
        List<ProcessHandle> found = ProcessHandle.allProcesses()
                .filter(h -> h.info()
                        .arguments()
                        .map(args -> Arrays.stream(args).anyMatch(a -> a.contains(mark)))
                        .orElse(false))
                .toList();
        for (ProcessHandle h : found) h.destroyForcibly();
        for (ProcessHandle h : found) {
            try {
                h.onExit().get(STRAGGLER_EXIT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                Log.debug("EngineAotTrainer: pid " + h.pid() + " outlived its kill");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** A one-module Java project the trainer builds: the javac worker's training sources. */
    static Path fixture(Path project) throws IOException {
        Files.createDirectories(project);
        Files.writeString(project.resolve(ManifestNames.MANIFEST), """
                name     = "aot-train"
                group    = "demo.train"
                version  = "0.1.0"
                java     = 25
                """);
        JavacTrainingSources.write(project.resolve("src/main/java"));
        return project;
    }

    /**
     * The trainer's environment: this engine's, with a scratch state and cache so its builds,
     * journal and engine stay apart from the user's, the shared store, and nothing an engine does
     * for itself when idle (HTTP, worker caches, warmups, a lingering compiler), owned by this
     * engine so a kill of this one cannot leave the trainer resident.
     */
    static Map<String, String> env(Path scratch, Path recorded) {
        Map<String, String> env = new LinkedHashMap<>(ProcessEnv.all());
        env.put("JK_STATE_DIR", scratch.resolve("state").toString());
        env.put("JK_CACHE_DIR", scratch.resolve("cache").toString());
        env.put("JK_STORE_DIR", JkDirs.store().toString());
        env.put(EngineAotCache.RECORD_ENV, recorded.toString());
        env.put("JK_HTTP_ENABLED", "false");
        env.put("JK_WORKER_AOT", "off");
        env.put("JK_AUTO_WARMUP", "off");
        env.put(WarmLinger.ENV, "0");
        env.put(OwnerWatchdog.ENV, Long.toString(ProcessHandle.current().pid()));
        return env;
    }

    private static boolean run(Path cli, Path dir, Map<String, String> env, String... args)
            throws IOException, InterruptedException {
        Process p = start(cli, dir, env, args);
        if (!p.waitFor(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return false;
        }
        return p.exitValue() == 0;
    }

    /** As {@link #run}, killed the moment {@code idle} turns false. */
    static boolean step(Path cli, Path dir, Map<String, String> env, BooleanSupplier idle, String... args)
            throws IOException, InterruptedException, Yielded {
        if (!idle.getAsBoolean()) throw new Yielded();
        Process p = start(cli, dir, env, args);
        long deadline = Clock.SYSTEM.millis() + TimeUnit.SECONDS.toMillis(STEP_TIMEOUT_SECONDS);
        while (!p.waitFor(IDLE_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            if (!idle.getAsBoolean()) {
                p.destroyForcibly();
                throw new Yielded();
            }
            if (Clock.SYSTEM.millis() > deadline) {
                p.destroyForcibly();
                return false;
            }
        }
        return p.exitValue() == 0;
    }

    private static Process start(Path cli, Path dir, Map<String, String> env, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of(cli.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.environment().clear();
        pb.environment().putAll(env);
        return pb.start();
    }

    /** The trainer's exit assembles the cache; wait for a non-empty file whose size has settled. */
    private static boolean awaitAssembled(Path recorded) throws IOException, InterruptedException {
        long deadline = Clock.SYSTEM.millis() + ASSEMBLY_TIMEOUT_MILLIS;
        long last = -1;
        while (!stopping && Clock.SYSTEM.millis() < deadline) {
            long size = Files.isRegularFile(recorded) ? Files.size(recorded) : -1;
            if (size > 0 && size == last) return true;
            last = size;
            Thread.sleep(500);
        }
        return false;
    }

    /** One cache per install: the others are for jars or JDKs this install no longer runs. */
    private static void dropOthers(Path keep) throws IOException {
        List<Path> others = new ArrayList<>();
        PathUtil.forEachChild(Objects.requireNonNull(keep.getParent(), "keep"), (p, attrs) -> {
            if (!p.equals(keep) && p.getFileName().toString().startsWith("engine-")) others.add(p);
            return true;
        });
        for (Path p : others) Files.deleteIfExists(p);
    }
}
