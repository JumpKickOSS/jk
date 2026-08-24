// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Spinner;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk clean}: remove per-module {@code target/} ({@code --keep-artifacts} keeps final jars).
 * With global {@code --force}, also invalidates this project's action-cache entries so the next
 * build starts from scratch. Shared-cache hygiene is {@code jk cache clean} / {@code jk storage
 * clean}.
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
                CommonOpts.cacheDir());
    }

    @Override
    public int run(Invocation in) throws IOException {
        boolean keepArtifacts = in.isSet("keep-artifacts");
        boolean force = GlobalOptions.from(in).force;
        Path cacheDirOverride = in.value("cache-dir").map(CliPaths::abs).orElse(null);
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

        if (force) {
            // The hammer's second half: this project's action-cache entries go too, so the
            // next build genuinely starts from scratch. No prompt — --force IS the consent.
            int cleared = clearProjectActionCache(dir, cacheDirOverride);
            if (cleared != 0) return cleared;
        }
        return 0;
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
            Path layoutTarget = BuildLayout.moduleTargetDir(workspaceRoot, projectDir);
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
        Path rootToml = workspaceRoot.resolve(ManifestPaths.MANIFEST);
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

    /** Invalidate this project's (+ workspace's) action-cache entries for {@code --force}. */
    private static int clearProjectActionCache(Path projectDir, Path cacheDirOverride) {
        if (!Files.isRegularFile(projectDir.resolve(ManifestPaths.MANIFEST))) {
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

        var summary = new EngineRequests.CacheMaintSummary[1];
        ConsoleSpec spec = CacheCommand.clearSpec(
                false,
                () -> summary[0] != null ? summary[0].files() : 0L,
                () -> summary[0] != null ? summary[0].bytes() : 0L);
        try {
            var result = EngineClient.runCacheMaintenance(
                    EnginePaths.current(),
                    new EngineRequests.CacheMaintRequest("clear", root, false, false, projectDir),
                    steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, "Cache"),
                    CacheCommand::printWait,
                    summary);
            return result.success() ? 0 : 1;
        } catch (IOException e) {
            CommandWedge.printFail("Clean", e.getMessage());
            return Exit.SOFTWARE;
        }
    }

    /**
     * Delete {@code root} depth-first, folding what went into {@code stats}. One shared
     * implementation ({@link cc.jumpkick.host.PathUtil#deleteRecursivelyOrThrow(Path,
     * cc.jumpkick.host.PathUtil.Removed)}) rather than a clean-local copy: this used to retry the
     * walk on {@link
     * java.nio.file.DirectoryNotEmptyException}, papering over an engine that was still writing
     * {@code target/.jk/preflight} and {@code target/jk-results.md} after telling the client the
     * build was over. The client now waits for {@code job-finish} before returning, so there is no
     * writer left to race and a not-empty directory is a real failure again (JK-2451).
     */
    static void deleteRecursively(Path root, long[] stats) throws IOException {
        var tally = new cc.jumpkick.host.PathUtil.Removed();
        try {
            cc.jumpkick.host.PathUtil.deleteRecursivelyOrThrow(root, tally);
        } finally {
            // Whatever it managed to remove is removed, failure or not — the report must match disk.
            stats[0] += tally.files();
            stats[1] += tally.bytes();
        }
    }
}
