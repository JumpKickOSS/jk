// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.PathUtil;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk storage} — manage the <strong>artifact store</strong> under {@code $JK_STORE_DIR}:
 * store CAS, {@code repos/} mirrors, and run logs. Peer of {@code jk cache} (rebuildable action
 * outputs). Credentials stay under {@code jk repo login}/{@code logout}.
 */
public final class StorageCommand extends GroupCommand {

    private final StorageStatusCommand status = new StorageStatusCommand();

    @Override
    public String name() {
        return "storage";
    }

    @Override
    public String description() {
        return "Manage the artifact store (deps CAS, repos)";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(status, new StorageCleanCommand(), new StorageNukeCommand());
    }

    /** Bare {@code jk storage} shows utilization (former {@code jk storage}). */
    @Override
    public CliCommand defaultSubcommand() {
        return status;
    }

    /**
     * Wipe every child of the artifact store root. Shared by {@code jk storage nuke} and {@code jk
     * self nuke --store}. Returns {@code [files, bytes]} removed (best-effort sizes).
     */
    public static long[] wipeStore(Path storeRoot) throws IOException {
        long[] stats = {0L, 0L};
        if (storeRoot == null || !Files.isDirectory(storeRoot)) return stats;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storeRoot)) {
            for (Path child : stream) {
                countTree(child, stats);
                PathUtil.deleteRecursivelyOrThrow(child);
            }
        }
        return stats;
    }

    private static void countTree(Path root, long[] stats) {
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                stats[0]++;
                try {
                    stats[1] += Files.size(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /**
     * Full store nuke with confirm / dry-run. Used by {@code jk storage nuke} and single-target
     * {@code jk self nuke --store}.
     *
     * @param skipConfirm when true (multi-target self nuke already confirmed), do not prompt
     */
    public static int runNuke(Path cacheRoot, boolean dryRun, boolean skipConfirm) throws IOException {
        Path storeRoot = JkStores.storeRootFor(cacheRoot);
        if (!Files.isDirectory(storeRoot)) {
            CommandWedge.printOk("Storage", "Nothing to nuke — store directory does not exist.");
            return 0;
        }
        CacheCommand.Stats pre = CacheCommand.statsOf(storeRoot);
        if (pre.files() == 0) {
            CommandWedge.printOk("Storage", "Nothing to nuke — the artifact store is empty.");
            return 0;
        }
        if (dryRun) {
            CommandWedge.printOk(
                    "Storage",
                    "Dry run: would remove "
                            + CacheCommand.fmtCount(pre.files())
                            + " files, "
                            + CacheCommand.fmtBytes(pre.bytes())
                            + ".");
            return 0;
        }
        if (!skipConfirm && !confirmNuke(storeRoot, pre)) {
            CommandWedge.envelopeStart();
            CliOutput.out(cc.jumpkick.cli.tui.BuildPlanWedge.chipLine(
                    Glyphs.CROSS, "Storage", cc.jumpkick.config.GlobalConfig.nerdfont(), "Nuke aborted."));
            return 1;
        }
        // Stop engines first — they read/write the store mid-build.
        try {
            for (var r : cc.jumpkick.cli.engine.EngineFleet.stopAll(true)) {
                if (r.outcome() == cc.jumpkick.cli.engine.EngineFleet.Outcome.SURVIVED) {
                    Theme t = Theme.active();
                    CliOutput.err(Theme.colorize(Glyphs.BANG, t.warning())
                            + " Engine pid "
                            + r.member().pid()
                            + " did not stop; nuking the store may leave it orphaned.");
                }
            }
        } catch (RuntimeException ignored) {
            // best-effort
        }
        long[] wiped = wipeStore(storeRoot);
        CommandWedge.printOk(
                "Storage",
                "Nuked "
                        + CacheCommand.fmtCount(wiped[0])
                        + " files, "
                        + CacheCommand.fmtBytes(wiped[1])
                        + " freed.");
        return 0;
    }

    private static boolean confirmNuke(Path storeRoot, CacheCommand.Stats stats) {
        Theme t = Theme.active();
        String bang = Theme.colorize(Glyphs.BANG, t.warning());
        CliOutput.out();
        CliOutput.out(bang + " " + Theme.colorize("This permanently deletes the ENTIRE artifact store.", t.errorLabel()));
        CliOutput.out("  " + storeRoot);
        CliOutput.stdout()
                .printf(
                        "  %s files, %s — CAS blobs, repo mirrors, and related store trees.%n",
                        CacheCommand.fmtCount(stats.files()), CacheCommand.fmtBytes(stats.bytes()));
        CliOutput.out("  Cache tier (action outputs) is kept. Credentials are kept (jk repo logout).");
        return cc.jumpkick.cli.tui.Confirm.of(bang + " Nuke the artifact store?", false).ask();
    }

    // --- subcommands ----------------------------------------------------------------

    public static final class StorageStatusCommand implements CliCommand {
        @Override
        public String name() {
            return "status";
        }

        @Override
        public List<String> aliases() {
            // Pre-split / muscle-memory names
            return List.of("df", "info");
        }

        @Override
        public String description() {
            return "Show artifact-store size and utilization";
        }

        @Override
        public List<Opt> options() {
            return List.of(cc.jumpkick.cli.CommonOpts.cacheDir());
        }

        @Override
        public int run(Invocation in) throws IOException {
            Path cacheRoot = CacheCommand.resolveCacheRoot(
                    in.value("cache-dir").map(Path::of).orElse(null));
            Path storeRoot = JkStores.storeRootFor(cacheRoot);
            if (!Files.isDirectory(cacheRoot) && !Files.isDirectory(storeRoot)) {
                CliOutput.out(
                        "Store directory: " + cc.jumpkick.cli.PathDisplay.styledRaw(storeRoot) + " (not yet created)");
                return 0;
            }
            CacheCommand.SectionStats s = CacheCommand.sectionStats(cacheRoot);
            var cfg = cc.jumpkick.config.JkCacheConfig.resolve();
            long maxBytes = cfg.maxStoreSizeBytes();
            String lastPruned = CacheCommand.lastPrunedLabel(cacheRoot);
            for (String line : CacheCommand.renderRepoStorageTable(
                    s.cas(), s.repos(), s.runs(), s.repoFiles(), s.repoBytes(), maxBytes, lastPruned)) {
                CliOutput.out(line);
            }
            return 0;
        }
    }

    public static final class StorageCleanCommand implements CliCommand {
        @Override
        public String name() {
            return "clean";
        }

        @Override
        public String description() {
            return "Sweep unreferenced CAS blobs and expired run logs";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    cc.jumpkick.cli.CommonOpts.cacheDir(),
                    Opt.flag("Print what would be removed; touch nothing.", "--dry-run"));
        }

        @Override
        public int run(Invocation in) {
            Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
            boolean dryRun = in.isSet("dry-run");
            GlobalOptions global = GlobalOptions.from(in);
            Path root = CacheCommand.resolveCacheRoot(cacheDir);

            var summary = new cc.jumpkick.cli.engine.EngineClient.CacheMaintSummary[1];
            ConsoleSpec spec = cleanSpec(
                    dryRun,
                    () -> summary[0] != null ? summary[0].files() : 0L,
                    () -> summary[0] != null ? summary[0].bytes() : 0L);
            BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
            cc.jumpkick.run.BuildPlanResult result;
            try {
                result = cc.jumpkick.cli.engine.EngineClient.runCacheMaintenance(
                        cc.jumpkick.engine.EnginePaths.current(),
                        new cc.jumpkick.cli.engine.EngineClient.CacheMaintRequest(
                                "sweep", root, Integer.MAX_VALUE, dryRun, true, false),
                        steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, "Storage"),
                        CacheCommand::printWait,
                        summary);
            } catch (IOException e) {
                CliOutput.err(CommandWedge.fail("Storage", e.getMessage()));
                return cc.jumpkick.model.command.Exit.SOFTWARE;
            }
            if (summary[0] != null) {
                CacheCommand.CacheCleanCommand.warnReachableEvicted(summary[0].reachableEvicted());
            }
            return result.success() ? 0 : 1;
        }

        static ConsoleSpec cleanSpec(
                boolean dryRun, java.util.function.LongSupplier files, java.util.function.LongSupplier bytes) {
            return new ConsoleSpec(
                    "Storage",
                    r -> {
                        long f = Math.max(0, files.getAsLong());
                        long b = Math.max(0, bytes.getAsLong());
                        if (dryRun) {
                            if (f == 0) return "Dry run: nothing to clean up.";
                            return "Dry run: would remove "
                                    + f
                                    + " "
                                    + (f == 1 ? "file" : "files")
                                    + ", "
                                    + CacheCommand.fmtBytes(b)
                                    + " reclaimable.";
                        }
                        if (f == 0) return "Finished cleaning store. Nothing to clean up.";
                        return "Finished cleaning store. "
                                + f
                                + " "
                                + (f == 1 ? "file" : "files")
                                + " removed, "
                                + CacheCommand.fmtBytes(b)
                                + " reclaimed.";
                    },
                    r -> "Failed to clean the store.",
                    true);
        }
    }

    public static final class StorageNukeCommand implements CliCommand {
        @Override
        public String name() {
            return "nuke";
        }

        @Override
        public String description() {
            return "Wipe the entire artifact store (asks to confirm)";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    cc.jumpkick.cli.CommonOpts.cacheDir(),
                    Opt.flag("Print what would be removed; touch nothing.", "--dry-run"));
        }

        @Override
        public int run(Invocation in) throws IOException {
            Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
            boolean dryRun = in.isSet("dry-run");
            Path root = CacheCommand.resolveCacheRoot(cacheDir);
            return runNuke(root, dryRun, false);
        }
    }
}
