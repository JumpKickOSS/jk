// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.EngineCatalogFreshen;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.cli.run.SessionMirrorListener;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk update} — move the exact pins {@code jk.toml} declares to the newest stable on the same
 * Maven major ({@code --major} to cross one; names or {@code --dep} to pick handles), then
 * re-resolve and overwrite {@code jk-lock.toml}. Workspace roots cascade over every member; {@code
 * --git [name]} re-resolves only git deps (pinned refs move only here). Engine-hosted.
 */
public final class UpdateCommand implements CliCommand {

    private List<String> features = List.of();
    private boolean noDefaultFeatures;
    private @Nullable URI repoUrl;
    private @Nullable Path cacheDir;
    private GlobalOptions global;
    /** Optional {@code enforced}|{@code floor}; null = project {@code [resolve] platform}. */
    private @Nullable String platform;
    /** Handles or {@code group:artifact} coordinates to move; empty = every declared pin. */
    private List<String> deps = List.of();

    private boolean major;

    @Override
    public String name() {
        return "update";
    }

    @Override
    public String description() {
        return "Bump declared pins to the newest stable on their major";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<a,b,...>", "Activate listed features beyond defaults.", "--features")
                        .splitOn(","),
                Opt.flag("Don't activate the project's defaults.", "--no-default-features"),
                Opt.value("<name>", "Limit to this handle or group:artifact", "--dep")
                        .repeat(),
                Opt.flag("Allow a pin to cross its Maven major", "--major"),
                Opt.value("[<name>]", "Re-resolve git dep(s) by name", "--git").withFallback("*"),
                Opt.value("<url>", "Override declared repos with a single URL.", "--repo-url")
                        .hide(),
                Opt.value("<enforced|floor>", "BOM policy: enforced or floor (lower bounds)", "--platform"),
                CommonOpts.cacheDir());
    }

    @Override
    public List<Param> parameters() {
        return List.of(
                Param.of("name", Arity.ZERO_OR_MORE, "Handles or group:artifact coordinates to move (default: all)."));
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.features = in.values("features");
        List<String> picked = new ArrayList<>(in.positionals());
        picked.addAll(in.values("dep"));
        this.deps = List.copyOf(picked);
        this.major = in.isSet("major");
        this.noDefaultFeatures = in.isSet("no-default-features");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        this.platform = in.value("platform").orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (!Files.exists(dir.resolve(ManifestPaths.MANIFEST))) return ManifestEditRefusal.print("Update", dir);
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);
        // Same pre-flight as lock: first-time download + revalidate before engine parses jk.toml.
        // Engine-hosted (JIT, no client-side TTL): the CLI never talks to the registry's network.
        EngineCatalogFreshen.freshenCatalog(EnginePaths.current(), "libraries", global.offline, null, null);

        String gitTarget = null;
        if (in.has("git")) {
            String target = in.value("git").orElse("*");
            gitTarget = "*".equals(target) ? null : target;
        }

        // The update's run record carries a details.jsonl like a lock's: the transcript binds to the
        // engine job when its job-start arrives and mirrors the plan events the handlers see.
        CliSessionTranscript transcript = CliSessionTranscript.open(dir, "update", updateArgv(in));
        int code = in.has("git") ? runHostedGitOnly(dir, cache, gitTarget) : runHosted(dir, cache);
        return CliSessionTranscript.finish(transcript, code, global.verbose);
    }

    /** Compact argv snapshot for details.jsonl. */
    private List<String> updateArgv(Invocation in) {
        List<String> argv = new ArrayList<>();
        argv.add("update");
        argv.addAll(deps);
        if (major) argv.add("--major");
        if (!features.isEmpty()) argv.add("--features=" + String.join(",", features));
        if (noDefaultFeatures) argv.add("--no-default-features");
        if (platform != null) argv.add("--platform=" + platform);
        if (in.has("git")) argv.add("--git=" + in.value("git").orElse("*"));
        return argv;
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
                platform,
                deps,
                major);
    }

    /** Hosted full re-resolve: one console listener per cascade module, summary line per lockfile. */
    private int runHosted(Path dir, Path cache) {
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        EngineRequests.LockHandler handler = new EngineRequests.LockHandler() {
            @Override
            public void onRewrite(
                    String moduleDir, String table, String handle, String module, String from, String to) {
                if (!global.outputIsJson()) printRewrite(Path.of(moduleDir), table, handle, from, to, dir);
            }

            @Override
            public BuildPlanListener onModuleStart(String moduleDir, String coord, List<Task> steps) {
                return SessionMirrorListener.mirrored(
                        BuildPlanConsole.chooseConsoleListener("Update", "Updating versions", steps, mode), mode);
            }

            @Override
            public void onModuleFinish(
                    @Nullable String moduleDir, BuildPlanResult result, EngineRequests.LockCounts counts) {
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
    private int runHostedGitOnly(Path dir, Path cache, @Nullable String gitTarget) {
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
     * One moved pin: {@code   handle  1.2.3 → 1.2.5} (workspace members prefix the manifest's
     * directory; an entry outside the dependency scope tables — {@code [workspace.dependencies]}, a
     * tool table's key — names its table).
     */
    static void printRewrite(Path manifestDir, String table, String handle, String from, String to, Path workingDir) {
        String where = manifestDir.equals(workingDir) ? "" : PathDisplay.of(manifestDir, workingDir) + "  ";
        String tableTag = isScopeTable(table) ? "" : "  [dim](" + RichText.escape(table) + ")[/]";
        CliOutput.out(RichText.parse("  [dim]" + RichText.escape(where) + "[/][bold]" + RichText.escape(handle)
                        + "[/]  " + RichText.escape(from) + " → [yellow]" + RichText.escape(to) + "[/]" + tableTag)
                .render());
    }

    /**
     * {@code ✓ Update  Updated [yellow]N[/] packages in [path]jk-lock.toml[/]} — count in warning
     * yellow, lockfile name in path periwinkle.
     */
    static void printUpdatedLine(Path lockFile, int packages, Path workingDir) {
        JkWedge.ok("Update", updatedTail(lockFile, packages, workingDir)).print();
    }

    /** True for a dependency scope table ({@code dependencies}, {@code test-dependencies}, …). */
    private static boolean isScopeTable(String table) {
        for (Scope scope : Scope.values()) {
            if (scope.tomlSection().equals(table)) return true;
        }
        return false;
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
