// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.DiskUsage;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Progress;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.RichText;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk cache} — manage the <strong>cache tier</strong> under {@code $JK_CACHE_DIR}: every
 * entry {@link CacheTree} names, which is every entry the engine's retention pass bounds. A usage
 * report measures off that enum rather than a list here, so the client cannot come to disagree
 * with the engine about what the cache tier <em>is</em> — it did, for thirteen tiers, and a
 * local-fallback nuke left ten of them on disk.
 *
 * <p>A <em>nuke</em> is scoped to the root instead, which is the one description that cannot drift
 * from either the table or the sweep that reclaims what the table omits: it removes
 * {@code $JK_CACHE_DIR}, which is the path it printed under "Path to Delete".
 *
 * <p>Downloaded artifacts live under the store ({@code JK_STORE_DIR}) and the Maven local
 * repository; see {@code jk storage} / {@code jk repo search}.
 */
public final class CacheCommand extends GroupCommand {

    @Override
    public String name() {
        return "cache";
    }

    @Override
    public String description() {
        return "Manage the cache tier (action outputs)";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                new CacheDirCommand(),
                new CacheUsageCommand(),
                new CacheCleanCommand(),
                new CacheNukeCommand(),
                new CacheSearchRedirect());
    }

    // --- shared helpers (accessed by Cache*Command classes) ---------------------------

    static Path resolveCacheRoot(Path override) {
        return override != null ? override : JkDirs.cache();
    }

    record Stats(long files, long bytes) {
        static Stats from(DiskUsage.Stats s) {
            return new Stats(s.files(), s.bytes());
        }
    }

    /** Unique-byte size of one tree (hard links within the tree counted once). */
    static Stats statsOf(Path dir) throws IOException {
        return Stats.from(DiskUsage.of(dir));
    }

    /**
     * The Cache section of {@code jk status}: one footprint and two tier counts.
     *
     * <p>The footprint is the cache root walked as a single tree — the same measurement
     * {@link CacheNukeCommand#cacheRootStats} puts on the nuke confirm screen, so the size a user
     * reads before deciding to prune is the size {@code jk cache nuke} then frees. Naming the
     * tiers to add up instead is what made this number omit {@code hash-memo} and
     * {@code graal-reachability} outright; even {@link CacheTree#cached()} is total only for as
     * long as the enum stays total, and the directory is total by construction.
     *
     * <p>The store is deliberately not in it. {@code sha256/} and {@code repos/} resolve under
     * {@code JK_STORE_DIR} and survive a nuke, so adding them to a figure printed under a heading
     * that says "Cache" is how a 235&nbsp;MB reading preceded a nuke that freed 115.
     *
     * <p>Bytes are unique-inode within the walk: a cache-CAS blob hard-linked into
     * {@code actions/} is counted once, exactly as the nuke's own measurement counts it.
     */
    static SectionStats sectionStats(Path cacheRoot) throws IOException {
        return new SectionStats(
                statsOf(CacheTree.CACHE_CAS.under(cacheRoot)),
                statsOf(CacheTree.ACTIONS.under(cacheRoot)),
                statsOf(cacheRoot));
    }

    /**
     * Rows for {@code jk cache usage}. {@code total} is the action cache (key records plus the
     * cache CAS) — the bytes {@code max-cache-size-gb} bounds; category rows are a content breakdown
     * (they need not sum to total). {@code incremental} is outside the total: Zinc analysis state
     * has its own budget, so folding it in would make the utilization bar measure one tier against
     * another tier's line.
     */
    record CacheUsageStats(
            Stats classFiles,
            Stats testResults,
            Stats normalJars,
            Stats shadowJars,
            Stats minifiedJars,
            Stats nativeBins,
            Stats ociImages,
            Stats incremental,
            Stats stamps,
            Stats derived,
            Stats total) {
        long totalFiles() {
            return total.files;
        }

        long totalBytes() {
            return total.bytes;
        }
    }

    static CacheUsageStats cacheUsageFromAck(cc.jumpkick.engine.protocol.CacheInventoryAck ack) {
        return new CacheUsageStats(
                statFromAck(ack, "classFiles"),
                statFromAck(ack, "testResults"),
                statFromAck(ack, "normalJars"),
                statFromAck(ack, "shadowJars"),
                statFromAck(ack, "minifiedJars"),
                statFromAck(ack, "nativeBins"),
                statFromAck(ack, "ociImages"),
                statFromAck(ack, "incremental"),
                statFromAck(ack, "stamps"),
                statFromAck(ack, "derived"),
                new Stats(ack.totalFiles(), ack.totalBytes()));
    }

    static StoreUsageStats storeUsageFromAck(cc.jumpkick.engine.protocol.CacheInventoryAck ack) {
        return new StoreUsageStats(
                statFromAck(ack, "jars"),
                statFromAck(ack, "executables"),
                statFromAck(ack, "oci"),
                statFromAck(ack, "workers"),
                statFromAck(ack, "maven-local"));
    }

    private static Stats statFromAck(cc.jumpkick.engine.protocol.CacheInventoryAck ack, String name) {
        for (String row : ack.stats()) {
            String[] f = row.split("\\|", -1);
            if (f.length >= 3 && name.equals(f[0])) {
                try {
                    return new Stats(Long.parseLong(f[1]), Long.parseLong(f[2]));
                } catch (NumberFormatException ignored) {
                    return new Stats(0, 0);
                }
            }
        }
        return new Stats(0, 0);
    }

    /**
     * The three numbers {@code jk status} prints under "Cache", in row order.
     *
     * <p>{@code root} is not the sum of the two above it and is not meant to be: those name the
     * two tiers the section reports a count for, and the root holds every other tier plus whatever
     * the retention sweep has yet to reclaim. Summing rows is the shape this record had when it
     * silently dropped a tier.
     */
    record SectionStats(Stats cacheCas, Stats actions, Stats root) {}

    /** Rows for {@code jk storage usage} (store-tier only). */
    record StoreUsageStats(Stats jars, Stats executables, Stats oci, Stats workers, Stats mavenLocal) {
        StoreUsageStats(Stats jars, Stats executables, Stats oci, Stats workers) {
            this(jars, executables, oci, workers, new Stats(0, 0));
        }

        long totalFiles() {
            return jars.files + executables.files + oci.files + workers.files;
        }

        long totalBytes() {
            return jars.bytes + executables.bytes + oci.bytes + workers.bytes;
        }
    }

    /**
     * Relative "last cleaned" label from {@code .last-pruned} under {@code root} — pluralizes
     * correctly ({@code 1 day ago} vs {@code 3 days ago}).
     */
    static String lastPrunedLabel(Path root) {
        var stamp = cc.jumpkick.task.CachePruneScheduler.read(root);
        if (stamp.isEmpty()) return "never";
        long ageMs = System.currentTimeMillis() - stamp.get().millis();
        long days = ageMs / (24L * 60 * 60 * 1000);
        if (days == 0) return "today";
        if (days == 1) return "1 day ago";
        return days + " days ago";
    }

    static String fmtCount(long n) {
        return String.format("%,d", n);
    }

    /**
     * Render a hosted maintenance job's {@code prune-wait} event (see {@code
     * EngineProtocol.PRUNE_WAIT}). No-op when nothing is blocking (0 in-flight and no external prune)
     * so we never print "Waiting for 0 in-flight builds…".
     */
    static void printWait(Boolean external, int plans) {
        if (Boolean.TRUE.equals(external)) {
            CliOutput.out("Waiting for another jk process's cache clean to finish…");
            return;
        }
        if (plans <= 0) return;
        CliOutput.out("Waiting for " + plans + " in-flight build" + (plans == 1 ? "" : "s") + " to finish…");
    }

    static String fmtBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double v = bytes;
        int unit = -1;
        do {
            v /= 1024.0;
            unit++;
        } while (v >= 1024.0 && unit < units.length - 1);
        return String.format("%.1f %s", v, units[unit]);
    }

    /**
     * Compact size for tight table cells: {@code 545.3M}, {@code 30.8M}, {@code 1.2G} (1024-based).
     */
    static String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        String units = "KMGT";
        double v = bytes;
        int u = -1;
        do {
            v /= 1024.0;
            u++;
        } while (v >= 1024.0 && u < units.length() - 1);
        return String.format("%.1f%s", v, units.charAt(u));
    }

    /**
     * Full cache nuke. Shared by {@code jk cache nuke} and {@code jk self nuke --cache}, and the
     * one place that makes the promise both of their confirm screens print: {@code root} is
     * <strong>gone</strong> afterwards, the way {@code rm -rf} on the path under "Path to Delete"
     * would leave it. Not an empty tier skeleton, not a surviving directory holding whatever the
     * tier table does not name. The artifact store is never touched — {@code JkStores} resolves it
     * from {@code JK_STORE_DIR} and never under the cache root.
     *
     * @param skipConfirm when true, do not prompt (caller already confirmed)
     */
    static int runNuke(Path root, boolean dryRun, GlobalOptions global, boolean skipConfirm) throws IOException {
        return runNuke(root, dryRun, global, skipConfirm, false);
    }

    /**
     * @param localOnly skip the engine-hosted purge and wipe in-process. Set by {@code jk self
     *     nuke} multi-target runs: the fleet was just stopped, and the hosted path's
     *     {@code ensureRunning} would boot a fresh engine only for STATE deletion to pull the
     *     state dir (sockets included) out from under it.
     */
    static int runNuke(Path root, boolean dryRun, GlobalOptions global, boolean skipConfirm, boolean localOnly)
            throws IOException {
        NerdFontCaps nerdFont = cc.jumpkick.config.GlobalConfig.nerdFont();
        if (!Files.isDirectory(root)) {
            CommandWedge.printOk("Cache", "Nothing to nuke — cache directory does not exist.");
            return 0;
        }
        Stats stats = CacheNukeCommand.cacheRootStats(root);
        if (dryRun) {
            CommandWedge.printOk(
                    "Cache",
                    "Dry run: would remove " + fmtCount(stats.files()) + " files, " + fmtBytes(stats.bytes())
                            + ", and the cache directory itself.");
            return 0;
        }
        if (stats.files() == 0) {
            // An empty skeleton is still the directory the nuke promised to remove, and there is
            // nothing left in it worth a confirm or an idle boundary.
            removeCacheRoot(root);
            CommandWedge.printOk("Cache", "Nuked an empty cache directory — nothing to reclaim.");
            return 0;
        }
        if (!skipConfirm && !CacheNukeCommand.confirmNuke(root, stats)) {
            CommandWedge.envelopeStart();
            CliOutput.out(cc.jumpkick.cli.tui.JkWedge.chipLine(Glyphs.CROSS, "Cache", nerdFont, "Nuke aborted."));
            return 1;
        }
        // Prefer engine idle-boundary wipe; fall back to in-process delete only when no engine
        // is reachable (unit tests, engine down). A LIVE engine whose purge plan failed keeps
        // admitting builds — racing it with a client-side recursive delete is how a nuke ends
        // half-done on top of fresh writes.
        if (!localOnly) {
            try {
                long[] result = {stats.files(), stats.bytes()};
                ConsoleSpec spec = new ConsoleSpec(
                        "Cache",
                        r -> "Nuked " + fmtCount(result[0]) + " files, " + fmtBytes(result[1]) + " freed.",
                        r -> "Failed to nuke cache.",
                        true);
                BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
                var planResult = cc.jumpkick.cli.engine.EngineClient.runCacheMaintenance(
                        cc.jumpkick.engine.EnginePaths.current(),
                        new cc.jumpkick.cli.engine.EngineRequests.CacheMaintRequest("purge", root, false, false, null),
                        steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, "Cache"),
                        CacheCommand::printWait,
                        new cc.jumpkick.cli.engine.EngineRequests.CacheMaintSummary[1]);
                if (planResult.success()) {
                    // The engine emptied the tier at its idle boundary; the root and the
                    // .prune.lock it held there are ours to take now that the pass has finished.
                    removeCacheRoot(root);
                    return 0;
                }
                CommandWedge.printFail("Cache", "The engine's purge failed — not racing it with a local wipe.");
                return 1;
            } catch (IOException | RuntimeException ignored) {
                // engine unreachable — fall through to local wipe
            }
        }
        removeCacheRoot(root);
        CommandWedge.printOk(
                "Cache", "Nuked " + fmtCount(stats.files()) + " files, " + fmtBytes(stats.bytes()) + " freed.");
        return 0;
    }

    /**
     * {@code rm -rf} the cache root. The whole root, not a tier list: the engine's purge plan is
     * driven off {@link CacheTree} and the retention sweep reclaims what that table does not name,
     * so the only description of "everything jk caches" that cannot drift from either is the
     * directory itself. It is also the engine-unreachable fallback for
     * {@code CachePlans.purgeActionCache} — the last delete of a nuke either way.
     *
     * <p>Recreating the tier directories empty was the old shape, and it is what made a nuke that
     * printed {@code ~/.cache/jk} under "Path to Delete" leave {@code ~/.cache/jk} standing.
     */
    static void removeCacheRoot(Path root) throws IOException {
        cc.jumpkick.host.PathUtil.deleteRecursivelyOrThrow(root);
    }

    // --- subcommands defined here to access private helpers ----------------------

    public static final class CacheDirCommand implements CliCommand {
        @Override
        public String name() {
            return "dir";
        }

        @Override
        public String description() {
            return "Print the cache directory path";
        }

        @Override
        public List<Opt> options() {
            return List.of(cc.jumpkick.cli.CommonOpts.cacheDir());
        }

        /** A bare path for shell substitution — {@code du -sh "$(jk cache dir)"}. */
        @Override
        public boolean scriptMode(Invocation in) {
            return true;
        }

        @Override
        public int run(Invocation in) {
            CliOutput.out(String.valueOf(resolveCacheRoot(
                    in.value("cache-dir").map(cc.jumpkick.cli.CliPaths::abs).orElse(null))));
            return 0;
        }
    }

    /**
     * {@code jk cache usage} — cache-tier content breakdown (classes, tests, jars, natives, OCI,
     * stamps, …) and utilization vs {@code [cache] max-cache-size-gb}.
     */
    public static final class CacheUsageCommand implements CliCommand {
        @Override
        public String name() {
            return "usage";
        }

        /**
         * Pre-rename / pre-split spellings ({@code jk cache storage}, {@code jk cache info}) — see
         * docs/aliases.md.
         */
        @Override
        public List<String> aliases() {
            return List.of("storage", "info");
        }

        @Override
        public String description() {
            return "Show cache-tier size and utilization";
        }

        @Override
        public List<Opt> options() {
            return List.of(cc.jumpkick.cli.CommonOpts.cacheDir());
        }

        @Override
        public int run(Invocation in) throws IOException {
            Path root = resolveCacheRoot(
                    in.value("cache-dir").map(cc.jumpkick.cli.CliPaths::abs).orElse(null));
            Path actions = CacheTree.ACTIONS.under(root);
            Path cacheCas = CacheTree.CACHE_CAS.under(root);
            if (!Files.isDirectory(root) && !Files.isDirectory(actions) && !Files.isDirectory(cacheCas)) {
                CliOutput.out("Cache: " + cc.jumpkick.cli.PathDisplay.styledRaw(root) + " (not yet created)");
                return 0;
            }
            cc.jumpkick.engine.protocol.CacheInventoryAck ack;
            try {
                ack = cc.jumpkick.cli.engine.EngineClient.cacheInventory(
                        cc.jumpkick.engine.EnginePaths.current(), "usage", root, null, List.of(), List.of(), false);
            } catch (IOException e) {
                CommandWedge.printFail("Cache", String.valueOf(e.getMessage()));
                return 1;
            }
            if (ack.error() != null) {
                CommandWedge.printFail("Cache", ack.error());
                return 1;
            }
            CacheUsageStats s = cacheUsageFromAck(ack);
            var cfg = cc.jumpkick.config.JkCacheConfig.resolve();
            String lastCleaned = lastPrunedLabel(root);
            CommandWedge.envelopeStart();
            for (String line : renderCacheUsageTable(s, cfg, lastCleaned)) {
                CliOutput.out(line);
            }
            return 0;
        }
    }

    /** Project-scoped clear UI for {@code jk clean --force}. */
    static ConsoleSpec clearSpec(
            boolean dryRun, java.util.function.LongSupplier files, java.util.function.LongSupplier bytes) {
        return new ConsoleSpec(
                "Cache",
                r -> {
                    long f = Math.max(0, files.getAsLong());
                    long b = Math.max(0, bytes.getAsLong());
                    if (f == 0) {
                        return dryRun
                                ? "Dry run: nothing cached for this project."
                                : "Build cache already clear for this project.";
                    }
                    String noun = f == 1 ? "entry" : "entries";
                    return dryRun
                            ? "Dry run: would invalidate " + fmtCount(f) + " " + noun + ", " + fmtBytes(b)
                                    + " reclaimable."
                            : "Invalidated " + fmtCount(f) + " cache " + noun + ", " + fmtBytes(b) + " freed.";
                },
                r -> "Failed to clear the build cache.",
                true);
    }

    public static final class CacheCleanCommand implements CliCommand {
        @Override
        public String name() {
            return "clean";
        }

        @Override
        public List<String> aliases() {
            return List.of("prune"); // pre-rename
        }

        @Override
        public String description() {
            return "Reclaim cache space to the configured budget (oldest first)";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    cc.jumpkick.cli.CommonOpts.cacheDir(),
                    Opt.flag("Print what would be removed; touch nothing.", "--dry-run"));
        }

        @Override
        public int run(Invocation in) throws IOException {
            Path cacheDir =
                    in.value("cache-dir").map(cc.jumpkick.cli.CliPaths::abs).orElse(null);
            boolean dryRun = in.isSet("dry-run");
            GlobalOptions global = GlobalOptions.from(in);

            Path root = resolveCacheRoot(cacheDir);
            if (!Files.isDirectory(root)) {
                CliOutput.out("Nothing to clean — " + root + " does not exist.");
                return 0;
            }

            return runHosted(root, cacheDir == null, dryRun, global);
        }

        /** The engine-hosted foreground path: send the request, explain any wait, render the stream. */
        private static int runHosted(Path root, boolean defaultCacheDir, boolean dryRun, GlobalOptions global) {
            // Settled from the terminal plan-finish before the console listener renders the line.
            var summary = new cc.jumpkick.cli.engine.EngineRequests.CacheMaintSummary[1];
            ConsoleSpec spec = cleanSpec(
                    dryRun,
                    () -> summary[0] != null ? summary[0].files() : 0L,
                    () -> summary[0] != null ? summary[0].bytes() : 0L);
            BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
            cc.jumpkick.run.BuildPlanResult result;
            try {
                result = cc.jumpkick.cli.engine.EngineClient.runCacheMaintenance(
                        cc.jumpkick.engine.EnginePaths.current(),
                        new cc.jumpkick.cli.engine.EngineRequests.CacheMaintRequest(
                                "prune", root, dryRun, defaultCacheDir, null),
                        steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, "Cache"),
                        CacheCommand::printWait,
                        summary);
            } catch (IOException e) {
                CommandWedge.printFail("Cache", e.getMessage());
                return cc.jumpkick.model.command.Exit.SOFTWARE;
            }
            return result.success() ? 0 : 1;
        }

        /** The Cache chip spec; counts are read lazily, at result-line render time. */
        static ConsoleSpec cleanSpec(
                boolean dryRun, java.util.function.LongSupplier files, java.util.function.LongSupplier bytes) {
            return new ConsoleSpec(
                    "Cache",
                    r -> {
                        long f = Math.max(0, files.getAsLong());
                        long b = Math.max(0, bytes.getAsLong());
                        if (dryRun) {
                            if (f == 0) return "Dry run: nothing to clean up.";
                            return "Dry run: would remove "
                                    + f + " " + (f == 1 ? "file" : "files")
                                    + ", " + fmtBytes(b) + " reclaimable.";
                        }
                        if (f == 0) return "Finished cleaning cache. Nothing to clean up.";
                        return "Finished cleaning cache. "
                                + f + " " + (f == 1 ? "file" : "files")
                                + " removed, " + fmtBytes(b) + " reclaimed.";
                    },
                    r -> "Failed to clean cache.",
                    true);
        }
    }

    public static final class CacheNukeCommand implements CliCommand {
        @Override
        public String name() {
            return "nuke";
        }

        @Override
        public List<String> aliases() {
            return List.of("purge"); // pre-rename
        }

        @Override
        public String description() {
            return "Wipe the entire cache tier (asks to confirm)";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    cc.jumpkick.cli.CommonOpts.cacheDir(),
                    Opt.flag("Print what would be removed; touch nothing.", "--dry-run"));
        }

        @Override
        public int run(Invocation in) throws IOException {
            Path cacheDir =
                    in.value("cache-dir").map(cc.jumpkick.cli.CliPaths::abs).orElse(null);
            boolean dryRun = in.isSet("dry-run");
            GlobalOptions global = GlobalOptions.from(in);
            return CacheCommand.runNuke(resolveCacheRoot(cacheDir), dryRun, global, false);
        }

        /**
         * Footprint of what the nuke will delete — the whole cache root, measured as one tree
         * because that is exactly the unit {@link CacheCommand#removeCacheRoot} removes. Measuring
         * a tier list instead is how a root holding only entries the table does not name reported
         * "nothing to nuke" and survived. Byte sizes are exclusive (unique inode / fileKey) so
         * hard links are not counted twice: the cache CAS is hard-linked from the action tier.
         */
        static Stats cacheRootStats(Path root) throws IOException {
            DiskUsage.Stats stats = DiskUsage.of(root);
            return new Stats(stats.files(), stats.bytes());
        }

        /** Stern, default-to-no confirmation before removing the cache root. */
        static boolean confirmNuke(Path root, Stats stats) {
            Theme t = Theme.active();
            String bang = Theme.colorize(Glyphs.BANG, t.warning());
            CliOutput.out();
            CliOutput.out(
                    bang + " " + Theme.colorize("This permanently deletes the ENTIRE cache tier.", t.errorLabel()));
            CliOutput.out("  " + root);
            CliOutput.stdout().printf("  %s files, %s.%n", fmtCount(stats.files), fmtBytes(stats.bytes));
            CliOutput.out("  The directory itself goes, not only its contents.");
            CliOutput.out(
                    "  Artifact store (deps under JK_STORE_DIR) is kept. Rebuildable — the next build re-runs work.");
            return cc.jumpkick.cli.tui.Confirm.of(bang + " Nuke the cache tier?", false)
                    .ask();
        }
    }

    /**
     * Hidden post-split redirect stub: {@code jk cache search} forwards to {@code jk repo search}
     * (see docs/aliases.md). A one-line stderr note points at the canonical command; stdout stays
     * identical to {@code jk repo search}, so piped scripts keep working.
     */
    public static final class CacheSearchRedirect implements CliCommand {
        private final RepoCommand.RepoSearchCommand target = new RepoCommand.RepoSearchCommand();

        @Override
        public String name() {
            return "search";
        }

        @Override
        public boolean hidden() {
            return true;
        }

        @Override
        public String description() {
            return "Moved — use jk repo search";
        }

        @Override
        public List<Opt> options() {
            return target.options();
        }

        @Override
        public List<cc.jumpkick.model.command.Param> parameters() {
            return target.parameters();
        }

        @Override
        public int run(Invocation in) throws Exception {
            CliOutput.err(Theme.colorize(
                    "note: jk cache search moved to jk repo search",
                    Theme.active().dim()));
            return target.run(in);
        }
    }

    // ---- shared table chrome for jk cache / storage usage -----------------------------

    /**
     * Box table for {@code jk cache usage}: content classes + action-cache total; utilization vs
     * cache {@code max-cache-size-gb}; a separate line for the separately-budgeted Zinc analysis
     * state; last-cleaned footer.
     */
    static List<String> renderCacheUsageTable(
            CacheUsageStats s, cc.jumpkick.config.JkCacheConfig cfg, String lastCleaned) {
        String stampSize = s.stamps().bytes <= 0 ? "--" : fmtSize(s.stamps().bytes);
        String[][] rows = {
            {"Class Files", fmtCount(s.classFiles().files), fmtSize(s.classFiles().bytes)},
            {"Test Results", fmtCount(s.testResults().files), fmtSize(s.testResults().bytes)},
            {"Normal Jars", fmtCount(s.normalJars().files), fmtSize(s.normalJars().bytes)},
            {"Shadow Jars", fmtCount(s.shadowJars().files), fmtSize(s.shadowJars().bytes)},
            {"Minified Jars", fmtCount(s.minifiedJars().files), fmtSize(s.minifiedJars().bytes)},
            {"Native Bins", fmtCount(s.nativeBins().files), fmtSize(s.nativeBins().bytes)},
            {"OCI Images", fmtCount(s.ociImages().files), fmtSize(s.ociImages().bytes)},
            {"Format Stamps", fmtCount(s.stamps().files), stampSize},
        };
        List<String> out = new ArrayList<>(renderUsageTable(
                "Cache Storage", rows, s.totalFiles(), s.totalBytes(), cfg.maxCacheSizeBytes(), lastCleaned));
        Theme t = Theme.active();
        out.add("  Incremental state (own budget): "
                + Theme.colorize(
                        fmtCount(s.incremental().files) + " files · " + fmtSize(s.incremental().bytes) + " of "
                                + fmtSize(cfg.incrementalMaxSizeBytes()),
                        t.normalGray()));
        // No denominator: these are bounded by count or by supersession, so a percentage would be
        // measured against a number that is not their bound. Bytes are apparent, never allocated.
        out.add("  Derived caches (own retention): "
                + Theme.colorize(
                        fmtCount(s.derived().files) + " files · " + fmtSize(s.derived().bytes) + " apparent",
                        t.normalGray()));
        return out;
    }

    /**
     * Box table for {@code jk storage usage}: jar / native / OCI content and worker jars, plus the
     * last-cleaned footer. The store carries no budget, so there is no utilization row.
     */
    static List<String> renderStoreUsageTable(StoreUsageStats s, String lastCleaned) {
        String[][] rows = {
            {"Jar Files", fmtCount(s.jars().files), fmtSize(s.jars().bytes)},
            {"Native Bins", fmtCount(s.executables().files), fmtSize(s.executables().bytes)},
            {"OCI Images", fmtCount(s.oci().files), fmtSize(s.oci().bytes)},
            {"Worker JARs", fmtCount(s.workers().files), fmtSize(s.workers().bytes)},
        };
        List<String> out = renderUsageTable("Artifact Storage", rows, s.totalFiles(), s.totalBytes(), 0, lastCleaned);
        Theme t = Theme.active();
        out.add("  Maven local (not budgeted): "
                + Theme.colorize(
                        fmtCount(s.mavenLocal().files) + " files · " + fmtSize(s.mavenLocal().bytes), t.normalGray()));
        return out;
    }

    /**
     * Shared Element / File Count / Size box chrome for cache and store usage reports.
     *
     * @param maxBytes budget for the Utilization bar; {@code <= 0} omits the row entirely
     */
    private static List<String> renderUsageTable(
            String title, String[][] rows, long totalFiles, long totalBytes, long maxBytes, String lastCleaned) {
        Table table = new Table(title)
                .columns(
                        new Table.Column("Element"),
                        new Table.Column("File Count", Table.Align.RIGHT),
                        new Table.Column("Size", Table.Align.RIGHT));
        for (String[] r : rows) {
            table.row(styledMetricRow(r));
        }
        table.row(Table.Row.separator());
        table.row(styledMetricRow(new String[] {"Total", fmtCount(totalFiles), fmtSize(totalBytes)}));
        if (maxBytes > 0) {
            String util = utilizationContent(totalBytes, maxBytes);
            table.row(Table.Row.span(Table.Cell.of(RichText.ansi(util)).span(3)));
        }
        List<String> out = new ArrayList<>(table.render(RenderContext.current()));
        Theme t = Theme.active();
        out.add("  Last cleaned: "
                + Theme.colorize(lastCleaned, "never".equals(lastCleaned) ? t.warning() : t.normalGray()));
        return out;
    }

    private static Table.Row styledMetricRow(String[] r) {
        String name =
                Theme.active().isAnsi() ? Theme.colorize(r[0], Theme.active().brightWhite()) : r[0];
        return Table.Row.data(RichText.ansi(name), RichText.plain(r[1]), RichText.plain(r[2]));
    }

    private static String utilizationContent(long used, long max) {
        return "Utilization  "
                + new Progress(used, max).look(Progress.Look.TRACK).segments(24).render(RenderContext.current());
    }
}
