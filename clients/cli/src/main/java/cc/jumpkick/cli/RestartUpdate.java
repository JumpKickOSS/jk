// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.PathClients;
import cc.jumpkick.cache.PreparedUpdate;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.engine.InstallLock;
import cc.jumpkick.cli.engine.ReleasedClient;
import cc.jumpkick.cli.tui.StartupPulse;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.SelfUpdatePolicy;
import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.version.Versions;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The engine restart as a moment to update: a command that finds no engine of this home running,
 * no other command holding the install, and a downloaded release newer than this client installs
 * it before an engine starts. Nothing can be cut off then. The entry point then hands the command
 * to the installed client ({@link SupersededClient}), which starts the new engine.
 *
 * <p>The same rules as the engine's own update hold: updating on, not CI (a run never replaces its
 * own jk), and only a download whose blobs are still in the store, so a command never waits on the
 * network. Protocol servers and jk's own management commands are left alone.
 */
final class RestartUpdate {

    /** Commands that never install first: jk managing itself, shell hooks, and protocol servers. */
    static final Set<String> SKIPPED_COMMANDS =
            Set.of("self", "engine", "mcp", "lsp", "bsp", "completion", "shell", "hook-env", "activate", "deactivate");

    /** What the home looks like at a command's start; a seam for tests. */
    interface Home {
        /** Whether this process is a released client (never a checkout or a test JVM). */
        boolean releasedClient();

        /** Whether the update check runs ({@code [updates] check}). */
        boolean enabled();

        boolean ci();

        /** Whether this process may start an engine at all. */
        boolean spawnAllowed();

        SelfUpdatePolicy.Action action();

        Optional<PreparedUpdate> prepared();

        /** The version the install pointer names. */
        Optional<String> installed();

        /** No engine of this home runs and no command holds or waits for the install. */
        boolean quiet();

        /** Both of the download's blobs are still in the store. */
        boolean intact(PreparedUpdate prepared);
    }

    /** Ceiling for the install; the blobs are local, so it only waits on a stuck process. */
    static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(2);

    private RestartUpdate() {}

    /**
     * Install the downloaded release when this command is the next engine start; true when it did,
     * and the installed client is then the one to run the command. Never throws.
     */
    static boolean applyIfDue(String[] args) {
        if (System.getenv(SupersededClient.ENV) != null) return false;
        try {
            Optional<String> due = due(JkVersion.VERSION, Jk.commandName(args), new LiveHome());
            if (due.isEmpty()) return false;
            StartupPulse.phase("Updating JumpKick to " + due.get());
            boolean ok = install(due.get());
            StartupPulse.release();
            return ok;
        } catch (RuntimeException e) {
            Log.debug("RestartUpdate: skipped", e);
            return false;
        }
    }

    /** The downloaded release to install before {@code command} starts an engine, if any. */
    static Optional<String> due(String running, @Nullable String command, Home home) {
        if (command == null || SKIPPED_COMMANDS.contains(command)) return Optional.empty();
        if (Versions.isSnapshot(running) || !home.releasedClient()) return Optional.empty();
        Optional<PreparedUpdate> prepared = home.prepared().filter(p -> p.newerThan(running));
        if (prepared.isEmpty()) return Optional.empty();
        // A newer install already exists: the entry point hands the command to it as it is.
        if (home.installed().filter(v -> Versions.compare(v, running) > 0).isPresent()) return Optional.empty();
        if (!home.enabled() || home.ci() || !home.spawnAllowed()) return Optional.empty();
        if (home.action() != SelfUpdatePolicy.Action.AUTO) return Optional.empty();
        if (!home.quiet() || !home.intact(prepared.get())) return Optional.empty();
        return Optional.of(prepared.get().version());
    }

    /**
     * {@code jk self update <version>} on the PATH client, its output appended to the update log so
     * this command's own output stays clean; true when it installed.
     */
    private static boolean install(String version) {
        Path jk = PathClients.pathClient(JkDirs.binDir());
        if (!Files.isRegularFile(jk)) return false;
        Path log = JkDirs.state().resolve("self-update.log");
        try {
            Files.createDirectories(log.getParent());
            ProcessBuilder pb = new ProcessBuilder(jk.toString(), "self", "update", version)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .redirectErrorStream(true);
            pb.environment().put("JK_NONINTERACTIVE", "1");
            Process p = pb.start();
            p.getOutputStream().close();
            if (!p.waitFor(INSTALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                Log.debug("RestartUpdate: jk self update " + version + " timed out");
                return false;
            }
            if (p.exitValue() == 0) return true;
            Log.debug("RestartUpdate: jk self update " + version + " exited " + p.exitValue());
            return false;
        } catch (IOException e) {
            Log.debug("RestartUpdate: jk self update " + version + " did not start", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The live home, read once per command. */
    static final class LiveHome implements Home {

        @Override
        public boolean releasedClient() {
            return ReleasedClient.current();
        }

        @Override
        public boolean enabled() {
            return GlobalConfig.updatesCheck();
        }

        @Override
        public boolean ci() {
            return EnvValues.isCi(ProcessEnv::get);
        }

        @Override
        public boolean spawnAllowed() {
            return !EnvValues.parseBool(ProcessEnv.get(EnginePaths.NO_SPAWN_ENV))
                    .orElse(false);
        }

        @Override
        public SelfUpdatePolicy.Action action() {
            SelfUpdateState state = SelfUpdateState.read();
            return SelfUpdatePolicy.decide(
                            JkVersion.VERSION,
                            ProcessEnv.get(SelfUpdatePolicy.ENV),
                            GlobalConfig.selfUpdate().orElse(null),
                            state,
                            SelfUpdatePolicy.attended(state, Clock.SYSTEM.instant(), ci()))
                    .action();
        }

        @Override
        public Optional<PreparedUpdate> prepared() {
            return PreparedUpdate.read(EngineInstall.current().engineHome());
        }

        @Override
        public Optional<String> installed() {
            return EngineInstall.current().currentInstall().map(EngineInstall.Materialized::version);
        }

        @Override
        public boolean quiet() {
            return InstallLock.holders().isEmpty() && EngineFleet.listThisHome().isEmpty();
        }

        @Override
        public boolean intact(PreparedUpdate prepared) {
            return prepared.blobsIntact(JkStores.storeCas());
        }
    }
}
