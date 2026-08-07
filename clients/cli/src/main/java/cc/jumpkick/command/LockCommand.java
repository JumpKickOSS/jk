// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.repo.LibraryRegistryClient;
import cc.jumpkick.repo.LibraryRegistrySync;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.util.JkDirs;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code jk lock} — resolve declared dependencies and write {@code jk-lock.toml}. Features use Cargo
 * semantics ({@code --features}/{@code --no-default-features}). Workspace roots (and members) write
 * a single root lock for the whole monorepo. Engine-hosted; this command renders progress.
 */
public final class LockCommand implements CliCommand {

    private List<String> features = List.of();
    private boolean noDefaultFeatures;
    private boolean sources;
    private URI repoUrl;
    private Path cacheDir;
    private GlobalOptions global;

    @Override
    public String name() {
        return "lock";
    }

    @Override
    public String description() {
        return "Resolve versions for dependencies and write jk-lock.toml";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<a,b,...>", "Activate listed features beyond the defaults.", "--features")
                        .splitOn(","),
                Opt.flag("Don't activate the project's default features.", "--no-default-features"),
                Opt.flag("Pin sources JARs for all Maven deps too.", "--sources"),
                CommonOpts.cacheDir(),
                Opt.value("<url>", "Override declared repos with a single URL.", "--repo-url")
                        .hide(),
                Opt.value("<url>", "Override the library registry URL (used by tests).", "--library-registry-url")
                        .hide(),
                Opt.value(
                                "<file>",
                                "Override the downloaded library catalog path (used by tests).",
                                "--library-cache-file")
                        .hide());
    }

    private URI libraryRegistryUrl;
    private Path libraryCacheFile;

    @Override
    public int run(Invocation in) throws Exception {
        this.features = in.values("features");
        this.noDefaultFeatures = in.isSet("no-default-features");
        this.sources = in.isSet("sources");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.libraryRegistryUrl =
                in.value("library-registry-url").map(URI::create).orElse(null);
        this.libraryCacheFile = in.value("library-cache-file").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (ProjectContext.require(dir, "lock").isEmpty()) return Exit.CONFIG;
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        // Client-side pre-flight: ensure libs.global.toml exists (first-time download) and
        // revalidate when present — before anything parses jk.toml short names. The engine reads
        // the same on-disk file; this closes the race with background StoreFeedRefresh.
        LibraryRegistrySync.ensurePresent(
                global.offline,
                libraryRegistryUrl != null ? libraryRegistryUrl : LibraryRegistryClient.DEFAULT_SOURCE,
                libraryCacheFile != null ? libraryCacheFile : LibraryCatalog.downloadedFile());

        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        boolean live = mode == BuildPlanConsole.Mode.AUTO || mode == BuildPlanConsole.Mode.QUIET;

        // Optimize/start the engine before the Lock plan console so a one-time AOT training shows the
        // "Engine — optimizing…" wedge first, then the Lock TUI takes over (never interleaved).
        cc.jumpkick.cli.engine.EnginePrewarm.ensure();
        return live ? runHostedLive(dir, cache, mode) : runHostedPlain(dir, cache, mode);
    }

    // ---- engine-hosted paths -------------------------------------------------

    private EngineClient.LockRequest lockRequest(Path dir, Path cache) {
        var session = cc.jumpkick.config.SessionContext.current();
        return new EngineClient.LockRequest(
                dir,
                cache,
                features,
                noDefaultFeatures,
                sources,
                repoUrl,
                session.offline(),
                session.force(),
                global.verbose);
    }

    /**
     * Hosted live path (AUTO / QUIET): one shared {@link CommandManager} spanning root + all
     * workspace modules, driven from wire events — one row per module, per-package completion lines
     * (colorized here, never engine-side), and the final Lock chip.
     */
    private int runHostedLive(Path dir, Path cache, BuildPlanConsole.Mode mode) {
        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.plan(CliOutput.stdout(), "Lock", animate);
        long start = System.nanoTime();

        AtomicInteger globalLocked = new AtomicInteger(0);
        // Per-module package counts (cumulative wire samples, then the authoritative lockfile
        // count). The engine restarts totalSeen per module, so the workspace total is the SUM
        // of per-module counts — folding with max reported only the largest module.
        Map<String, Integer> lockedByDir = new java.util.concurrent.ConcurrentHashMap<>();
        List<String> errorLines = new ArrayList<>();
        Map<String, String> coordByDir = new java.util.HashMap<>();

        EngineClient.LockHandler handler = new EngineClient.LockHandler() {
            @Override
            public BuildPlanListener onModuleStart(String moduleDir, String coord, List<Task> steps) {
                coordByDir.put(moduleDir, coord);
                // The display label is empty so renderActiveRow produces "module › dep".
                view.addTaskLabeled(coord, "lock", "");
                view.stepRunning(coord, "lock");
                // Lock is purely resolution — total is unknown upfront, so we show a
                // static top-line label and record each resolved dep as a completion line.
                view.solveLabel("Locking versions…");
                return new BuildPlanListener() {};
            }

            @Override
            public void onPackage(String moduleDir, String name, String version, int totalSeen) {
                String coord = coordByDir.get(moduleDir);
                // Show active dep in the step row (module › dep via renderActiveRow).
                view.stepMessage(coord, "lock", Coords.module(name, version));
                // Absolute count: engine cumulative per-module samples summed across modules;
                // else +1 per event.
                int n;
                if (totalSeen >= 0) {
                    lockedByDir.merge(moduleDir, totalSeen, Math::max);
                    n = lockedByDir.values().stream()
                            .mapToInt(Integer::intValue)
                            .sum();
                    globalLocked.set(Math.max(globalLocked.get(), n));
                } else {
                    n = globalLocked.incrementAndGet();
                }
                Theme t = Theme.active();
                String line = Theme.colorize(Glyphs.CHECK, t.success())
                        + " "
                        + ConsoleSpec.countBracket(n, t)
                        + " "
                        + Coords.module(name, version);
                if (view.animating()) {
                    view.addCompletion(line);
                } else {
                    CliOutput.out(line);
                }
            }

            @Override
            public void onModuleFinish(String moduleDir, BuildPlanResult result, EngineClient.LockCounts counts) {
                view.stepDone(coordByDir.get(moduleDir), "lock", result.success());
                // Authoritative package count from the written lockfile (not wire event cardinality).
                if (counts != null && counts.packages() >= 0) {
                    lockedByDir.put(moduleDir, (int) counts.packages());
                    int sum = lockedByDir.values().stream()
                            .mapToInt(Integer::intValue)
                            .sum();
                    globalLocked.set(Math.max(globalLocked.get(), sum));
                }
                if (!result.success()) {
                    for (BuildPlanResult.Diagnostic d : result.errors()) {
                        errorLines.add(ConsoleSpec.renderError(d));
                    }
                }
            }
        };

        EngineClient.LockOutcome outcome;
        try {
            outcome = EngineClient.runLock(cc.jumpkick.engine.EnginePaths.current(), lockRequest(dir, cache), handler);
        } catch (java.io.IOException e) {
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()), List.of());
            return Exit.SOFTWARE;
        }
        if (!outcome.success()) {
            errorLines.addAll(outcome.errors());
            view.finishBuildPlanFailure(lockFailTail(), errorLines);
            return outcome.exitCode();
        }
        view.finishBuildPlanSuccess(lockSuccessTail(globalLocked.get(), start, dir));
        return 0;
    }

    /** Hosted plain path (--verbose / --output json): one console listener per cascade module. */
    private int runHostedPlain(Path dir, Path cache, BuildPlanConsole.Mode mode) {
        EngineClient.LockHandler handler = new EngineClient.LockHandler() {
            private BuildPlanListener current;

            @Override
            public BuildPlanListener onModuleStart(String moduleDir, String coord, List<Task> steps) {
                current = BuildPlanConsole.chooseConsoleListener("lock", steps, mode);
                return current;
            }

            @Override
            public void onPackage(String moduleDir, String name, String version) {
                // The engine sends structured lock-package events instead of pre-themed labels;
                // colorize here, client-side, exactly as the in-process plan labels itself.
                current.label("resolve-deps", "Resolved " + Coords.module(name, version));
            }
        };

        EngineClient.LockOutcome outcome;
        try {
            outcome = EngineClient.runLock(cc.jumpkick.engine.EnginePaths.current(), lockRequest(dir, cache), handler);
        } catch (java.io.IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Lock", e.getMessage()));
            return Exit.SOFTWARE;
        }
        for (String err : outcome.errors()) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Lock", err));
        }
        return outcome.exitCode();
    }

    // ---- shared rendering helpers --------------------------------------------

    /** Failure result tail for the Lock chip (BuildPlanWedge prepends "Failed to lock"). */
    static String lockFailTail() {
        return "dependencies";
    }

    /**
     * Success chip tail: {@code Lock successful. Resolved N dependencies took T}, or {@code Workspace
     * lock successful.…} when the project is a workspace root or member.
     */
    static String lockSuccessTail(int pkgs, long startNanos, Path projectDir) {
        boolean workspace = cc.jumpkick.lock.LockPaths.isWorkspaceLock(projectDir);
        String title = workspace ? "Workspace lock successful" : "Lock successful";
        return Theme.colorize(title, Theme.active().success())
                + ". Resolved "
                + Theme.colorize(String.valueOf(pkgs), Theme.active().focused())
                + " dependenc" + (pkgs == 1 ? "y" : "ies") + " "
                + ConsoleSpec.took(Duration.ofMillis((System.nanoTime() - startNanos) / 1_000_000));
    }

    /** @deprecated tests may call the 2-arg form */
    @Deprecated
    static String lockSuccessTail(int pkgs, long startNanos) {
        return lockSuccessTail(pkgs, startNanos, Path.of("."));
    }
}
