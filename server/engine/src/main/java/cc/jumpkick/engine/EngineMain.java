// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.plugin.BuiltInPluginJars;
import cc.jumpkick.host.AotCacheFiles;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Engine JVM entrypoint ({@code :engine}). Plain Java — never a native image. Spawned by the slim
 * client as {@code java -cp lib/jk-engine/<jar> cc.jumpkick.engine.EngineMain}. Not a client: no CLI
 * command tree, no TUI.
 */
public final class EngineMain {

    private EngineMain() {}

    /**
     * {@code args} other than known role flags are deliberately ignored: on the {@code java -cp}
     * spawn line every option is a real JVM flag consumed before {@code main} runs, and a {@code
     * JK_ENGINE_EXE} wrapper that fails to consume the {@code -Xms}/{@code -Xmx} the spawner
     * appends leaves them harmlessly inert here — better an unsized engine than a dead one.
     */
    public static void main(String[] args) {
        // IPv4-only sockets (WSL localhost forwarding) ride the spawn line as
        // EngineJvmFlags.AOT_SENSITIVE — never a runtime setProperty, which leaves the JDK's
        // loopback selection incoherent (PreferIpv4). A JK_ENGINE_EXE wrapper passes it itself.
        // --aot-training: the sidecar trainer (docs/architecture.md) — an isolated, self-terminating
        // engine run whose only purpose is recording an AOT cache. Spawned BY the main engine,
        // never by hand; binds only throwaway paths under a private temp dir.
        if (args.length > 0 && "--aot-training".equals(args[0])) {
            System.exit(runAotTraining());
        }
        // --inflate-xz: one-shot, no daemon. The native CLI shells this out so tukaani
        // stays out of the Graal image. In and out are filesystem paths (a native
        // client is several MiB; this is not a stdio job).
        if (args.length > 0 && "--inflate-xz".equals(args[0])) {
            System.exit(runInflateXz(args));
        }
        System.exit(run());
    }

    /**
     * {@code EngineMain --inflate-xz <in.xz> <out>} — inflate a release client {@code .xz} to
     * the raw binary. Returns {@link Exit#USAGE} on a bad command line, 1 on inflate failure, 0 on
     * success.
     */
    static int runInflateXz(String[] args) {
        if (args.length != 3) {
            System.err.println("usage: EngineMain --inflate-xz <in.xz> <out>");
            return Exit.USAGE;
        }
        try {
            Xz.inflate(Path.of(args[1]), Path.of(args[2]));
            return 0;
        } catch (IOException e) {
            System.err.println("jk engine (inflate-xz): " + e.getMessage());
            return 1;
        }
    }

