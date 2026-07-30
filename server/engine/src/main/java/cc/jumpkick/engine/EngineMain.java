// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.model.JkVersion;

/**
 * Engine JVM entrypoint ({@code :engine}). Plain Java — never a native image. Spawned by the slim
 * client as {@code java -cp jk-engine.jar cc.jumpkick.engine.EngineMain}. Not a client: no CLI
 * command tree, no TUI.
 */
public final class EngineMain {

    private EngineMain() {}

    /**
     * {@code args} other than known role flags are deliberately ignored: on the {@code java -cp}
     * spawn line every option is a real JVM flag consumed before {@code main()} runs, and a {@code
     * JK_ENGINE_EXE} wrapper that fails to consume the {@code -Xms}/{@code -Xmx} the spawner
     * appends leaves them harmlessly inert here — better an unsized engine than a dead one.
     */
    public static void main(String[] args) {
        // Relocate the fetched-artifact set out of cache/ if this is the first run since the split
        // (JK-1289). A directory rename, so ~1.6 GB moves in milliseconds; never throws, and anything
        // left behind is still found via StoreMigration.resolveForRead.
        cc.jumpkick.util.StoreMigration.migrateIfNeeded();
        // --job: one-shot child — serve exactly one request over stdio, then exit.
        if (args.length > 0 && "--job".equals(args[0])) {
            System.exit(runJob());
        }
        // --aot-training: the sidecar trainer (docs/architecture.md) — an isolated, self-terminating
        // engine run whose only purpose is recording an AOT cache. Spawned BY the main engine,
        // never by hand; binds only throwaway paths under a private temp dir.
        if (args.length > 0 && "--aot-training".equals(args[0])) {
            System.exit(runAotTraining());
        }
        System.exit(run());
    }

    /** One request over stdin/stdout; engine-lifecycle logging stays on stderr. */
    static int runJob() {
        try {
            EngineServer server = new EngineServer(
                    EnginePaths.current(),
                    cc.jumpkick.config.JkEngineConfig.resolve(),
                    null,
                    JkVersion.VERSION,
                    System.err::println);
            server.serveJob(
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)),
                    new java.io.BufferedWriter(
                            new java.io.OutputStreamWriter(System.out, java.nio.charset.StandardCharsets.UTF_8)));
            return 0;
        } catch (RuntimeException e) {
            System.err.println("jk engine (job): " + e.getMessage());
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
        // terminal-generated SIGINT/SIGHUP. Cancelling a build is BUILD_CANCEL on the wire.
        PosixDetach.intoOwnSession();
        TerminalSignals.ignoreInterruptAndHangup();
        try {
            EnginePaths.Paths paths = EnginePaths.current();
            cc.jumpkick.config.JkEngineConfig config = cc.jumpkick.config.JkEngineConfig.resolve();
            cc.jumpkick.config.JkHttpConfig httpConfig =
                    cc.jumpkick.config.JkHttpConfig.resolve().orElse(null);
            EngineServer server = new EngineServer(paths, config, httpConfig, JkVersion.VERSION, System.err::println);
            // The spawner asks for an AOT cache with -Djk.aot.train.output=<path> when none exists
            // yet (see EngineClient.spawn). The server invokes the factory only after WINNING its
            // election — a losing redundant spawn never trains — and owns the child end-to-end.
            String aotOut = System.getProperty("jk.aot.train.output");
            if (aotOut != null && !aotOut.isBlank() && !java.nio.file.Files.exists(java.nio.file.Path.of(aotOut))) {
                server.aotTrainerSpawner(() -> spawnAotTrainer(aotOut));
            }
            server.run();
            return 0;
        } catch (java.io.IOException e) {
            System.err.println("jk engine: failed to start: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Launch the sidecar AOT trainer: this engine's own JVM and classpath, re-entered at {@code
     * --aot-training}, with {@code -XX:AOTCacheOutput} so the recording assembles at its clean
     * exit. {@code -XX:+UseSerialGC} matches the serving spawn line (EngineClient.spawn) — the
     * assembled cache must be recorded under the same GC it will later be mapped under.
     */
    private static Process spawnAotTrainer(String aotOut) {
        try {
            String javaExe = ProcessHandle.current().info().command().orElseGet(() -> java.nio.file.Path.of(
                            System.getProperty("java.home"), "bin", "java")
                    .toString());
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe,
                    "-XX:+UseSerialGC",
                    "-XX:AOTCacheOutput=" + aotOut,
                    "-cp",
                    System.getProperty("java.class.path"),
                    EngineMain.class.getName(),
                    "--aot-training");
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            return pb.start();
        } catch (java.io.IOException e) {
            System.err.println("jk engine: could not spawn the AOT training sidecar: " + e.getMessage());
            return null;
        }
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
    static int runAotTraining() {
        java.nio.file.Path tmp = null;
        try {
            tmp = java.nio.file.Files.createTempDirectory("jk-aot-train-");
            EnginePaths.Paths paths = EnginePaths.resolve(tmp);
            EngineServer server = new EngineServer(
                    paths, cc.jumpkick.config.JkEngineConfig.resolve(), null, JkVersion.VERSION, System.err::println);
            Thread serving = new Thread(
                    () -> {
                        try {
                            server.run();
                        } catch (java.io.IOException e) {
                            System.err.println("jk engine (aot-training): " + e.getMessage());
                        }
                    },
                    "jk-aot-training");
            serving.start();
            java.nio.file.Path endpoint = EnginePaths.endpoint(paths);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
            while (!java.nio.file.Files.exists(endpoint) && serving.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            Thread.sleep(AOT_TRAINING_UPTIME_MS);
            server.close();
            serving.join(java.time.Duration.ofSeconds(30).toMillis());
            return 0;
        } catch (Exception e) {
            System.err.println("jk engine (aot-training): " + e.getMessage());
            return 1;
        } finally {
            if (tmp != null) deleteRecursively(tmp);
        }
    }

    private static void deleteRecursively(java.nio.file.Path root) {
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (java.io.IOException ignored) {
            // best-effort cleanup
        }
    }
}
