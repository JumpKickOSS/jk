// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.engine.plugin.BuiltInPluginJars;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.ShadowManifests;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Path;
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
        installLogging(JkEngineConfig.resolve());
        // IPv4-only sockets (WSL localhost forwarding) ride the spawn line as
        // EngineJvmFlags.AOT_SENSITIVE — never a runtime setProperty, which leaves the JDK's
        // loopback selection incoherent (PreferIpv4). A JK_ENGINE_EXE wrapper passes it itself.
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
            Log.error("usage: EngineMain --inflate-xz <in.xz> <out>");
            return Exit.USAGE;
        }
        try {
            Xz.inflate(Path.of(args[1]), Path.of(args[2]));
            return 0;
        } catch (IOException e) {
            Log.error("jk engine (inflate-xz): " + e.getMessage());
            return 1;
        }
    }

    /**
     * The engine server's whole life: resolve identity/config from the same env this process
     * inherited from its spawner, serve until shutdown, then return. All engine-lifecycle logging
     * goes through {@link Log} onto {@code System.err} — the spawner already redirected this
     * process's stdout/stderr to the engine's log file, so nothing here writes to a real terminal.
     */
    public static int run() {
        // Survive the spawner's terminal: detach into our own POSIX session, then take over
        // SIGINT/SIGHUP — before anything is forked, so children start with default dispositions.
        // Cancelling a build is CANCEL_REQUEST on the wire.
        PosixDetach.intoOwnSession();
        TerminalSignals.install();
        try {
            JkDirs.current().secureRoots();
            EnginePaths.Paths paths = EnginePaths.current();
            JkEngineConfig config = JkEngineConfig.resolve();
            EngineLogSink logSink = installLogSink(paths.log(), config);
            installLogging(config); // re-bind: System.err is now the capped sink
            BuiltInPluginJars.registerMissingBuiltInFetcher();
            BuiltInPluginJars.install();
            ShadowManifests.install();
            try {
                BuiltInPluginJars.installUserConfig();
            } catch (RuntimeException badConfig) {
                // A user-config plugin pin (or config parse) error is explicit user intent we
                // cannot honor — refuse to start with the message, never a raw stack.
                Log.error("jk engine: " + badConfig.getMessage());
                return 1;
            }
            JkHttpConfig httpConfig = JkHttpConfig.resolve().orElse(null);
            EngineServer server = new EngineServer(paths, config, httpConfig, JkVersion.VERSION, Log::info);
            if (logSink != null) server.logSink(logSink);
            OwnerWatchdog.start(System.getProperty(OwnerWatchdog.PROPERTY), server::close, Log::info);
            server.run();
            return 0;
        } catch (IOException e) {
            Log.error("jk engine: failed to start: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Bind {@link Log} to this process's current {@code System.err} at the configured threshold,
     * redacting every secret the process has been told about. Called once at entry so every role
     * logs in one shape, and again in {@link #run} once the capped sink owns the stream.
     */
    private static void installLogging(JkEngineConfig config) {
        Log.install(config.logThreshold(), SecretRedactor::redactKnown);
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
            Log.warn("jk engine: log size cap is off — could not open " + log + ": " + e.getMessage());
            return null;
        }
    }
}
