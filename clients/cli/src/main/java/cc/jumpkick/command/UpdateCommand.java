// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.Step;
import cc.jumpkick.util.JkDirs;
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
                cc.jumpkick.cli.CommonOpts.cacheDir());
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.features = in.values("features");
        this.noDefaultFeatures = in.isSet("no-default-features");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.platform = in.value("platform").orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (!Files.exists(dir.resolve("jk.toml"))) {
            CliOutput.err(
                    cc.jumpkick.cli.tui.CommandWedge.fail("Update", "no jk.toml in " + PathDisplay.styledRaw(dir)));
            return Exit.CONFIG;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);
        // Same pre-flight as lock: first-time download + revalidate before engine parses jk.toml.
        cc.jumpkick.repo.LibraryRegistrySync.ensurePresent(global.offline);

        String gitTarget = null;
        if (in.has("git")) {
            String target = in.value("git").orElse("*");
            gitTarget = "*".equals(target) ? null : target;
        }

        return in.has("git") ? runHostedGitOnly(dir, cache, gitTarget) : runHosted(dir, cache);
    }

    // ---- engine-hosted paths -------------------------------------------------

    private EngineClient.UpdateRequest updateRequest(Path dir, Path cache) {
        var session = cc.jumpkick.config.SessionContext.current();
        return new EngineClient.UpdateRequest(
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
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
        EngineClient.LockHandler handler = new EngineClient.LockHandler() {
            @Override
            public PipelineListener onModuleStart(String moduleDir, String coord, List<Step> steps) {
                return PipelineConsole.chooseConsoleListener("update", steps, mode);
            }

            @Override
            public void onModuleFinish(String moduleDir, PipelineResult result, EngineClient.LockCounts counts) {
                if (result.success() && !global.outputIsJson()) {
                    printUpdatedLine(
                            cc.jumpkick.lock.LockPaths.lockFile(Path.of(moduleDir)),
                            (int) counts.packages(),
                            global.workingDir());
                }
            }
        };

        EngineClient.LockOutcome outcome;
        try {
            outcome = EngineClient.runUpdate(
                    cc.jumpkick.engine.EnginePaths.current(), updateRequest(dir, cache), handler);
        } catch (java.io.IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Update", e.getMessage()));
            return Exit.SOFTWARE;
        }
        for (String err : outcome.errors()) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Update", err));
        }
        return outcome.exitCode();
    }

    /** Hosted {@code --git} splice: no pipeline events — the terminal carries the refreshed count. */
    private int runHostedGitOnly(Path dir, Path cache, String gitTarget) {
        EngineClient.LockOutcome outcome;
        try {
            outcome = EngineClient.runUpdateGitOnly(
                    cc.jumpkick.engine.EnginePaths.current(), updateRequest(dir, cache), gitTarget);
        } catch (java.io.IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Update", e.getMessage()));
            return Exit.SOFTWARE;
        }
        for (String err : outcome.errors()) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Update", err));
        }
        if (outcome.success() && !global.outputIsJson()) {
            printGitSummary(outcome.refreshed());
        }
        return outcome.exitCode();
    }

    // ---- shared rendering helpers --------------------------------------------

    /** {@code ✓ Updated: path/to/jk-lock.toml › N packages} — shared by the hosted and in-process paths. */
    static void printUpdatedLine(Path lockFile, int packages, Path workingDir) {
        var th = Theme.active();
        CliOutput.out(Theme.colorize(Glyphs.CHECK, th.success())
                + " Updated: "
                + Theme.colorize(PathDisplay.of(lockFile, workingDir), th.path())
                + " "
                + Theme.colorize("›", th.darkGray())
                + " "
                + Theme.colorize(String.valueOf(packages), th.cyan())
                + " package"
                + (packages == 1 ? "" : "s"));
    }

    /** {@code Refreshed N git dependencies.} / {@code No git dependencies to refresh.} */
    static void printGitSummary(int refreshed) {
        CliOutput.out(
                refreshed == 0
                        ? "No git dependencies to refresh."
                        : "Refreshed " + refreshed + " git dependenc" + (refreshed == 1 ? "y" : "ies") + ".");
    }
}