    /**
     * The engine server's whole life: resolve identity/config from the same env this process
     * inherited from its spawner, serve until shutdown, then return. All engine-lifecycle logging
     * goes to {@code System.err} — the spawner already redirected this process's stdout/stderr to
     * the engine's log file, so nothing here writes to a real terminal.
     */
    public static int run() {
        // Survive the spawner's terminal: detach into our own POSIX session, then ignore
        // terminal-generated SIGINT/SIGHUP. Cancelling a build is CANCEL_REQUEST on the wire.
        PosixDetach.intoOwnSession();
        TerminalSignals.ignoreInterruptAndHangup();
        try {
            JkDirs.current().secureRoots();
            EnginePaths.Paths paths = EnginePaths.current();
            JkEngineConfig config = JkEngineConfig.resolve();
            installLogSink(paths.log(), config);
            BuiltInPluginJars.registerMissingBuiltInFetcher();
            BuiltInPluginJars.install();
            try {
                BuiltInPluginJars.installUserConfig();
            } catch (RuntimeException badConfig) {
                // A user-config plugin pin (or config parse) error is explicit user intent we
                // cannot honor — refuse to start with the message, never a raw stack.
                System.err.println("jk engine: " + badConfig.getMessage());
                return 1;
            }
            JkHttpConfig httpConfig = JkHttpConfig.resolve().orElse(null);
            EngineServer server = new EngineServer(paths, config, httpConfig, JkVersion.VERSION, System.err::println);
            // The spawner asks for an AOT cache with -Djk.aot.train.output=<path> when none exists
            // yet (see EngineClient.spawn). The server invokes the factory only after WINNING its
            // election — a losing redundant spawn never trains — and owns the child end-to-end.
            String aotOut = System.getProperty("jk.aot.train.output");
            if (aotOut != null && !aotOut.isBlank() && !Files.exists(Path.of(aotOut))) {
                server.aotTrainerSpawner(() -> spawnAotTrainer(aotOut));
            }
            OwnerWatchdog.start(System.getProperty(OwnerWatchdog.PROPERTY), server::close, System.err::println);
            server.run();
            return 0;
        } catch (IOException e) {
            System.err.println("jk engine: failed to start: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Route this process's stderr/stdout through the size-capped {@link EngineLogSink} on the
     * engine log. When the log cannot be opened the inherited streams stay — the spawner's
     * redirect still reaches the same file, only uncapped — and the reason is the first line
     * written there.
     */
    private static @Nullable EngineLogSink installLogSink(Path log, JkEngineConfig config) {
        try {
            return EngineLogSink.install(log, config.logMaxBytes());
        } catch (IOException e) {
            System.err.println("jk engine: log size cap is off — could not open " + log + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Launch the sidecar AOT trainer: this engine's own JVM and classpath, re-entered at {@code
     * --aot-training}, with {@code -XX:AOTCacheOutput} so the recording assembles at its clean
     * exit. It runs under this JVM's own flags, because the cache is mapped only under the flag set
     * it was recorded under.
     *
     * <p>The trainer assembles to a <em>temp sibling</em>, promoted to the final path only on a
     * clean exit ({@link #promoteTrainedCache}). The watchdog kills an overrunning trainer with
     * {@link Runtime#halt}, so writing {@code -XX:AOTCacheOutput} straight to the final path could
     * leave a partial/zero-byte file there — which the client would then map forever.
     */
    private static @Nullable Process spawnAotTrainer(String aotOut) {
        try {
            Path finalPath = Path.of(aotOut);
            Path tmp = trainerTmpPath(finalPath);
            cleanStaleTrainerTmps(finalPath);
            String javaExe = ProcessHandle.current().info().command().orElseGet(() -> JdkFingerprint.java(
                            JavaHomes.runningJavaHome())
                    .toString());
            ProcessBuilder pb = new ProcessBuilder(aotTrainerCommand(
                    javaExe,
                    ManagementFactory.getRuntimeMXBean().getInputArguments(),
                    System.getProperty("java.class.path"),
                    tmp));
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process p = pb.start();
            p.onExit().thenAccept(proc -> promoteTrainedCache(tmp, finalPath, proc.exitValue()));
            return p;
        } catch (IOException e) {
            System.err.println("jk engine: could not spawn the AOT training sidecar: " + e.getMessage());
            return null;
        }
    }

    /** The trainer's private assembly target: a same-directory sibling, atomically movable. */
    static Path trainerTmpPath(Path finalPath) {
        return AotCacheFiles.tmpFor(finalPath, ProcessHandle.current().pid());
    }

    /**
     * The trainer's command line: this JVM's own flags — heap, metaspace, GC, everything the
     * spawner chose — minus any AOT flag, plus {@code -XX:AOTCacheOutput}. A cache is mapped only
     * under the flag set it was recorded under, and the heap is part of that set, so the trainer
     * copies the serving line rather than keeping a second list of it.
     */
    static List<String> aotTrainerCommand(String javaExe, List<String> servingJvmArgs, String classpath, Path tmpOut) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.addAll(trainerJvmArgs(servingJvmArgs));
        cmd.add("-XX:AOTCacheOutput=" + tmpOut);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(EngineMain.class.getName());
        cmd.add("--aot-training");
        return List.copyOf(cmd);
    }

    /** The serving JVM's arguments with the AOT flags and the training switch left out. */
    static List<String> trainerJvmArgs(List<String> servingJvmArgs) {
        List<String> out = new ArrayList<>();
        for (String arg : servingJvmArgs) {
            if (arg.startsWith("-XX:AOTCache")
                    || arg.startsWith("-XX:AOTMode")
                    || arg.startsWith("-Djk.aot.train.output=")) continue;
            out.add(arg);
        }
        return out;
    }

    /**
     * Publish a finished recording: a clean exit with a non-empty assembly is atomically moved to
     * the final path; anything else (nonzero exit, watchdog halt, empty file) is discarded — the
     * final path either holds a complete cache or nothing.
     */
    static void promoteTrainedCache(Path tmp, Path finalPath, int exit) {
        try {
            if (exit == 0 && Files.isRegularFile(tmp) && Files.size(tmp) > 0) {
                AtomicWrites.moveInto(tmp, finalPath);
                return;
            }
        } catch (IOException e) {
            System.err.println("jk engine: could not publish the AOT cache: " + e.getMessage());
        }
        deleteQuietly(tmp);
        deleteQuietly(AotCacheFiles.configOf(tmp)); // interrupted recording
    }

    /**
     * Drop temp assemblies a dead engine left behind for this cache stem. Safe: the engine spawns
     * a trainer only after winning its election, so no live sibling shares the stem.
     */
    private static void cleanStaleTrainerTmps(Path finalPath) {
        Path dir = finalPath.getParent();
        if (dir == null) return;
        String prefix = finalPath.getFileName() + AotCacheFiles.TMP_INFIX;
        try (var entries =
                Files.newDirectoryStream(dir, p -> p.getFileName().toString().startsWith(prefix))) {
            for (Path p : entries) deleteQuietly(p);
        } catch (IOException ignored) {
            // best-effort — a leftover tmp costs disk, not correctness
        }
    }

    private static void deleteQuietly(@Nullable Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * A daemon thread that halts the JVM if the trainer outlives {@code limitMs}.
     *
     * <p>{@link Runtime#halt} rather than {@link System#exit} on purpose: exit runs shutdown hooks, and if
     * the reason the trainer is stuck is a hook or a lock, asking politely is exactly what will not work.
     * A recording that has not assembled by now is worthless anyway, so there is nothing to lose by
     * skipping the orderly path.
     *
     * <p>Daemon, so it never keeps an otherwise-finished trainer alive — the watchdog must not become the
     * thing that leaks.
     */
    private static void startTrainerWatchdog(long limitMs) {
        Thread watchdog = new Thread(
                () -> {
                    try {
                        Thread.sleep(limitMs);
                    } catch (InterruptedException e) {
                        return; // trainer finished first
                    }
                    System.err.println("jk engine (aot-training): exceeded " + (limitMs / 1000)
                            + "s — halting; a normal recording takes about "
                            + (AOT_TRAINING_UPTIME_MS / 1000) + "s");
                    // Not a cancellation and not bad config: the trainer wedged. EX_SOFTWARE.
                    Runtime.getRuntime().halt(Exit.SOFTWARE);
                },
                "jk-aot-training-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * How long the trainer serves before stopping itself. A sub-second run yields an empty
     * recording the assembler rejects; three seconds captures the whole startup path (which is
     * what the cache accelerates) without meaningfully extending the doubled-RSS window.
     */
    private static final long AOT_TRAINING_UPTIME_MS = 3_000;

    /**
     * The sidecar trainer's whole life: bring up a REAL engine server against a private temp state
     * dir (it can never win, lose, or see the real engine's election), idle briefly, then stop
     * cleanly so the JVM assembles the {@code .aot} at exit.
     */
    /**
     * Hard ceiling on a trainer's life, enforced by a watchdog rather than by the happy path.
     *
     * <p>The happy path is already bounded — {@link #AOT_TRAINING_UPTIME_MS} of serving, then close, then
     * a 30s join — so under 40s is the design. This exists because that reasoning holds only while every
     * step in it stays bounded, and a trainer is invisible: it has no endpoint pointer, no pid file, and
     * no identity, so {@code jk engine} commands cannot see or stop one. A trainer that did hang would sit
     * there holding a JVM until the machine rebooted, and the first sign of it would be someone reading
     * {@code ps} output.
     *
     * <p>Two minutes is deliberately far above the ~40s design and far below "noticeable": generous enough
     * that a genuinely slow machine never trips it, tight enough that a stuck trainer is gone before it
     * matters.
     */
    private static final long AOT_TRAINING_HARD_LIMIT_MS = Duration.ofMinutes(2).toMillis();

    static int runAotTraining() {
        startTrainerWatchdog(AOT_TRAINING_HARD_LIMIT_MS);
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("jk-aot-train-");
            EnginePaths.Paths paths = EnginePaths.resolve(tmp);
            EngineServer server =
                    new EngineServer(paths, JkEngineConfig.resolve(), null, JkVersion.VERSION, System.err::println);
            Thread serving = new Thread(
                    () -> {
                        try {
                            server.run();
                        } catch (IOException e) {
                            System.err.println("jk engine (aot-training): " + e.getMessage());
                        }
                    },
                    "jk-aot-training");
            serving.start();
            Path endpoint = EnginePaths.endpoint(paths);
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!Files.exists(endpoint) && serving.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            Thread.sleep(AOT_TRAINING_UPTIME_MS);
            server.close();
            serving.join(Duration.ofSeconds(30).toMillis());
            return 0;
        } catch (Exception e) {
            System.err.println("jk engine (aot-training): " + e.getMessage());
            return 1;
        } finally {
            if (tmp != null) PathUtil.deleteRecursively(tmp);
        }
    }
}
