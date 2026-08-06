// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Spinner;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code jk clean}: remove per-module {@code target/} ({@code --keep-artifacts} keeps final jars),
 * optional {@code --cache} GC, optional {@code --force} action-cache invalidation for the project.
 */
public final class CleanCommand implements CliCommand {

    @Override
    public String name() {
        return "clean";
    }

    @Override
    public String description() {
        return "Delete generated build outputs";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Delete only build/ intermediates; keep artifacts.", "--keep-artifacts"),
                Opt.flag("GC the shared cache: purge blobs idle 90+ days.", "--cache"),
                cc.jumpkick.cli.CommonOpts.cacheDir());
    }

    @Override
    public int run(Invocation in) throws IOException {
        boolean keepArtifacts = in.isSet("keep-artifacts");
        boolean gcCache = in.isSet("cache");
        boolean force = GlobalOptions.from(in).force;
        Path cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        Path dir = GlobalOptions.from(in).workingDir();
        Path workspaceRoot = resolveWorkspaceRoot(dir);
        List<String> warnings = new ArrayList<>();
        List<Path> projectDirs = collectProjectDirs(workspaceRoot, warnings);
        for (String warning : warnings) {
            CliOutput.err(Theme.colorize(Glyphs.BANG, Theme.active().warning()) + " " + warning);
        }

        long startMs = System.currentTimeMillis();
        long[] stats = {0L, 0L}; // [fileCount, totalBytes]

        CommandWedge.envelopeStart(); // leading blank before spinner / settle chrome
        try (Spinner spinner = Spinner.show(CliOutput.stdout(), "Cleaning...")) {
            cleanTargets(workspaceRoot, projectDirs, keepArtifacts, stats);
        }

        long elapsedMs = System.currentTimeMillis() - startMs;

        if (stats[0] == 0) {
            CommandWedge.printOk("Clean", "Nothing to remove");
        } else {
            String removed = Theme.colorize("Removed", Theme.active().focused());
            String stats_ = String.format(
                    "%,d file%s, %s total", stats[0], stats[0] == 1 ? "" : "s", CacheCommand.fmtBytes(stats[1]));
            String inTime = ConsoleSpec.took(Duration.ofMillis(elapsedMs));
            CommandWedge.printOk("Clean", removed + " " + stats_ + " " + inTime);
        }

        if (gcCache) {
            gcCache();
        }
        if (force) {
            // The hammer's second half: this project's action-cache entries go too, so the
            // next build genuinely starts from scratch. No prompt — --force IS the consent.
            int cleared = clearProjectActionCache(dir, cacheDirOverride);
            if (cleared != 0) return cleared;
        }
        return 0;
    }

    /** Run the cache GC (engine-hosted for a real invocation) and print a one-line summary. */
    private static void gcCache() throws IOException {
        long purgedBlobs;
        long freedBytes;
        long repoLinksRemoved;
        // Hosted: the spinner stays client-side (the plan has no per-file progress worth a
        // bar); the counts ride the terminal plan-finish.
        var summary = new cc.jumpkick.cli.engine.EngineClient.CacheMaintSummary[1];
        try (Spinner spinner = Spinner.show(CliOutput.stdout(), "Collecting cache...")) {
            cc.jumpkick.run.BuildPlanResult result = cc.jumpkick.cli.engine.EngineClient.runCacheMaintenance(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineClient.CacheMaintRequest(
                            "gc", JkDirs.cache(), 0, false, false, null, false),
                    steps -> new cc.jumpkick.run.BuildPlanListener() {},
                    (external, plans) -> {},
                    summary);
            if (!result.success() || summary[0] == null) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                        "Clean", "cache GC failed — run `jk engine status` for details"));
                return;
            }
        }
        purgedBlobs = Math.max(0, summary[0].files());
        freedBytes = Math.max(0, summary[0].bytes());
        repoLinksRemoved = Math.max(0, summary[0].repoLinks());

        if (purgedBlobs == 0) {
            CommandWedge.printOk("Cache GC", "nothing idle past 90 days");
        } else {
            String msg = String.format(
                    "purged %,d blob%s (%s), %,d repo link%s",
                    purgedBlobs,
                    purgedBlobs == 1 ? "" : "s",
                    CacheCommand.fmtBytes(freedBytes),
                    repoLinksRemoved,
                    repoLinksRemoved == 1 ? "" : "s");
            CommandWedge.printOk("Cache GC", msg);
        }
    }

    /** Build-intermediate subdirs removed by {@code --keep-artifacts} (final jars stay). */
    private static final List<String> INTERMEDIATE_SUBDIRS =
            List.of("classes", "kotlin", "resources", "generated", "tmp", "test-results", "reports");

    /**
     * Delete each project's output tree (or, with {@code keepArtifacts}, only its intermediates).
     * Outputs live at the layout-resolved target dir — {@code <workspace>/target/<rel>/} for a
     * member, not {@code <member>/target/}. The member-local {@code target/} is still
     * swept for trees built before the layout change.
     */
    static void cleanTargets(Path workspaceRoot, List<Path> projectDirs, boolean keepArtifacts, long[] stats)
            throws IOException {
        for (Path projectDir : projectDirs) {
            Path layoutTarget = cc.jumpkick.layout.BuildLayout.moduleTargetDir(workspaceRoot, projectDir);
            Path legacyTarget = projectDir.resolve("target");
            boolean distinct = !layoutTarget.equals(legacyTarget);
            if (!keepArtifacts) {
                deleteRecursively(layoutTarget, stats);
                if (distinct) deleteRecursively(legacyTarget, stats);
            } else {
                for (String sub : INTERMEDIATE_SUBDIRS) {
                    deleteRecursively(layoutTarget.resolve(sub), stats);
                    if (distinct) deleteRecursively(legacyTarget.resolve(sub), stats);
                }
            }
        }
    }

    /**
     * Returns the workspace root plus every declared module directory. Falls back to just {@code
     * [workspaceRoot]} when parsing fails or there are no modules (single-project).
     */
    private static List<Path> collectProjectDirs(Path workspaceRoot, List<String> warnings) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(workspaceRoot);
        Path rootToml = workspaceRoot.resolve("jk.toml");
        if (!Files.exists(rootToml)) return dirs;
        var info = BuildCommand.projectInfoOrNull(workspaceRoot);
        if (info != null && info.workspaceRoot()) {
            for (Path moduleDir : resolveModuleDirs(workspaceRoot, info.moduleDirs(), warnings)) {
                if (Files.isDirectory(moduleDir)) dirs.add(moduleDir);
            }
        }
        return dirs;
    }

    /**
     * Module entries resolved against the workspace root; entries that escape it (absolute paths,
     * {@code..}) are skipped with a warning — a hostile {@code [workspace].modules} entry must
     * never point {@code jk clean} outside the workspace.
     */
    static List<Path> resolveModuleDirs(Path workspaceRoot, List<String> modules, List<String> warnings) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<Path> dirs = new ArrayList<>();
        for (String module : modules) {
            Path moduleDir = root.resolve(module).normalize();
            if (!moduleDir.startsWith(root)) {
                warnings.add("skipping module outside workspace: " + module);
                continue;
            }
            dirs.add(moduleDir);
        }
        return dirs;
    }

    private static Path resolveWorkspaceRoot(Path dir) {
        return WorkspaceScan.findRoot(dir).orElse(dir);
    }

    /** Invalidate this project's (+ workspace's) action-cache entries — `jk cache clear -y`. */
    private static int clearProjectActionCache(Path projectDir, Path cacheDirOverride) {
        if (!Files.isRegularFile(projectDir.resolve("jk.toml"))) {
            // Not a project dir: nothing project-scoped to clear; the file clean already ran.
            return 0;
        }
        // Match BuildCommand: action-cache tags/INPUT paths are keyed on the realpath of the
        // module root. Without this, macOS /var → /private/var (and other symlink roots) make
        // `jk clean --force` miss every entry the build just wrote.
        try {
            projectDir = projectDir.toRealPath();
        } catch (IOException ignored) {
            projectDir = projectDir.toAbsolutePath().normalize();
        }
        Path root = CacheCommand.resolveCacheRoot(cacheDirOverride);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(new GlobalOptions());

        var summary = new cc.jumpkick.cli.engine.EngineClient.CacheMaintSummary[1];
        ConsoleSpec spec = CacheCommand.CacheClearCommand.clearSpec(
                false,
                () -> summary[0] != null ? summary[0].files() : 0L,
                () -> summary[0] != null ? summary[0].bytes() : 0L);
        try {
            var result = cc.jumpkick.cli.engine.EngineClient.runCacheMaintenance(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineClient.CacheMaintRequest(
                            "clear", root, 0, false, false, null, false, projectDir),
                    steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, "Cache"),
                    CacheCommand::printWait,
                    summary);
            return result.success() ? 0 : 1;
        } catch (IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Clean", e.getMessage()));
            return cc.jumpkick.model.command.Exit.SOFTWARE;
        }
    }

    /**
     * Delete {@code root} depth-first. Retries a few times when the tree is racing the engine
     * (e.g. {@code target/.jk/preflight} rewritten mid-walk) so {@code jk clean} does not exit 1
     * on a transient {@link java.nio.file.DirectoryNotEmptyException}.
     */
    static void deleteRecursively(Path root, long[] stats) throws IOException {
        if (!Files.exists(root)) return;
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            if (attempt > 0) {
                // Brief pause so a concurrent preflight/memo write can finish before we re-walk.
                try {
                    Thread.sleep(25L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
            try {
                try (Stream<Path> stream = Files.walk(root)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            long size = Files.isRegularFile(p) ? Files.size(p) : -1;
                            // Count only after a successful delete — a failed attempt must not
                            // inflate the stats across retry walks.
                            if (Files.deleteIfExists(p) && size >= 0) {
                                stats[0]++;
                                stats[1] += size;
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
                return;
            } catch (UncheckedIOException e) {
                last = e.getCause() instanceof IOException io ? io : new IOException(e);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }
}
