// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk update} — re-resolve dependencies and overwrite {@code jk-lock.toml} (unlike {@code lock},
 * always fresh). Workspace roots cascade; {@code --git [name]} re-resolves only git deps (pinned
 * refs move only here). Engine-hosted.
 */
public final class UpdateCommand implements CliCommand {

    private List<String> features = List.of();
    private boolean noDefaultFeatures;
    private URI repoUrl;
    private Path cacheDir;
    private GlobalOptions global;
    /** Optional {@code enforced}|{@code floor}; null = project {@code [resolve] platform}. */
    private String platform;

    @Override
    public String name() {
        return "update";
    }

    @Override
    public String description() {
        return "Propose version upgrades for declared dependencies";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<a,b,...>", "Activate listed features beyond defaults.", "--features")
                        .splitOn(","),
                Opt.flag("Don't activate the project's defaults.", "--no-default-features"),
                Opt.value("[<name>]", "Re-resolve git dep(s) by name", "--git").withFallback("*"),
                Opt.value("<url>", "Override declared repos with a single URL.", "--repo-url")
                        .hide(),
                Opt.value("<enforced|floor>", "BOM policy: enforced or floor (lower bounds)", "--platform"),
                CommonOpts.cacheDir());
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.features = in.values("features");
        this.noDefaultFeatures = in.isSet("no-default-features");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        this.platform = in.value("platform").orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (!Files.exists(dir.resolve(ManifestPaths.MANIFEST))) {
            CommandWedge.printFail("Update", "no jk.toml in " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);
        // Same pre-flight as lock: first-time download + revalidate before engine parses jk.toml.
        // Engine-hosted (JIT, no client-side TTL): the CLI never talks to the registry's network.
        EngineClient.freshenCatalog(EnginePaths.current(), "libraries", global.offline, null, null);

        String gitTarget = null;
        if (in.has("git")) {
            String target = in.value("git").orElse("*");
            gitTarget = "*".equals(target) ? null : target;
        }

        return in.has("git") ? runHostedGitOnly(dir, cache, gitTarget) : runHosted(dir, cache);
    }

    // ---- engine-hosted paths -------------------------------------------------

    private EngineRequests.UpdateRequest updateRequest(Path dir, Path cache) {
        var session = SessionContext.current();
        return new EngineRequests.UpdateRequest(
                dir,
                cache,
                features,
                noDefaultFeatures,
                repoUrl,
                session.offline(),
                session.force(),
                global.verbose,
                platform);
    }

    /** Hosted full re-resolve: one console listener per cascade module, summary line per lockfile. */
    private int runHosted(Path dir, Path cache) {
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        EngineRequests.LockHandler handler = new EngineRequests.LockHandler() {
            @Override
            public BuildPlanListener onModuleStart(String moduleDir, String coord, List<Task> steps) {
                return BuildPlanConsole.chooseConsoleListener("Update", "Updating versions", steps, mode);
            }

            @Override
            public void onModuleFinish(String moduleDir, BuildPlanResult result, EngineRequests.LockCounts counts) {
                if (result.success() && !global.outputIsJson()) {
                    printUpdatedLine(
                            LockPaths.lockFile(Path.of(moduleDir)), (int) counts.packages(), global.workingDir());
                }
            }
        };

        EngineRequests.LockOutcome outcome;
        try {
            outcome = EngineClient.runUpdate(EnginePaths.current(), updateRequest(dir, cache), handler);
        } catch (IOException e) {
            CommandWedge.printFail("Update", e.getMessage());
            return Exit.SOFTWARE;
        }
        for (String err : outcome.errors()) {
            CommandWedge.printFail("Update", err);
        }
        // A failed plan sends its real diagnostics as plan events and an EMPTY errors list, so
        // the loop above prints nothing — every interactive command still settles with a wedge.
        if (!outcome.success() && outcome.errors().isEmpty() && !global.outputIsJson()) {
            CommandWedge.printFail("Update", "update failed — see diagnostics above");
        }
        return outcome.exitCode();
    }

    /** Hosted {@code --git} splice: no plan events — the terminal carries the refreshed count. */
    private int runHostedGitOnly(Path dir, Path cache, String gitTarget) {
        EngineRequests.LockOutcome outcome;
        try {
            outcome = EngineClient.runUpdateGitOnly(EnginePaths.current(), updateRequest(dir, cache), gitTarget);
        } catch (IOException e) {
            CommandWedge.printFail("Update", e.getMessage());
            return Exit.SOFTWARE;
        }
        for (String err : outcome.errors()) {
            CommandWedge.printFail("Update", err);
        }
        if (outcome.success() && !global.outputIsJson()) {
            printGitSummary(outcome.refreshed());
        }
        return outcome.exitCode();
    }

    // ---- shared rendering helpers --------------------------------------------

    /**
     * {@code ✓ Update  Updated [yellow]N[/] packages in [path]jk-lock.toml[/]} — count in warning
     * yellow, lockfile name in path periwinkle.
     */
    static void printUpdatedLine(Path lockFile, int packages, Path workingDir) {
        JkWedge.ok("Update", updatedTail(lockFile, packages, workingDir)).print();
    }

    /** Settle-line tail: {@code Updated [yellow]N[/] packages in [path]jk-lock.toml[/]}. */
    static RichText updatedTail(Path lockFile, int packages, Path workingDir) {
        String lockName = lockFile.getFileName() != null
                ? lockFile.getFileName().toString()
                : PathDisplay.of(lockFile, workingDir);
        return RichText.parse("Updated [yellow]"
                + packages
                + "[/] package"
                + (packages == 1 ? "" : "s")
                + " in [path]"
                + RichText.escape(lockName)
                + "[/]");
    }

    /** {@code Refreshed N git dependencies.} / {@code No git dependencies to refresh.} */
    static void printGitSummary(int refreshed) {
        if (refreshed == 0) {
            CommandWedge.printOk("Update", "No git dependencies to refresh.");
        } else {
            CommandWedge.printOk(
                    "Update", "Refreshed " + refreshed + " git dependenc" + (refreshed == 1 ? "y" : "ies") + ".");
        }
    }
}
