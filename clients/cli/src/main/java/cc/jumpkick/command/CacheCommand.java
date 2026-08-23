// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.DiskUsage;
import cc.jumpkick.cache.JkStores;
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
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk cache} — manage the <strong>cache tier</strong> under {@code $JK_CACHE_DIR}: action
 * index ({@code actions/}), cache CAS ({@code sha256/}), and format stamps. Downloaded artifacts
 * live under the store ({@code JK_STORE_DIR}) and the Maven local repository; see {@code jk
 * storage} / {@code jk repo search}.
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

    /**
     * Unique-byte size of one tree (hard links within the tree counted once). Prefer
     * {@link #sectionStats} when summing CAS + repos so cross-tree hard links are not double-counted.
     */
    static Stats statsOf(Path dir) throws IOException {
        return Stats.from(DiskUsage.of(dir));
    }

    /**
     * Cache/store section sizes for {@code jk cache usage}, {@code jk storage usage}, {@code jk
     * status}, and dashboard parity.
     *
     * <p>Artifact CAS + {@code repos/} resolve via {@link JkStores} (store). Cache CAS ({@code
     * <cacheRoot>/sha256/}), action index, runs, and stamps stay under the cache root.
     *
     * <p>Byte sizes are exclusive across store sections (store CAS first) so a leftover hard link
     * between {@code sha256/} and {@code repos/} is not counted twice. Cache-tier {@code actions}
     * stats include the cache CAS blob tree; the cache CAS is copy-only.
     */
    static SectionStats sectionStats(Path cacheRoot) throws IOException {
        Path storeCas = JkStores.resolve(cacheRoot, "sha256");
        Path repos = JkStores.resolve(cacheRoot, "repos");
        Path actions = cacheRoot.resolve("actions");
        Path cacheCas = cacheRoot.resolve("sha256");
        Path runs = cacheRoot.resolve("runs");
        Path stamps = cacheRoot.resolve("format-stamps");
        // Store CAS first so leftover shared inodes with repos/ are not counted twice.
        DiskUsage.Stats[] parts = DiskUsage.exclusive(storeCas, repos, actions, runs, stamps);
        DiskUsage.Stats cacheCasStats = DiskUsage.of(cacheCas);
        Stats actionsPlusCacheCas =
                new Stats(parts[2].files() + cacheCasStats.files(), parts[2].bytes() + cacheCasStats.bytes());
        return new SectionStats(
                Stats.from(parts[0]),
                actionsPlusCacheCas,
                Stats.from(parts[1]),
                Stats.from(parts[3]),
                Stats.from(parts[4]));
    }

    /**
     * Cache-tier stats only (action index + cache CAS, format stamps) — no artifact-store walk.
     * Used by status / dashboard parity; {@code jk cache usage} reads the engine inventory ack.
     */
    static CacheTierStats cacheTierStats(Path cacheRoot) throws IOException {
        DiskUsage.Stats actions = DiskUsage.of(cacheRoot.resolve("actions"));
        DiskUsage.Stats cacheCas = DiskUsage.of(cacheRoot.resolve("sha256"));
        DiskUsage.Stats stamps = DiskUsage.of(cacheRoot.resolve("format-stamps"));
        return new CacheTierStats(
                new Stats(actions.files() + cacheCas.files(), actions.bytes() + cacheCas.bytes()), Stats.from(stamps));
    }

    /** Legacy combined cache-tier totals (action index + CAS + stamps). */
    record CacheTierStats(Stats actions, Stats stamps) {}

    /**
     * Rows for {@code jk cache usage}. {@code total} is the whole cache-root walk; category rows
     * are a content breakdown (they need not sum to total).
     */
    record CacheUsageStats(
            Stats classFiles,
            Stats testResults,
            Stats eventLogs,
            Stats normalJars,
            Stats shadowJars,
            Stats minifiedJars,
            Stats nativeBins,
            Stats ociImages,
            Stats stamps,
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
                statFromAck(ack, "eventLogs"),
                statFromAck(ack, "normalJars"),
                statFromAck(ack, "shadowJars"),
                statFromAck(ack, "minifiedJars"),
                statFromAck(ack, "nativeBins"),
                statFromAck(ack, "ociImages"),
                statFromAck(ack, "stamps"),
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

    /** Breakdown used by storage / status — fields ordered for the reports. */
    record SectionStats(Stats cas, Stats actions, Stats repos, Stats runs, Stats stamps) {
        long totalFiles() {
            return cas.files + actions.files + repos.files + runs.files + stamps.files;
        }

        long totalBytes() {
            return cas.bytes + actions.bytes + repos.bytes + runs.bytes + stamps.bytes;
        }

        /** Store-side footprint for {@code jk storage usage} (CAS + repos; run logs are state). */
        long repoFiles() {
            return cas.files + repos.files;
        }

        long repoBytes() {
            return cas.bytes + repos.bytes;
        }
    }

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
        Path stamp = root.resolve(cc.jumpkick.task.CachePruneScheduler.LAST_PRUNED_FILE);
        if (!Files.isRegularFile(stamp)) return "never";
        try {
            long millis = Long.parseLong(
                    Files.readString(stamp, StandardCharsets.UTF_8).trim());
            long ageMs = System.currentTimeMillis() - millis;
            long days = ageMs / (24L * 60 * 60 * 1000);
            if (days == 0) return "today";
            if (days == 1) return "1 day ago";
            return days + " days ago";
        } catch (Exception e) {
            return "unknown";
        }
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
     * Full cache-tier nuke. Shared by {@code jk cache nuke} and {@code jk self nuke --cache}.
     * Deletes {@code actions/}, {@code format-stamps/}, and cache {@code sha256/} (same trees as
     * the engine purge plan). Artifact store is never touched.
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
        Stats stats = CacheNukeCommand.actionCacheStats(root);
        if (stats.files() == 0) {
            CommandWedge.printOk("Cache", "Nothing to nuke — the cache tier is empty.");
            return 0;
        }
        if (dryRun) {
            CommandWedge.printOk(
                    "Cache",
                    "Dry run: would remove " + fmtCount(stats.files()) + " files, " + fmtBytes(stats.bytes()) + ".");
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
                        new cc.jumpkick.cli.engine.EngineRequests.CacheMaintRequest(
                                "purge", root, 0, false, false, false),
                        steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, "Cache"),
                        CacheCommand::printWait,
                        new cc.jumpkick.cli.engine.EngineRequests.CacheMaintSummary[1]);
                if (planResult.success()) return 0;
                CommandWedge.printFail("Cache", "The engine's purge failed — not racing it with a local wipe.");
                return 1;
            } catch (IOException | RuntimeException ignored) {
                // engine unreachable — fall through to local wipe
            }
        }
        wipeCacheTier(root);
        CommandWedge.printOk(
                "Cache", "Nuked " + fmtCount(stats.files()) + " files, " + fmtBytes(stats.bytes()) + " freed.");
        return 0;
    }

    /** Delete cache-tier trees under {@code root} (mirrors engine {@code purgeActionCache}). */
    static void wipeCacheTier(Path root) throws IOException {
        for (String tree : new String[] {"actions", "format-stamps", "sha256"}) {
            Path dir = root.resolve(tree);
            if (Files.isDirectory(dir)) {
                cc.jumpkick.util.PathUtil.deleteRecursivelyOrThrow(dir);
                Files.createDirectories(dir); // keep empty dirs so layout stays familiar
            }
        }
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
            Path actions = root.resolve("actions");
            Path cacheCas = root.resolve("sha256");
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
            long maxBytes = cfg.maxCacheSizeBytes();
            String lastCleaned = lastPrunedLabel(root);
            CommandWedge.envelopeStart();
            for (String line : renderCacheUsageTable(s, maxBytes, lastCleaned)) {
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
            return "Reclaim cache space (stale entries, Class-C heavy outputs)";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    cc.jumpkick.cli.CommonOpts.cacheDir(),
                    Opt.value("<days>", "Drop action-cache entries older than N days", "--older-than"),
                    Opt.flag("Print what would be removed; touch nothing.", "--dry-run"),
                    // Store-side flag moved to `jk storage clean`; kept hidden for back-compat.
                    Opt.flag("Sweep unreferenced CAS objects after clean", "--sweep")
                            .hide(),
                    Opt.flag("Internal: opportunistic prune.", "--background").hide());
        }

        @Override
        public int run(Invocation in) throws IOException {
            Path cacheDir =
                    in.value("cache-dir").map(cc.jumpkick.cli.CliPaths::abs).orElse(null);
            int olderThanDays = in.value("older-than").map(Integer::parseInt).orElse(30);
            boolean dryRun = in.isSet("dry-run");
            boolean sweep = in.isSet("sweep");
            // --background is parsed for script back-compat but has no distinct behavior since
            // the engine's idle-boundary prune replaced the detached spawner.
            GlobalOptions global = GlobalOptions.from(in);

            Path root = resolveCacheRoot(cacheDir);
            if (!Files.isDirectory(root)) {
                CliOutput.out("Nothing to clean — " + root + " does not exist.");
                return 0;
            }

            return runHosted(root, cacheDir == null, olderThanDays, dryRun, sweep, global);
        }

        /** The engine-hosted foreground path: send the request, explain any wait, render the stream. */
        private static int runHosted(
                Path root,
                boolean defaultCacheDir,
                int olderThanDays,
                boolean dryRun,
                boolean sweep,
                GlobalOptions global) {
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
                        // --sweep adds the CAS sweep but must not do LESS cleaning than plain
                        // clean: Class-C heavy outputs drop either way.
                        sweep
                                ? new cc.jumpkick.cli.engine.EngineRequests.CacheMaintRequest(
                                        "prune", root, olderThanDays, dryRun, true, defaultCacheDir, null, true)
                                : cc.jumpkick.cli.engine.EngineRequests.CacheMaintRequest.cacheClean(
                                        root, olderThanDays, dryRun, defaultCacheDir),
                        steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, "Cache"),
                        CacheCommand::printWait,
                        summary);
            } catch (IOException e) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Cache", e.getMessage());
                return cc.jumpkick.model.command.Exit.SOFTWARE;
            }
            if (summary[0] != null) warnReachableEvicted(summary[0].reachableEvicted());
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

        static void warnReachableEvicted(long evicted) {
            if (evicted <= 0) return;
            Theme pt = Theme.active();
            CliOutput.err(Theme.colorize(Glyphs.BANG, pt.warning())
                    + " "
                    + Theme.colorize(
                            "evicted "
                                    + evicted
                                    + " reachable objects to fit the budget — consider raising"
                                    + " cache.max-cache-size-gb (or JK_MAX_CACHE_SIZE_GB).",
                            pt.settled()));
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
         * Cache-tier footprint the purge will delete: {@code actions/}, {@code format-stamps/}, and
         * cache {@code sha256/} (mirrors {@code CachePlans.purgeActionCache}). Collocated
         * {@code repos/} and {@code runs/} are excluded — artifact store stays under {@code
         * JK_STORE_DIR}. Byte sizes are exclusive across those trees (unique inode / fileKey) so
         * hard links are not counted twice.
         */
        static Stats actionCacheStats(Path root) throws IOException {
            DiskUsage.Stats[] parts =
                    DiskUsage.exclusive(root.resolve("actions"), root.resolve("format-stamps"), root.resolve("sha256"));
            return new Stats(DiskUsage.totalFiles(parts), DiskUsage.totalBytes(parts));
        }

        /** Stern, default-to-no confirmation before wiping the cache tier. */
        static boolean confirmNuke(Path root, Stats stats) {
            Theme t = Theme.active();
            String bang = Theme.colorize(Glyphs.BANG, t.warning());
            CliOutput.out();
            CliOutput.out(
                    bang + " " + Theme.colorize("This permanently deletes the ENTIRE cache tier.", t.errorLabel()));
            CliOutput.out("  " + root);
            CliOutput.stdout()
                    .printf(
                            "  %s files, %s — action index, cache CAS (sha256/), and format stamps.%n",
                            fmtCount(stats.files), fmtBytes(stats.bytes));
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
     * Box table for {@code jk cache usage}: content classes + full-tree total; utilization vs
     * cache {@code max-cache-size-gb}; last-cleaned footer.
     */
    static List<String> renderCacheUsageTable(CacheUsageStats s, long maxBytes, String lastCleaned) {
        String stampSize = s.stamps().bytes <= 0 ? "--" : fmtSize(s.stamps().bytes);
        String[][] rows = {
            {"Class Files", fmtCount(s.classFiles().files), fmtSize(s.classFiles().bytes)},
            {"Test Results", fmtCount(s.testResults().files), fmtSize(s.testResults().bytes)},
            {"Event Logs", fmtCount(s.eventLogs().files), fmtSize(s.eventLogs().bytes)},
            {"Normal Jars", fmtCount(s.normalJars().files), fmtSize(s.normalJars().bytes)},
            {"Shadow Jars", fmtCount(s.shadowJars().files), fmtSize(s.shadowJars().bytes)},
            {"Minified Jars", fmtCount(s.minifiedJars().files), fmtSize(s.minifiedJars().bytes)},
            {"Native Bins", fmtCount(s.nativeBins().files), fmtSize(s.nativeBins().bytes)},
            {"OCI Images", fmtCount(s.ociImages().files), fmtSize(s.ociImages().bytes)},
            {"Format Stamps", fmtCount(s.stamps().files), stampSize},
        };
        return renderUsageTable("Cache Storage", rows, s.totalFiles(), s.totalBytes(), maxBytes, lastCleaned);
    }

    /**
     * Box table for {@code jk storage usage}: jar / native / OCI content and worker jars;
     * utilization vs store {@code max-store-size-gb}; last-cleaned footer.
     */
    static List<String> renderStoreUsageTable(StoreUsageStats s, long maxBytes, String lastCleaned) {
        String[][] rows = {
            {"Jar Files", fmtCount(s.jars().files), fmtSize(s.jars().bytes)},
            {"Native Bins", fmtCount(s.executables().files), fmtSize(s.executables().bytes)},
            {"OCI Images", fmtCount(s.oci().files), fmtSize(s.oci().bytes)},
            {"Worker JARs", fmtCount(s.workers().files), fmtSize(s.workers().bytes)},
        };
        List<String> out =
                renderUsageTable("Artifact Storage", rows, s.totalFiles(), s.totalBytes(), maxBytes, lastCleaned);
        Theme t = Theme.active();
        out.add("  Maven local (not budgeted): "
                + Theme.colorize(
                        fmtCount(s.mavenLocal().files) + " files · " + fmtSize(s.mavenLocal().bytes), t.normalGray()));
        return out;
    }

    /** Shared Element / File Count / Size box chrome for cache and store usage reports. */
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
        String util = utilizationContent(totalBytes, maxBytes);
        table.row(Table.Row.span(Table.Cell.of(RichText.ansi(util)).span(3)));
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
