// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.EnsureFreshLock;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.SessionMirrorListener;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.lock.JdkPin;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk sync} — align local toolchain + dependency cache with {@code jk-lock.toml}. CAS/auto-lock
 * are engine-hosted; JDK installs stay client-side (engine only resolves installed JDKs).
 */
public final class SyncCommand implements CliCommand {

    private @Nullable Path cacheDir;
    private @Nullable Path jdksDir;
    private @Nullable URI repoUrl;
    private boolean offlinePrepare;
    private boolean sources;
    private @Nullable GlobalOptions global;

    @Override
    public String name() {
        return "sync";
    }

    @Override
    public String description() {
        return "Ensure our local cache has all project dependencies";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                CommonOpts.cacheDir(),
                CommonOpts.jdksDir(),
                Opt.value("<url>", "Override declared repos with a single URL (for tests).", "--repo-url")
                        .hide(),
                Opt.flag("Prepare for an offline build.", "--offline-prepare"),
                Opt.flag("Also download sources JARs when available.", "--sources"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        this.jdksDir = CommonOpts.jdksDirValue(in);
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.offlinePrepare = in.isSet("offline-prepare");
        this.sources = in.isSet("sources");
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        String targetLabel = dir.getFileName() != null ? dir.getFileName().toString() : dir.toString();
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);

        // Sync materializes the lock — freshen first so users never hand-run `jk lock`.
        int lockCode = EnsureFreshLock.ensure(dir, cache, global, "Sync", repoUrl);
        if (lockCode != 0) return lockCode;

        // Pre-flight the JDK ensure client-side: a missing pinned JDK is downloaded HERE, before
        // the request — never silently inside the engine (docs/architecture.md keeps installs, and any
        // interactive consent, client-side). The engine's own ensure-jdk step then only resolves
        // the already-installed JDK (JdkEnsure with allowInstall=false). Thin client: the three
        // values JdkEnsure needs (project jdk spec, java floor, lock pin) ride the ProjectInfo
        // summary rather than a client-side parse.
        var info = ProjectInfos.orNull(dir);
        try {
            JdkEnsure.ensure(
                    dir,
                    jdksDir,
                    info == null ? null : info.jdk(),
                    info == null ? 0 : info.javaRelease(),
                    lockJdkPin(dir),
                    m -> CommandWedge.printFail("Sync", m),
                    true);
        } catch (Exception e) {
            CommandWedge.printFail(
                    "Sync", (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return 1;
        }

        // The sync's run record carries a details.jsonl like a build's: the transcript binds to the
        // engine job when its job-start arrives and mirrors the plan events the listener sees.
        CliSessionTranscript transcript = CliSessionTranscript.open(dir, "sync", syncArgv());
        int code = runHosted(dir, cache, mode, targetLabel);
        return CliSessionTranscript.finish(transcript, code, global.verbose);
    }

    /** Compact argv snapshot for details.jsonl. */
    private List<String> syncArgv() {
        List<String> argv = new ArrayList<>();
        argv.add("sync");
        if (offlinePrepare) argv.add("--offline-prepare");
        if (sources) argv.add("--sources");
        return argv;
    }

    private int runHosted(Path dir, Path cache, BuildPlanConsole.Mode mode, String targetLabel) {
        // Summary counts arrive on the terminal plan-finish, before the console listener's own
        // planFinish renders the line — so these holders are settled exactly like the in-process
        // path's counters.
        long[] fetched = new long[1];
        long[] upToDate = new long[1];
        ConsoleSpec spec = syncSpec(() -> fetched[0], () -> upToDate[0]);

        var session = SessionContext.current();
        BuildPlanResult result;
        try {
            result = EngineClient.runSync(
                    EnginePaths.current(),
                    new EngineRequests.SyncRequest(
                            dir,
                            cache,
                            jdksDir,
                            repoUrl,
                            sources,
                            session.offline(),
                            session.force(),
                            session.config().forceOr(false),
                            Objects.requireNonNull(global).verbose),
                    steps -> SessionMirrorListener.mirrored(
                            BuildPlanConsole.chooseConsoleListener(steps, mode, spec, targetLabel), mode),
                    fetched,
                    upToDate);
        } catch (IOException e) {
            CommandWedge.printFail("Sync", e.getMessage());
            return Exit.SOFTWARE;
        }
        // The engine ran the opportunistic cache prune on success (it did the work); nothing more
        // to do here. The progress-bar listener has already surfaced any failure.
        return result.success() ? 0 : 1;
    }

    /** The Sync chip spec; counts are read lazily, at result-line render time. */
    static ConsoleSpec syncSpec(LongSupplier fetched, LongSupplier upToDate) {
        return new ConsoleSpec(
                "Sync",
                r -> {
                    long f = fetched.getAsLong();
                    long u = upToDate.getAsLong();
                    return f == 0 && u == 0 ? "already up to date" : f + " fetched, " + u + " up-to-date";
                },
                r -> "Failed to sync dependencies.",
                true);
    }

    /**
     * Workspace lock {@code [jdk]} pin, or null when there is no lock / no pin. A lock that exists
     * but fails to parse propagates: the pin is a floor sync must enforce (docs/user/lockfile.md),
     * so a corrupt lock has to fail the command, not silently drop the pin. Only an unreadable
     * file degrades to null — the freshen step just rewrote the lock, so IO here is transient.
     */
    private static @Nullable JdkPin lockJdkPin(Path dir) {
        Path lf = LockPaths.lockFile(dir);
        if (!Files.isRegularFile(lf)) return null;
        try {
            return LockfileReader.read(lf).jdk();
        } catch (IOException e) {
            return null;
        }
    }
}
