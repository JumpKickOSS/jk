// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.compile.JavacTrainingSources;
import cc.jumpkick.config.EngineAotCache;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
 * leaves with the job, and whenever {@code JK_ENGINE_AOT} is off. One attempt per engine process.
 */
final class EngineAotTrainer {

    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();

    /** Ceiling for each CLI step; the whole recording is a few seconds of builds plus assembly. */
    private static final long STEP_TIMEOUT_SECONDS = 120;

    /** How long the trainer's exit may take to assemble the cache after {@code engine stop}. */
    private static final long ASSEMBLY_TIMEOUT_MILLIS = 120_000;

    private EngineAotTrainer() {}

    /** Start the recording when this install has none and the engine is idle; returns at once. */
    static void maybeTrain(BooleanSupplier idle, Consumer<String> log) {
        if (!EngineAotCache.enabled(System::getenv) || EnvValues.isCi(System::getenv)) return;
        String own = System.getenv(EngineAotCache.RECORD_ENV);
        if (own != null && !own.isBlank()) return;
        Path cli = JkDirs.binDir().resolve("jk");
        Path jar = Path.of(System.getProperty("java.class.path", ""));
        Path javaHome = JavaHomes.runningJavaHome();
        if (javaHome == null || !Files.isRegularFile(cli) || !Files.isRegularFile(jar)) return;
        Path cache = EngineAotCache.cacheFile(JkDirs.state(), javaHome, jar);
        if (cache == null || Files.isRegularFile(cache) || !idle.getAsBoolean()) return;
        if (!ATTEMPTED.compareAndSet(false, true)) return;
        // Idle-time recording beside the resident engine; reads no session.
        Thread.ofVirtual().name("jk-engine-aot-train").start(() -> train(cli, cache, log));
    }

    private static void train(Path cli, Path cache, Consumer<String> log) {
        long start = Clock.SYSTEM.millis();
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory(Files.createDirectories(JkDirs.tmp()), "engine-aot-train-");
            Path project = fixture(scratch.resolve("project"));
            Path recorded = scratch.resolve("engine.aot");
            Map<String, String> env = env(scratch, recorded);
            Path main = project.resolve("src/main/java/demo/train/Main.java");
            boolean ok = run(cli, project, env, "build", "--skip-tests", "--no-ansi");
            if (ok) {
                Files.writeString(main, Files.readString(main) + "\n// edited\n", StandardCharsets.UTF_8);
                ok = run(cli, project, env, "build", "--skip-tests", "--no-ansi")
                        && run(cli, project, env, "build", "--skip-tests", "--no-ansi")
                        && run(cli, project, env, "status", "--no-ansi");
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
            PathUtil.deleteRecursively(scratch);
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
     * for itself when idle (HTTP, worker caches, warmups, a lingering compiler).
     */
    static Map<String, String> env(Path scratch, Path recorded) {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.put("JK_STATE_DIR", scratch.resolve("state").toString());
        env.put("JK_CACHE_DIR", scratch.resolve("cache").toString());
        env.put("JK_STORE_DIR", JkDirs.store().toString());
        env.put(EngineAotCache.RECORD_ENV, recorded.toString());
        env.put("JK_HTTP_ENABLED", "false");
        env.put("JK_WORKER_AOT", "off");
        env.put("JK_AUTO_WARMUP", "off");
        env.put("JK_COMPILE_LINGER_MS", "0");
        return env;
    }

    private static boolean run(Path cli, Path dir, Map<String, String> env, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(cli.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.environment().clear();
        pb.environment().putAll(env);
        Process p = pb.start();
        if (!p.waitFor(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return false;
        }
        return p.exitValue() == 0;
    }

    /** The trainer's exit assembles the cache; wait for a non-empty file whose size has settled. */
    private static boolean awaitAssembled(Path recorded) throws IOException, InterruptedException {
        long deadline = Clock.SYSTEM.millis() + ASSEMBLY_TIMEOUT_MILLIS;
        long last = -1;
        while (Clock.SYSTEM.millis() < deadline) {
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
