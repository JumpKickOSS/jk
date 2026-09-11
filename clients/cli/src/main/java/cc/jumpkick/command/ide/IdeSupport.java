// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.EnsureFreshLock;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineProcessControl;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.SilentListener;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.ide.IdeException;
import cc.jumpkick.ide.IdeModel;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Client half of the {@code jk ide} model build. The model math — workspace + module parsing,
 * lockfile + CAS reads, cross-module edges, per-module JDK/SDK handles — runs engine-side
 * ({@code IdeOps}, thin-client contract) and ships as an {@link IdeWireModel}; this class ensures a
 * fresh lock, runs the hosted best-effort sync (silent — {@link IdeChrome} owns the chip), fetches
 * the wire model, and rebuilds the {@link IdeModel} the shared generators consume. All TTY output
 * stays here; the generators themselves live in {@code cc.jumpkick.ide}.
 */
public final class IdeSupport {

    private IdeSupport() {}

    // =========================================================================
    // Model build
    // =========================================================================

    /**
     * Resolve the full {@link IdeModel} for the workspace at the invocation's working directory.
     * Reads {@code --cache-dir}/{@code --jdks-dir}/{@code --ide-config-dir} overrides (all optional,
     * used by tests). The engine ensures each module's stable JDK pointer while computing the model,
     * so the resolved {@code JAVA_HOME} paths are valid regardless of which IDE consumes them.
     */
    public static IdeModel build(Invocation in) throws IOException {
        return build(in, null);
    }

    /**
     * Like {@link #build(Invocation)}, driving lock + sync under {@code chrome} when present so
     * {@code jk ide} keeps a single {@code IDE} wedge (no nested Sync chip).
     */
    public static IdeModel build(Invocation in, @Nullable IdeChrome chrome) throws IOException {
        Path ideConfigDir = in.value("ide-config-dir").map(Path::of).orElse(null);
        return IdeModel.fromWire(wireModel(in, chrome), ideConfigDir);
    }

    /**
     * Engine {@link IdeWireModel} after lock + hosted sync — for IDE plugins ({@code jk ide
     * --print-model}) and generators.
     */
    public static IdeWireModel wireModel(Invocation in) throws IOException {
        return wireModel(in, null);
    }

    /**
     * Engine {@link IdeWireModel} after lock + hosted sync. When {@code chrome} is non-null, lock
     * and sync stay silent (the caller already owns the {@code IDE} chip). {@code --print-model}
     * passes {@code null} and is also silent — machine stdout is the wire JSON only.
     */
    public static IdeWireModel wireModel(Invocation in, @Nullable IdeChrome chrome) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        Path jdksDir = CommonOpts.jdksDirValue(in);

        Path startDir = global.workingDir();
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        if (!Files.exists(startDir.resolve(ManifestPaths.MANIFEST))) {
            throw new IdeException(2, "no jk.toml in " + PathDisplay.styledRaw(startDir));
        }

        // Fresh lock before sync/model — IDE files must match current manifests.
        // Quiet: the IDE chip (or --print-model's raw JSON) owns the terminal. Failure already
        // printed a wedge; rethrow without a second line.
        int lockCode = EnsureFreshLock.ensureQuiet(syncRoot(startDir), cache, global, "IDE");
        if (lockCode != 0) {
            throw new IdeException(lockCode, null);
        }

        // Bring the CAS in line with the lockfiles up front (one sync-request covers the workspace
        // cascade). No Sync chip — chrome.phase("Sync") is the live label when present.
        hostedBestEffortSync(syncRoot(startDir), cache, jdksDir, global, chrome);
        IdeWireModel wire;
        try {
            wire = EngineClient.ideModel(EnginePaths.current(), startDir, cache, jdksDir);
        } catch (IOException e) {
            throw new IdeException(2, String.valueOf(e.getMessage()));
        }
        if (wire.error() != null) {
            throw new IdeException(2, wire.error());
        }
        return wire;
    }

    /**
     * The workspace root the pre-model sync should target: the enclosing workspace when {@code
     * startDir} is a module (one hosted sync covers the cascade), else {@code startDir} itself.
     * Resolved via {@code PROJECT_INFO} — no client-side parsing.
     */
    private static Path syncRoot(Path startDir) {
        try {
            var info = EngineClient.projectInfo(EnginePaths.current(), startDir);
            if (info.error() == null && !info.workspaceRootDir().isEmpty()) {
                return Path.of(info.workspaceRootDir());
            }
        } catch (Exception ignored) {
            // best-effort — sync against startDir; the model build skips whatever is missing
        }
        return startDir;
    }

    /**
     * One hosted {@code jk sync} against the workspace root. Silent on the terminal — {@code jk
     * ide}'s live {@code IDE} chip already says {@code Sync}; {@code --print-model} must not emit
     * chrome. Best-effort: any failure warns and returns; the model build skips whatever is missing.
     */
    /**
     * Hard ceiling for best-effort IDE pre-sync. A wedged engine must not block {@code jk ide} /
     * {@code jk vscode} (or the CLI test suite) for the full protocol idle timeout (default 60m).
     */
    private static final long BEST_EFFORT_SYNC_MS = 30_000L;

    private static void hostedBestEffortSync(
            Path wsRoot, Path cache, @Nullable Path jdksDir, GlobalOptions global, @Nullable IdeChrome chrome) {
        long[] fetched = new long[1];
        long[] upToDate = new long[1];
        var session = SessionContext.current();
        var paths = EnginePaths.current();
        var req = new EngineRequests.SyncRequest(
                wsRoot, cache, jdksDir, null, false, session.offline(), session.force(), false, global.verbose);
        // Time-box: best-effort must never hang the CLI. On timeout, force-stop the engine so the
        // blocked protocol read unblocks via channel close.
        AtomicReference<Exception> fail = new AtomicReference<>();
        Thread t = new Thread(
                () -> {
                    try {
                        EngineClient.runSync(
                                paths,
                                req,
                                steps -> new SilentListener(System.out, System.err, true),
                                fetched,
                                upToDate);
                    } catch (Exception e) {
                        fail.set(e);
                    }
                },
                "jk-ide-best-effort-sync");
        t.setDaemon(true);
        t.start();
        try {
            t.join(BEST_EFFORT_SYNC_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            try {
                EngineProcessControl.forceStop(EnginePaths.activeSocket(paths));
            } catch (RuntimeException ignored) {
                // best-effort
            }
            try {
                t.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            syncWarn(
                    chrome,
                    "dependency sync timed out after " + (BEST_EFFORT_SYNC_MS / 1000)
                            + "s — missing jars will be skipped");
            return;
        }
        Exception e = fail.get();
        if (e != null) {
            syncWarn(chrome, "dependency sync incomplete (" + e.getMessage() + ") — missing jars will be skipped");
        }
    }

    /** Soft sync failure: a note under the live IDE chip, or a fail wedge when there is no chrome. */
    private static void syncWarn(@Nullable IdeChrome chrome, String message) {
        if (chrome != null) {
            chrome.note(RichText.plain(message));
            return;
        }
        CommandWedge.printFail("IDE", message);
    }
}
