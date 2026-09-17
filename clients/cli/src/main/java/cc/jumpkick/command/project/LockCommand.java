// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.ProjectContext;
import cc.jumpkick.cli.engine.EngineCatalogFreshen;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EnginePrewarm;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.CliSessionTranscript;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.SessionMirrorListener;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk lock} — resolve declared dependencies and write {@code jk-lock.toml}. Pinned versions
 * stay put: only what a new or changed constraint rules out moves. {@code -F}/{@code --force}
 * floats every pin to the newest version its declared range allows; deliberate upgrades that are
 * not a force-refresh belong on {@code jk update}. Features use Cargo semantics ({@code
 * --features}/{@code --no-default-features}). Workspace roots (and members) write a single root
 * lock for the whole monorepo. Engine-hosted; this command renders progress.
 */
public final class LockCommand implements CliCommand {

    private List<String> features = List.of();
    private boolean noDefaultFeatures;
    private boolean sources;
    private @Nullable URI repoUrl;
    private @Nullable Path cacheDir;
    private GlobalOptions global;
    private @Nullable CliSessionTranscript session;

    @Override
    public String name() {
        return "lock";
    }

    @Override
    public String description() {
        return "Resolve dependencies into jk-lock.toml, keeping pinned versions";
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

    private @Nullable URI libraryRegistryUrl;
    private @Nullable Path libraryCacheFile;

    @Override
    public int run(Invocation in) throws Exception {
        this.features = in.values("features");
        this.noDefaultFeatures = in.isSet("no-default-features");
        this.sources = in.isSet("sources");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        this.libraryRegistryUrl =
                in.value("library-registry-url").map(URI::create).orElse(null);
        this.libraryCacheFile = in.value("library-cache-file").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (ProjectContext.require(dir, "lock").isEmpty()) return Exit.CONFIG;
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        // Ensure libs.global.toml exists (first-time download) and revalidate when present —
        // before anything parses jk.toml short names. Engine-hosted (JIT, no client-side TTL): the
        // CLI never talks to the library registry's network itself, and the engine reads the same
        // on-disk file this writes, closing the race with background StoreFeedRefresh.
        EngineCatalogFreshen.freshenCatalog(
                EnginePaths.current(),
                "libraries",
                global.offline,
                libraryRegistryUrl != null ? libraryRegistryUrl.toString() : null,
                libraryCacheFile != null ? libraryCacheFile : JkDirs.libraryRegistry());

        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        boolean live = mode == BuildPlanConsole.Mode.AUTO || mode == BuildPlanConsole.Mode.QUIET;

        // Optimize/start the engine before the Lock plan console so a one-time AOT training shows the
        // "Engine — optimizing…" wedge first, then the Lock TUI takes over (never interleaved).
        EnginePrewarm.ensure();
        // The lock's run record carries a details.jsonl like a build's: the transcript binds to the
        // engine job when its job-start arrives and mirrors the plan events the handlers see.
        session = CliSessionTranscript.open(dir, "lock", lockArgv());
        int code = live ? runHostedLive(dir, cache, mode) : runHostedPlain(dir, cache, mode);
        return CliSessionTranscript.finish(session, code, global.verbose);
    }

    /** Compact argv snapshot for details.jsonl. */
    private List<String> lockArgv() {
        List<String> argv = new ArrayList<>();
        argv.add("lock");
        if (!features.isEmpty()) argv.add("--features=" + String.join(",", features));
        if (noDefaultFeatures) argv.add("--no-default-features");
        if (sources) argv.add("--sources");
        return argv;
    }

    // ---- engine-hosted paths -------------------------------------------------

    private EngineRequests.LockRequest lockRequest(Path dir, Path cache) {
        var session = SessionContext.current();
        return new EngineRequests.LockRequest(
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
     * Hosted live path (AUTO / QUIET): one shared {@link JkManager} spanning root + all
     * workspace modules, driven from wire events through a {@link LiveLockHandler}.
     */
    private int runHostedLive(Path dir, Path cache, BuildPlanConsole.Mode mode) {
        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();
        JkManager view = JkManager.plan(CliOutput.stdout(), "Lock", animate);
        long start = System.nanoTime();
        LiveLockHandler handler = new LiveLockHandler(view, mode);

        EngineRequests.LockOutcome outcome;
        try {
            outcome = EngineClient.runLock(EnginePaths.current(), lockRequest(dir, cache), handler);
        } catch (IOException e) {
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()), List.of());
            return Exit.SOFTWARE;
        }
        if (!outcome.success()) {
            handler.errorLines.addAll(outcome.errors());
            view.finishBuildPlanFailure(lockFailTail(), handler.errorLines);
            printNotes(handler.notes());
            return outcome.exitCode();
        }
        view.finishBuildPlanSuccess(lockSuccessTail(
                handler.globalLocked.get(), handler.unverified.get(), List.copyOf(handler.insecureRepos), start, dir));
        printNotes(handler.notes());
        return 0;
    }

    /**
     * The lock's notes, after the summary chip: which member reads its own rows, which pin or BOM
     * overrode what a POM asked for, which repository a POM declared served a row. One line each.
     */
    static void printNotes(List<String> notes) {
        if (notes.isEmpty()) return;
        String bang = Theme.colorize(Glyphs.bang(), Theme.active().warning());
        for (String note : notes) CliOutput.out("  " + bang + " " + note);
    }

    /**
     * The live path's view of a lock cascade: one row per module, per-package completion lines
     * (colorized here, never engine-side), and the lock's phase lines. With a live region the phase
     * rides the module's row; without one ({@code --no-progress}, a pipe) each phase line prints as
     * it arrives, so a transcript of a long solve shows the lock moving.
     */
    static final class LiveLockHandler implements EngineRequests.LockHandler {
        private final JkManager view;
        private final BuildPlanConsole.Mode mode;
        final AtomicInteger globalLocked = new AtomicInteger(0);
        // Every warning the lock plans raised, once each, in arrival order: printed after the chip.
        private final Set<String> notes = Collections.synchronizedSet(new LinkedHashSet<>());
        // Per-module package counts (cumulative wire samples, then the authoritative lockfile
        // count). The engine restarts totalSeen per module, so the workspace total is the SUM
        // of per-module counts — folding with max reported only the largest module.
        private final Map<String, Integer> lockedByDir = new ConcurrentHashMap<>();
        // What the downloads were checked against, summed over modules for the Lock chip.
        final AtomicLong unverified = new AtomicLong();
        final Set<String> insecureRepos = Collections.synchronizedSet(new LinkedHashSet<>());
        final List<String> errorLines = new ArrayList<>();
        private final Map<String, String> coordByDir = new HashMap<>();

        LiveLockHandler(JkManager view) {
            this(view, BuildPlanConsole.Mode.AUTO);
        }

        LiveLockHandler(JkManager view, BuildPlanConsole.Mode mode) {
            this.view = view;
            this.mode = mode;
        }

        /** The notes collected so far, in arrival order. */
        List<String> notes() {
            synchronized (notes) {
                return List.copyOf(notes);
            }
        }

        @Override
        public BuildPlanListener onModuleStart(String moduleDir, String coord, List<Task> steps) {
            coordByDir.put(moduleDir, coord);
            // The display label is empty so renderActiveRow produces "module › dep".
            view.addTaskLabeled(coord, "lock", "");
            view.stepRunning(coord, "lock");
            // Lock is purely resolution — total is unknown upfront, so we show a
            // static top-line label and record each resolved dep as a completion line.
            view.solveLabel("Locking versions…");
            BuildPlanListener collector = new BuildPlanListener() {
                @Override
                public void warn(String step, String code, String message) {
                    if (message != null && !message.isBlank()) notes.add(message);
                }
            };
            return SessionMirrorListener.mirrored(collector, mode);
        }

        @Override
        public void onPhase(@Nullable String moduleDir, String label) {
            if (view.animating()) {
                view.stepMessage(coordByDir.get(moduleDir), "lock", label);
            } else {
                CliOutput.out(label);
            }
        }

        @Override
        public void onPackage(@Nullable String moduleDir, String name, @Nullable String version, int totalSeen) {
            String coord = coordByDir.get(moduleDir);
            // Show active dep in the step row (module › dep via renderActiveRow).
            view.stepMessage(coord, "lock", Coords.module(name, version));
            // Absolute count: engine cumulative per-module samples summed across modules;
            // else +1 per event.
            int n;
            if (totalSeen >= 0) {
                lockedByDir.merge(moduleDir, totalSeen, Math::max);
                n = lockedByDir.values().stream().mapToInt(Integer::intValue).sum();
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
        public void onModuleFinish(String moduleDir, BuildPlanResult result, EngineRequests.LockCounts counts) {
            view.stepDone(coordByDir.get(moduleDir), "lock", result.success());
            // Authoritative package count from the written lockfile (not wire event cardinality).
            if (counts != null && counts.packages() >= 0) {
                lockedByDir.put(moduleDir, (int) counts.packages());
                int sum = lockedByDir.values().stream()
                        .mapToInt(Integer::intValue)
                        .sum();
                globalLocked.set(Math.max(globalLocked.get(), sum));
            }
            if (counts != null) {
                unverified.addAndGet(Math.max(0, counts.unverified()));
                insecureRepos.addAll(counts.insecureRepos());
            }
            if (!result.success()) {
                ConsoleSpec.appendErrors(errorLines, result.errors());
            }
        }
    }

    /** Hosted plain path (--verbose / --output json): one console listener per cascade module. */
    private int runHostedPlain(Path dir, Path cache, BuildPlanConsole.Mode mode) {
        EngineRequests.LockHandler handler = new EngineRequests.LockHandler() {
            private @Nullable BuildPlanListener current;

            @Override
            public BuildPlanListener onModuleStart(String moduleDir, String coord, List<Task> steps) {
                current = SessionMirrorListener.mirrored(
                        BuildPlanConsole.chooseConsoleListener("lock", steps, mode), mode);
                return current;
            }

            @Override
            public void onPhase(@Nullable String moduleDir, String label) {
                Objects.requireNonNull(current, "lock-phase before module-start")
                        .label(TaskNames.RESOLVE_DEPS, label);
            }

            @Override
            public void onPackage(@Nullable String moduleDir, String name, @Nullable String version) {
                // The engine sends structured lock-package events instead of pre-themed labels;
                // colorize here, client-side, exactly as the in-process plan labels itself.
                Objects.requireNonNull(current, "lock-package before module-start")
                        .label(TaskNames.RESOLVE_DEPS, "Resolved " + Coords.module(name, version));
            }
        };

        EngineRequests.LockOutcome outcome;
        try {
            outcome = EngineClient.runLock(EnginePaths.current(), lockRequest(dir, cache), handler);
        } catch (IOException e) {
            CommandWedge.printFail("Lock", e.getMessage());
            return Exit.SOFTWARE;
        }
        for (String err : outcome.errors()) {
            CommandWedge.printFail("Lock", err);
        }
        return outcome.exitCode();
    }

    // ---- shared rendering helpers --------------------------------------------

    /** Failure result tail for the Lock chip (JkWedge prepends "Failed to lock"). */
    static String lockFailTail() {
        return "dependencies";
    }

    /**
     * Success chip tail: {@code Lock successful. Resolved N dependencies took T}, or {@code Workspace
     * lock successful.…} when the project is a workspace root or member. Two segments appear only
     * when a repository opted out of a check: {@code · N unverified (allowed)} counts the artifacts
     * pinned without a published checksum, and {@code · insecure (allowed): mirror} names the
     * plaintext repositories asked.
     */
    static String lockSuccessTail(
            int pkgs, long unverified, List<String> insecureRepos, long startNanos, Path projectDir) {
        boolean workspace = LockPaths.isWorkspaceLock(projectDir);
        String title = workspace ? "Workspace lock successful" : "Lock successful";
        Theme t = Theme.active();
        StringBuilder tail = new StringBuilder(Theme.colorize(title, t.success()))
                .append(". Resolved ")
                .append(Theme.colorize(String.valueOf(pkgs), t.focused()))
                .append(" dependenc")
                .append(pkgs == 1 ? "y" : "ies");
        if (unverified > 0) {
            tail.append(" · ")
                    .append(Theme.colorize(String.valueOf(unverified), t.warning()))
                    .append(" unverified (allowed)");
        }
        if (!insecureRepos.isEmpty()) {
            tail.append(" · ")
                    .append(Theme.colorize("insecure (allowed)", t.warning()))
                    .append(": ")
                    .append(String.join(", ", insecureRepos));
        }
        return tail.append(' ')
                .append(ConsoleSpec.took(Duration.ofMillis((Clock.SYSTEM.nanos() - startNanos) / 1_000_000)))
                .toString();
    }
}
