// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.tool.OrphanedToolEnvs;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * {@code jk storage} — manage the <strong>artifact store</strong> under {@code $JK_STORE_DIR}:
 * Maven-layout {@code repos/} and worker jars. Peer of {@code jk cache} (rebuildable action
 * outputs). The Maven local repository is reported but never wiped. Credentials stay under
 * {@code jk repo login}/{@code logout}. Bare {@code jk storage} prints this group's help (like
 * {@code jk cache}).
 *
 * <p>The artifact store is never size-pruned; {@code clean} reclaims garbage only.
 */
public final class StorageCommand extends GroupCommand {

    @Override
    public String name() {
        return "storage";
    }

    @Override
    public String description() {
        return "Manage the artifact store (repos, workers)";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                new StorageDirCommand(),
                new StorageUsageCommand(),
                new StorageCleanCommand(),
                new StorageNukeCommand());
    }

    /**
     * Wipe every child of the artifact store root — or, with {@code dryRun}, count what a wipe
     * would remove without deleting. Shared by {@code jk storage nuke} and {@code jk self nuke
     * --store}. Returns {@code [files, bytes]} (best-effort sizes).
     */
    public static long[] wipeStore(Path storeRoot, boolean dryRun) throws IOException {
        var ack = EngineClient.cacheInventory(
                EnginePaths.current(),
                "wipe-store",
                CacheCommand.resolveCacheRoot(null),
                storeRoot,
                List.of(),
                List.of(),
                dryRun);
        if (ack.error() != null) throw new IOException(ack.error());
        return new long[] {ack.files(), ack.bytes()};
    }

    /**
     * The engine-side wipe behind {@link #runNuke}. A seam because whether a real engine starts
     * depends on the machine, so the orphan sweep below it cannot be provoked in a test otherwise.
     */
    interface StoreWipe {
        /** {@link #wipeStore}'s contract: {@code [files, bytes]}, counted only under {@code dryRun}. */
        long[] wipe(Path storeRoot, boolean dryRun) throws IOException;
    }

    /**
     * Full store nuke with confirm / dry-run: {@code jk storage nuke}. The wipe orphans every
     * installed tool whose recorded classpath lies under the store — {@link OrphanedToolEnvs} is
     * the one rule, shared with {@code jk self nuke} — so their envs and launchers go with it, the
     * confirm names them and the settle line counts them; otherwise {@code jk tool list} keeps
     * naming a tool nothing can run.
     */
    public static int runNuke(boolean dryRun, boolean skipConfirm) throws IOException {
        return runNuke(dryRun, skipConfirm, true, StorageCommand::wipeStore);
    }

    /**
     * @param skipConfirm when true (multi-target self nuke already confirmed), do not prompt
     * @param sweepOrphans whether this command removes the tool envs and launchers the wipe
     *     orphans; false for the store leg of {@code jk self nuke --store}, whose own plan table
     *     carries and deletes them
     */
    static int runNuke(boolean dryRun, boolean skipConfirm, boolean sweepOrphans, StoreWipe wipe) throws IOException {
        Path storeRoot = JkStores.store();
        if (!Files.isDirectory(storeRoot)) {
            CommandWedge.printOk("Storage", "Nothing to nuke — store directory does not exist.");
            return 0;
        }
        JkDirs dirs = JkDirs.current();
        List<OrphanedToolEnvs.Orphan> orphans = sweepOrphans
                ? OrphanedToolEnvs.under(dirs.toolEnvsDir(), dirs.binDirectory(), List.of(storeRoot))
                : List.of();
        // Engine-side dry-run walk: the exact tree + counts the real wipe would remove.
        long[] preCount = wipe.wipe(storeRoot, true);
        CacheCommand.Stats pre = new CacheCommand.Stats(preCount[0], preCount[1]);
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
                            + orphansNote(orphans, "would remove")
                            + ".");
            return 0;
        }
        if (!skipConfirm && !confirmNuke(storeRoot, pre, orphans)) {
            CommandWedge.envelopeStart();
            CliOutput.out(JkWedge.chipLine(Glyphs.CROSS, "Storage", GlobalConfig.nerdFont(), "Nuke aborted."));
            return 1;
        }
        // Stop the fleet first — engines from other checkouts keep writing into the store
        // mid-wipe. The wipe request itself restarts one engine, which performs the delete
        // under the cache-maintenance exclusive lock.
        EngineFleet.stopAll(true);
        long[] wiped = wipe.wipe(storeRoot, false);
        List<OrphanedToolEnvs.Orphan> removed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (OrphanedToolEnvs.Orphan orphan : orphans) {
            try {
                OrphanedToolEnvs.remove(orphan);
                removed.add(orphan);
            } catch (IOException e) {
                failures.add(orphan.name() + " (" + e.getMessage() + ")");
            }
        }
        CommandWedge.printOk(
                "Storage",
                "Nuked " + CacheCommand.fmtCount(wiped[0]) + " files, " + CacheCommand.fmtBytes(wiped[1]) + " freed"
                        + orphansNote(removed, "removed") + ".");
        if (!failures.isEmpty()) {
            Theme t = Theme.active();
            CliOutput.err(Theme.colorize(Glyphs.BANG, t.warning())
                    + " Orphaned tools whose env or launcher could not be removed (jk tool uninstall <name>):");
            for (String f : failures) CliOutput.err("  " + f);
            return Exit.SOFTWARE;
        }
        return 0;
    }

    /**
     * The settle line's account of the tools the wipe orphans: {@code , and removed 2 orphaned
     * tools (widget, other; env and launcher — jk install restores them)}. Empty when none.
     */
    static String orphansNote(List<OrphanedToolEnvs.Orphan> orphans, String verb) {
        if (orphans.isEmpty()) return "";
        List<String> names = orphans.stream().map(OrphanedToolEnvs.Orphan::name).toList();
        return ", and " + verb + " " + orphans.size() + " orphaned tool" + (orphans.size() == 1 ? "" : "s") + " ("
                + String.join(", ", names) + "; env and launcher — jk install restores them)";
    }

    private static boolean confirmNuke(
            Path storeRoot, CacheCommand.Stats stats, List<OrphanedToolEnvs.Orphan> orphans) {
        Theme t = Theme.active();
        String bang = Theme.colorize(Glyphs.BANG, t.warning());
        CliOutput.out();
        CliOutput.out(
                bang + " " + Theme.colorize("This permanently deletes the ENTIRE artifact store.", t.errorLabel()));
        CliOutput.out("  " + storeRoot);
        CliOutput.stdout()
                .printf(
                        "  %s files, %s — Maven-layout repos, workers, and related store trees.%n",
                        CacheCommand.fmtCount(stats.files()), CacheCommand.fmtBytes(stats.bytes()));
        if (!orphans.isEmpty()) {
            // An installed tool execs jars under the store; its env and launcher go with them.
            List<String> names =
                    orphans.stream().map(OrphanedToolEnvs.Orphan::name).toList();
            CliOutput.out("  Orphans " + orphans.size() + " installed tool" + (orphans.size() == 1 ? "" : "s") + " ("
                    + String.join(", ", names) + "): env and launcher go too; jk install restores them.");
        }
        CliOutput.out("  Cache tier (action outputs) is kept. Credentials are kept (jk repo logout).");
        return Confirm.of(bang + " Nuke the artifact store?", false).ask();
    }

    // --- subcommands ----------------------------------------------------------------

    /** {@code jk storage dir} — print the artifact store root ({@code JK_STORE_DIR}). */
    public static final class StorageDirCommand implements CliCommand {
        @Override
        public String name() {
            return "dir";
        }

        @Override
        public String description() {
            return "Print the artifact store directory path";
        }

        /** A bare path for shell substitution — {@code ls "$(jk storage dir)"}. */
        @Override
        public boolean scriptMode(Invocation in) {
            return true;
        }

        @Override
        public int run(Invocation in) {
            CliOutput.out(String.valueOf(JkStores.store()));
            return 0;
        }
    }

    /** {@code jk storage usage} — artifact-store size table (jars, natives, OCI, worker jars). */
    public static final class StorageUsageCommand implements CliCommand {
        @Override
        public String name() {
            return "usage";
        }

        @Override
        public List<String> aliases() {
            // Muscle-memory names for the bare `jk storage` verb
            return List.of("status", "df", "info");
        }

        @Override
        public String description() {
            return "Show artifact-store size";
        }

        @Override
        public List<Opt> options() {
            return List.of();
        }

        @Override
        public int run(Invocation in) throws IOException {
            Path storeRoot = JkStores.store();
            // Last-cleaned stamp lives under the cache root (same file cache clean writes).
            Path cacheRoot = CacheCommand.resolveCacheRoot(null);
            if (!Files.isDirectory(storeRoot)) {
                CliOutput.out("Store directory: " + PathDisplay.styledRaw(storeRoot) + " (not yet created)");
                return 0;
            }
            CacheInventoryAck ack;
            try {
                ack = EngineClient.cacheInventory(
                        EnginePaths.current(), "store-usage", cacheRoot, storeRoot, List.of(), List.of(), false);
            } catch (IOException e) {
                CommandWedge.printFail("Storage", String.valueOf(e.getMessage()));
                return 1;
            }
            if (ack.error() != null) {
                CommandWedge.printFail("Storage", ack.error());
                return 1;
            }
            CacheCommand.StoreUsageStats s = CacheCommand.storeUsageFromAck(ack);
            String lastPruned = CacheCommand.lastPrunedLabel(cacheRoot);
            CommandWedge.envelopeStart();
            for (String line : CacheCommand.renderStoreUsageTable(s, lastPruned)) {
                CliOutput.out(line);
            }
            CacheInventoryAck repos;
            try {
                repos = EngineClient.cacheInventory(
                        EnginePaths.current(), "repos", cacheRoot, storeRoot, List.of(), List.of(), false);
            } catch (IOException e) {
                repos = CacheInventoryAck.error(String.valueOf(e.getMessage()));
            }
            for (String line : RepoStores.render(RepoStores.decode(repos), Theme.active())) {
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
            return "Reclaim leaked download temps (--workers: drop plugin workers)";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    Opt.flag("Print what would be removed; touch nothing.", "--dry-run"),
                    Opt.flag("Also drop installed plugin workers; next build re-fetches", "--workers"));
        }

        @Override
        public int run(Invocation in) {
            boolean dryRun = in.isSet("dry-run");
            GlobalOptions global = GlobalOptions.from(in);
            Path root = CacheCommand.resolveCacheRoot(null);

            if (in.isSet("workers")) {
                CacheInventoryAck dropped;
                try {
                    dropped = EngineClient.cacheInventory(
                            EnginePaths.current(),
                            "drop-workers",
                            root,
                            JkStores.store(),
                            List.of(),
                            List.of(),
                            dryRun);
                } catch (IOException e) {
                    CommandWedge.printFail("Storage", e.getMessage());
                    return Exit.SOFTWARE;
                }
                if (dropped.error() != null) {
                    CommandWedge.printFail("Storage", dropped.error());
                    return 1;
                }
                CommandWedge.printOk("Storage", droppedWorkersLine(dropped, dryRun));
            }

            var summary = new EngineRequests.CacheMaintSummary[1];
            ConsoleSpec spec = cleanSpec(
                    dryRun,
                    () -> summary[0] != null ? summary[0].files() : 0L,
                    () -> summary[0] != null ? summary[0].bytes() : 0L);
            BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
            BuildPlanResult result;
            try {
                result = EngineClient.runCacheMaintenance(
                        EnginePaths.current(),
                        new EngineRequests.CacheMaintRequest("sweep", root, dryRun, false, null),
                        steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, "Storage"),
                        CacheCommand::printWait,
                        summary);
            } catch (IOException e) {
                CommandWedge.printFail("Storage", e.getMessage());
                return Exit.SOFTWARE;
            }
            return result.success() ? 0 : 1;
        }

        /**
         * What {@code --workers} did: which workers went, from which store repo, and what that
         * reclaimed. The next fork of each re-fetches the published plugin and rebuilds its launch
         * classpath from the published POM, so the line says so.
         */
        static String droppedWorkersLine(CacheInventoryAck dropped, boolean dryRun) {
            if (dropped.lines().isEmpty()) {
                return dryRun
                        ? "Dry run: no installed plugin workers to drop."
                        : "No installed plugin workers to drop.";
            }
            List<String> named = new ArrayList<>();
            for (String row : dropped.lines()) {
                String[] f = row.split("\\|", -1);
                if (f.length >= 3) named.add(f[0] + " " + f[1] + " (" + f[2] + ")");
            }
            String what = named.size() + (named.size() == 1 ? " worker" : " workers") + ": " + String.join(", ", named);
            String size = dropped.files() + (dropped.files() == 1 ? " file" : " files") + ", "
                    + CacheCommand.fmtBytes(dropped.bytes());
            if (dryRun) return "Dry run: would drop " + what + " — " + size + " reclaimable.";
            return "Dropped " + what + " — " + size
                    + " reclaimed. The next build fetches the published plugin and rebuilds its launch classpath.";
        }

        static ConsoleSpec cleanSpec(boolean dryRun, LongSupplier files, LongSupplier bytes) {
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
            return List.of(Opt.flag("Print what would be removed; touch nothing.", "--dry-run"));
        }

        @Override
        public int run(Invocation in) throws IOException {
            boolean dryRun = in.isSet("dry-run");
            return runNuke(dryRun, false);
        }
    }
}
