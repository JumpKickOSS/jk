// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk sync} — align local toolchain + dependency cache with {@code jk.lock}. CAS/auto-lock
 * are engine-hosted; JDK installs stay client-side (engine only resolves installed JDKs).
 */
public final class SyncCommand implements CliCommand {

    private Path cacheDir;
    private Path jdksDir;
    private java.net.URI repoUrl;
    private boolean offlinePrepare;
    private boolean sources;
    private GlobalOptions global;

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
                cc.jumpkick.cli.CommonOpts.cacheDir(),
                Opt.value("<dir>", "Override the JDK install root. Default: the IntelliJ JDK directory.", "--jdks-dir")
                        .hide(),
                Opt.value("<url>", "Override declared repos with a single URL (for tests).", "--repo-url")
                        .hide(),
                Opt.flag("Prepare for an offline build.", "--offline-prepare"),
                Opt.flag("Also download sources JARs when available.", "--sources"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.repoUrl = in.value("repo-url").map(java.net.URI::create).orElse(null);
        this.offlinePrepare = in.isSet("offline-prepare");
        this.sources = in.isSet("sources");
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        String targetLabel = dir.getFileName() != null ? dir.getFileName().toString() : dir.toString();
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);

        // Pre-flight the JDK ensure client-side: a missing pinned JDK is downloaded HERE, before
        // the request — never silently inside the engine (docs/architecture.md keeps installs, and any
        // interactive consent, client-side). The engine's own ensure-jdk step then only resolves
        // the already-installed JDK (JdkEnsure with allowInstall=false). Thin client: the three
        // values JdkEnsure needs (project jdk spec, java floor, lock pin) ride the ProjectInfo
        // summary rather than a client-side parse.
        var info = BuildCommand.projectInfoOrNull(dir);
        try {
            JdkEnsure.ensure(
                    dir,
                    jdksDir,
                    info == null ? null : info.jdk(),
                    info == null ? 0 : info.javaRelease(),
                    info == null ? null : info.lockJdk(),
                    m -> CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Sync", m)),
                    true);
        } catch (Exception e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Sync", (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
            return 1;
        }

        // Summary counts arrive on the terminal pipeline-finish, before the console listener's own
        // pipelineFinish renders the line — so these holders are settled exactly like the in-process
        // path's counters.
        long[] fetched = new long[1];
        long[] upToDate = new long[1];
        ConsoleSpec spec = syncSpec(() -> fetched[0], () -> upToDate[0]);

        var session = cc.jumpkick.config.SessionContext.current();
        PipelineResult result;
        try {
            result = EngineClient.runSync(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new EngineClient.SyncRequest(
                            dir,
                            cache,
                            jdksDir,
                            repoUrl,
                            sources,
                            session.offline(),
                            session.force(),
                            session.config().forceOr(false),
                            global.verbose),
                    steps -> PipelineConsole.chooseConsoleListener(steps, mode, spec, targetLabel),
                    fetched,
                    upToDate);
        } catch (IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Sync", e.getMessage()));
            return Exit.SOFTWARE;
        }
        // The engine ran the opportunistic cache prune on success (it did the work); nothing more
        // to do here. The progress-bar listener has already surfaced any failure.
        return result.success() ? 0 : 1;
    }

    /** The Sync chip spec; counts are read lazily, at result-line render time. */
    static ConsoleSpec syncSpec(java.util.function.LongSupplier fetched, java.util.function.LongSupplier upToDate) {
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
}
