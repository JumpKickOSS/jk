// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.ProgressRow;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.TestHomes;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
                Opt.flag("Delete only target/ intermediates; keep artifacts.", "--keep-artifacts"),
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
        List<Path> roots = deleteRoots(workspaceRoot, projectDirs, keepArtifacts);
        var tally = new PathUtil.Removed();

        // The row opens before anything is counted so a big tree gets chrome from the first
        // moment; the bar appears once the count is in, and the settle takes the row's place.
        ProgressRow row = ProgressRow.of(CliOutput.stdout(), "Clean")
                .status("Counting…")
                .cancelSubject("clean")
                .open();
        IOException stuck = null;
        try {
            long total = PathUtil.measureTrees(roots).files();
            if (total > 0) {
                row.status("Removing " + (keepArtifacts ? BuildLayout.TARGET + " intermediates" : BuildLayout.TARGET));
                row.follow(tally::files, total);
                PathUtil.deleteTrees(roots, tally);
            }
        } catch (IOException e) {
            // The walk finished; what it could remove is gone. The settle below says so, and names
            // the file that would not go — on Windows, one another process still has open.
            stuck = e;
        } finally {
            row.finish();
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        long files = tally.files();
        String stats = String.format(
                "%,d file%s, %s total", files, files == 1 ? "" : "s", CacheCommand.fmtBytes(tally.bytes()));

        if (stuck != null) {
            CommandWedge.printFail("Clean", stuckMessage(stuck, workspaceRoot, files, stats));
            return 1;
        }
        if (files == 0) {
            CommandWedge.printOk("Clean", "Nothing to remove");
        } else {
            String removed = Theme.colorize("Removed", Theme.active().focused());
            String inTime = ConsoleSpec.took(Duration.ofMillis(elapsedMs));
            CommandWedge.printOk("Clean", removed + " " + stats + " " + inTime);
        }

        if (force) {
            // The hammer's second half: this project's action-cache entries go too, so the
            // next build genuinely starts from scratch. No prompt — --force IS the consent.
            int cleared = clearProjectActionCache(dir, cacheDirOverride);
            if (cleared != 0) return cleared;
        }
        return 0;
    }

    /**
     * The failure settle: what did go, then the first file that would not and how many more. A
     * file that cannot be unlinked on Windows is one some process still has open — a build, the
     * resident engine's worker, an IDE — so the line says where to look.
     */
    static String stuckMessage(IOException stuck, Path workspaceRoot, long files, String stats) {
        String path = stuck.getMessage() == null ? "a file" : stuck.getMessage();
        try {
            Path p = Path.of(path);
            if (p.isAbsolute() && p.startsWith(workspaceRoot)) {
                path = workspaceRoot.relativize(p).toString().replace('\\', '/');
            }
        } catch (RuntimeException notAPath) {
            // the message was not a path; show it as it came
        }
        int more = stuck.getSuppressed().length;
        String others = more == 0 ? "" : " and " + more + " more";
        String removed = files == 0 ? "Nothing removed" : "Removed " + stats + ", but";
        return removed + " " + path + others + " could not be removed: another process has it open"
                + " (a build, the engine, or an IDE)";
    }

    /** Build-intermediate subdirs removed by {@code --keep-artifacts} (final jars stay). */
    private static final List<String> INTERMEDIATE_SUBDIRS =
            List.of("classes", "kotlin", "resources", "generated", "tmp", "test-results", "reports");

    /**
     * Every root the clean removes, for one pooled delete: each project's output tree (or, with
     * {@code keepArtifacts}, only its intermediates). Outputs live at the layout-resolved target
     * dir — {@code <workspace>/target/<rel>/} for a member, not {@code <member>/target/}. A
     * distinct member-local {@code target/} is also swept when present.
     *
     * <p>The module's test sandbox home goes too. It is not under {@code target/} — it holds jk's
     * whole layout and a directory of that shape inside a source tree is what a stray {@code git}
     * command walks up out of ({@link TestHomes}) — so a full clean names it explicitly. Only a
     * full clean: {@code --keep-artifacts} keeps intermediates, and a warm store is the most
     * intermediate thing here.
     */
    static List<Path> deleteRoots(Path workspaceRoot, List<Path> projectDirs, boolean keepArtifacts) {
        List<Path> roots = new ArrayList<>();
        for (Path projectDir : projectDirs) {
            Path layoutTarget = BuildLayout.moduleTargetDir(workspaceRoot, projectDir);
            Path memberLocalTarget = projectDir.resolve(BuildLayout.TARGET);
            boolean distinct = !layoutTarget.equals(memberLocalTarget);
            if (!keepArtifacts) {
                roots.add(layoutTarget);
                if (distinct) roots.add(memberLocalTarget);
                roots.add(TestHomes.slotFor(projectDir));
            } else {
                for (String sub : INTERMEDIATE_SUBDIRS) {
                    roots.add(layoutTarget.resolve(sub));
                    if (distinct) roots.add(memberLocalTarget.resolve(sub));
                }
            }
        }
        return roots;
    }

    /**
     * Returns the workspace root plus every declared module directory. Falls back to just {@code
     * [workspaceRoot]} when parsing fails or there are no modules (single-project).
     */
    private static List<Path> collectProjectDirs(Path workspaceRoot, List<String> warnings) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(workspaceRoot);
        Path rootToml = ManifestPaths.manifestIn(workspaceRoot);
        if (!Files.exists(rootToml)) return dirs;
        var info = ProjectInfos.orNull(workspaceRoot);
        if (info != null && info.workspaceRoot()) {
            for (Path moduleDir : resolveModuleDirs(workspaceRoot, info.moduleDirs(), warnings)) {
                if (Files.isDirectory(moduleDir)) dirs.add(moduleDir);
            }
        }
        return dirs;
    }

    /**
     * Module entries resolved against the workspace root; entries that escape it (absolute paths,
     * {@code ..}) are skipped with a warning — a hostile {@code [workspace].modules} entry must
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
    private static int clearProjectActionCache(Path projectDir, @Nullable Path cacheDirOverride) {
        if (!Files.isRegularFile(ManifestPaths.manifestIn(projectDir))) {
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
}
