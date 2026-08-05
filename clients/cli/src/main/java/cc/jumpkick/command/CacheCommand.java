// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.DiskUsage;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
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
 * index ({@code actions/}), cache CAS ({@code sha256/}), and format stamps. Long-lived artifact
 * CAS and Maven/repo mirrors live under the store ({@code JK_STORE_DIR}); see {@code jk repo
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
                new CacheStorageCommand(),
                new CacheClearCommand(),
                new CachePruneCommand(),
                new CachePurgeCommand(),
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
     * Cache/store section sizes for {@code jk cache storage}, {@code jk repo storage}, {@code jk
     * status}, and dashboard parity.
     *
     * <p>Artifact CAS + {@code repos/} resolve via {@link JkStores} (store). Cache CAS ({@code
     * <cacheRoot>/sha256/}), action index, runs, and stamps stay under the cache root.
     *
     * <p>Byte sizes are exclusive across store sections (CAS first), so hard-linked repo jars do not
     * inflate "Size on Disk" or the utilization bar. Cache-tier {@code actions} stats include the
     * cache CAS blob tree; plain (non-exclusive) counting there is exact because the cache CAS is
     * copy-only — no blob is ever hard-linked across tiers (verified for JK-1525).
     */
    static SectionStats sectionStats(Path cacheRoot) throws IOException {
        Path storeCas = JkStores.resolve(cacheRoot, "sha256");
        Path repos = JkStores.resolve(cacheRoot, "repos");
        Path actions = cacheRoot.resolve("actions");
        Path cacheCas = cacheRoot.resolve("sha256");
        Path runs = cacheRoot.resolve("runs");
        Path stamps = cacheRoot.resolve("format-stamps");
        // Store CAS first so hard-linked repos/ do not double-count; cache trees are exclusive of store.
        DiskUsage.Stats[] parts = DiskUsage.exclusive(storeCas, repos, actions, runs, stamps);
        DiskUsage.Stats cacheCasStats = DiskUsage.of(cacheCas);
        Stats actionsPlusCacheCas = new Stats(
                parts[2].files() + cacheCasStats.files(), parts[2].bytes() + cacheCasStats.bytes());
        return new SectionStats(
                Stats.from(parts[0]),
                actionsPlusCacheCas,
                Stats.from(parts[1]),
                Stats.from(parts[3]),
                Stats.from(parts[4]));
    }

    /**
     * Cache-tier stats only (action index + cache CAS, format stamps) — no artifact-store walk.
     * The cache CAS is copy-only ({@code Cas.putFile} on both store and restore; only the store
     * CAS ever hard-links, via {@code MavenRepo}), so no cross-tier links exist and plain sizes
     * are exact. {@code jk cache storage} displays exactly these two numbers; walking the whole
     * store CAS + repos for them added store-proportional latency in the slim CLI (JK-1525).
     */
    static CacheTierStats cacheTierStats(Path cacheRoot) throws IOException {
        DiskUsage.Stats actions = DiskUsage.of(cacheRoot.resolve("actions"));
        DiskUsage.Stats cacheCas = DiskUsage.of(cacheRoot.resolve("sha256"));
        DiskUsage.Stats stamps = DiskUsage.of(cacheRoot.resolve("format-stamps"));
        return new CacheTierStats(
                new Stats(actions.files() + cacheCas.files(), actions.bytes() + cacheCas.bytes()),
                Stats.from(stamps));
    }

    /** Cache-tier breakdown for {@code jk cache storage} ({@code actions} includes the cache CAS). */
    record CacheTierStats(Stats actions, Stats stamps) {}

    /** Breakdown used by storage / status — fields ordered for the reports. */
    record SectionStats(Stats cas, Stats actions, Stats repos, Stats runs, Stats stamps) {
        long totalFiles() {
            return cas.files + actions.files + repos.files + runs.files + stamps.files;
        }

        long totalBytes() {
            return cas.bytes + actions.bytes + repos.bytes + runs.bytes + stamps.bytes;
        }

        /** Store-side footprint for {@code jk repo storage} (CAS + worker jars + run logs). */
        long repoFiles() {
            return cas.files + repos.files + runs.files;
        }

        long repoBytes() {
            return cas.bytes + repos.bytes + runs.bytes;
        }
    }

    /** Relative "last pruned" label from {@code .last-pruned} under {@code root}. */
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
    static void printWait(Boolean external, int pipelines) {
        if (Boolean.TRUE.equals(external)) {
            CliOutput.out("Waiting for another jk process's cache prune to finish…");
            return;
        }
        if (pipelines <= 0) return;
        CliOutput.out("Waiting for " + pipelines + " in-flight build" + (pipelines == 1 ? "" : "s") + " to finish…");
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

        @Override
        public int run(Invocation in) {
            CliOutput.out(String.valueOf(
                    resolveCacheRoot(in.value("cache-dir").map(Path::of).orElse(null))));
            return 0;
        }
    }

    /**
     * {@code jk cache storage} — cache-tier footprint (action index + cache CAS + format stamps;
     * utilization vs {@code [cache] max-cache-size-mb}, last pruned).
     */
    public static final class CacheStorageCommand implements CliCommand {
        /**
         * Widest label (<code>Storage Size</code>) <em>plus its colon</em> — the format is applied
         * to {@code label + ":"}, so the field must count the colon or the widest row's value
         * lands one column right of the rest (JK-1441).
         */
        private static final int LABEL_FIELD = "Storage Size".length() + 1;

        @Override
        public String name() {
            return "storage";
        }

        /** Hidden pre-split name ({@code jk cache info}) — see docs/aliases.md. */
        @Override
        public List<String> aliases() {
            return List.of("info");
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
            Path root = resolveCacheRoot(in.value("cache-dir").map(Path::of).orElse(null));
            Path actions = root.resolve("actions");
            Path cacheCas = root.resolve("sha256");
            if (!Files.isDirectory(root)
                    && !Files.isDirectory(actions)
                    && !Files.isDirectory(cacheCas)) {
                CliOutput.out("Cache: " + cc.jumpkick.cli.PathDisplay.styledRaw(root) + " (not yet created)");
                return 0;
            }
            // Cache tier: action index + cache CAS + format stamps — never walks the store (JK-1525).
            CacheTierStats s = cacheTierStats(root);
            long files = s.actions().files + s.stamps().files;
            long bytes = s.actions().bytes + s.stamps().bytes;
            var cfg = cc.jumpkick.config.JkCacheConfig.resolve();
            long maxBytes = cfg.maxCacheSizeBytes();
            String lastPruned = lastPrunedLabel(root);

            CommandWedge.envelopeStart();
            CliOutput.out(CommandWedge.menu("Cache Storage"));
            detail("File Count", Long.toString(files));
            detail("Storage Size", fmtBytes(bytes));
            detail("Utilization", utilizationText(bytes, maxBytes));
            Theme t = Theme.active();
            detail(
                    "Last Pruned",
                    Theme.colorize(lastPruned, "never".equals(lastPruned) ? t.warning() : t.normalGray()));
            return 0;
        }

        /** {@code  • Label:  value} with right-padded labels. */
        private static void detail(String label, String value) {
            CliOutput.out(" " + Theme.colorize(Glyphs.bullet(), Theme.active().dim()) + " "
                    + String.format("%-" + LABEL_FIELD + "s", label + ":") + " " + value);
        }

        /** Compact utilization bar + percent for a bullet line. */
        static String utilizationText(long used, long max) {
            Theme t = Theme.active();
            int pct = (int) Math.round(cc.jumpkick.cli.tui.ProgressBar.fraction(used, max) * 100);
            String bar = cc.jumpkick.cli.tui.ProgressBar.renderBar(
                    used, max, 24, t.bright(t.planBadgeColor()), t.darkGray());
            return bar + "  " + pct + "%";
        }
    }

    /**
     * {@code jk cache clear} — invalidate action-cache entries for this project and its workspace.
     * Engine-hosted at an idle boundary; CAS blobs survive until {@code prune --sweep}.
     */
    public static final class CacheClearCommand implements CliCommand {
        @Override
        public String name() {
            return "clear";
        }

        @Override
        public String description() {
            return "Invalidate this project's build cache (project + workspace)";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    Opt.flag("Print what would be invalidated; touch nothing.", "--dry-run"),
                    cc.jumpkick.cli.CommonOpts.cacheDir());
        }

        @Override
        public int run(Invocation in) throws IOException {
            GlobalOptions global = GlobalOptions.from(in);
            Path projectDir = global.workingDir();
            Path manifest = projectDir.resolve("jk.toml");
            boolean nerdfont = cc.jumpkick.config.GlobalConfig.nerdfont();
            if (!Files.isRegularFile(manifest)) {
                Theme t = Theme.active();
                CliOutput.err(Theme.colorize(Glyphs.CROSS, t.error())
                        + " Not a jk project — no "
                        + Theme.colorize("jk.toml", t.warning())
                        + " in "
                        + cc.jumpkick.cli.PathDisplay.styledRaw(projectDir)
                        + ".");
                CliOutput.err("  Run this from a project directory; it clears that project and its workspace.");
                return cc.jumpkick.model.command.Exit.CONFIG;
            }
            // Match BuildCommand realpath so action-cache tags/INPUT prefixes align (macOS /var etc.).
            try {
                projectDir = projectDir.toRealPath();
            } catch (IOException ignored) {
                projectDir = projectDir.toAbsolutePath().normalize();
            }

            boolean dryRun = in.isSet("dry-run");
            Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
            Path root = resolveCacheRoot(cacheDir);

            if (!dryRun && !confirmClear()) {
                CliOutput.out(
                        cc.jumpkick.cli.tui.PipelineWedge.chipLine(Glyphs.CROSS, "Cache", nerdfont, "Clear aborted."));
                return 1;
            }

            PipelineConsole.Mode mode = PipelineConsole.modeFor(global);

            // Counts settle from the terminal pipeline-finish before the console listener renders.
            var summary = new cc.jumpkick.cli.engine.EngineClient.CacheMaintSummary[1];
            ConsoleSpec spec = clearSpec(
                    dryRun,
                    () -> summary[0] != null ? summary[0].files() : 0L,
                    () -> summary[0] != null ? summary[0].bytes() : 0L);
            cc.jumpkick.run.PipelineResult result;
            try {
                result = cc.jumpkick.cli.engine.EngineClient.runCacheMaintenance(
                        cc.jumpkick.engine.EnginePaths.current(),
                        new cc.jumpkick.cli.engine.EngineClient.CacheMaintRequest(
                                "clear", root, 0, dryRun, false, null, false, projectDir),
                        steps -> PipelineConsole.chooseConsoleListener(steps, mode, spec, "Cache"),
                        CacheCommand::printWait,
                        summary);
            } catch (IOException e) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Cache", e.getMessage()));
                return cc.jumpkick.model.command.Exit.SOFTWARE;
            }
            return result.success() ? 0 : 1;
        }

        /** The Cache chip spec; counts are read lazily, at result-line render time. */
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

        /** Default-to-yes confirmation ({@code [Y/n]}) — the mutation is recoverable (a rebuild). */
        private static boolean confirmClear() {
            Theme t = Theme.active();
            CliOutput.out("This invalidates the build cache for this project and its workspace;");
            CliOutput.out("  the next build re-runs from scratch. CAS blobs are kept.");
            return cc.jumpkick.cli.tui.Confirm.of(
                            Theme.colorize(Glyphs.BANG, t.warning()) + " Clear the build cache?", true)
                    .ask();
        }
    }

    /**
     * {@code jk cache prune} — engine-hosted idle-boundary job (waits for in-flight pipelines;
     * holds {@code .prune.lock}). Detached {@code --background} child is for engine-less tests.
     */
    public static final class CachePruneCommand implements CliCommand {
        @Override
        public String name() {
            return "prune";
        }

        @Override
        public String description() {
            return "Remove stale action-cache entries and leftover temp files";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    cc.jumpkick.cli.CommonOpts.cacheDir(),
                    Opt.value(
                            "<days>",
                            "Drop action-cache entries older than N days",
                            "--older-than"),
                    Opt.flag("Print what would be removed; touch nothing.", "--dry-run"),
                    // Store-side flags moved to `jk repo prune` (JK-1435); kept hidden for
                    // back-compat — see docs/aliases.md.
                    Opt.flag("Sweep unreferenced CAS objects after prune", "--sweep")
                            .hide(),
                    Opt.value("<size>", "Cap CAS size (e.g. 20G); implies --sweep", "--max-size")
                            .hide(),
                    Opt.flag("Internal: opportunistic prune.", "--background").hide());
        }

        @Override
        public int run(Invocation in) throws IOException {
            Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
            int olderThanDays = in.value("older-than").map(Integer::parseInt).orElse(30);
            boolean dryRun = in.isSet("dry-run");
            boolean sweep = in.isSet("sweep");
            String maxSize = in.value("max-size").orElse(null);
            boolean background = in.isSet("background");
            GlobalOptions global = GlobalOptions.from(in);

            Path root = resolveCacheRoot(cacheDir);
            if (!Files.isDirectory(root)) {
                CliOutput.out("Nothing to prune — " + root + " does not exist.");
                return 0;
            }

            return runHosted(root, cacheDir == null, olderThanDays, dryRun, sweep, maxSize, global);
        }

        /** The engine-hosted foreground path: send the request, explain any wait, render the stream. */
        private static int runHosted(
                Path root,
                boolean defaultCacheDir,
                int olderThanDays,
                boolean dryRun,
                boolean sweep,
                String maxSize,
                GlobalOptions global) {
            // Settled from the terminal pipeline-finish before the console listener renders the line.
            var summary = new cc.jumpkick.cli.engine.EngineClient.CacheMaintSummary[1];
            ConsoleSpec spec = pruneSpec(
                    dryRun,
                    () -> summary[0] != null ? summary[0].files() : 0L,
                    () -> summary[0] != null ? summary[0].bytes() : 0L);
            PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
            cc.jumpkick.run.PipelineResult result;
            try {
                result = cc.jumpkick.cli.engine.EngineClient.runCacheMaintenance(
                        cc.jumpkick.engine.EnginePaths.current(),
                        new cc.jumpkick.cli.engine.EngineClient.CacheMaintRequest(
                                "prune", root, olderThanDays, dryRun, sweep, maxSize, defaultCacheDir),
                        steps -> PipelineConsole.chooseConsoleListener(steps, mode, spec, "Cache"),
                        CacheCommand::printWait,
                        summary);
            } catch (IOException e) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Cache", e.getMessage()));
                return cc.jumpkick.model.command.Exit.SOFTWARE;
            }
            if (summary[0] != null) warnReachableEvicted(summary[0].reachableEvicted());
            return result.success() ? 0 : 1;
        }

        /** The Cache chip spec; counts are read lazily, at result-line render time. */
        static ConsoleSpec pruneSpec(
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
                        if (f == 0) return "Finished pruning cache. Nothing to clean up.";
                        return "Finished pruning cache. "
                                + f + " " + (f == 1 ? "file" : "files")
                                + " removed, " + fmtBytes(b) + " reclaimed.";
                    },
                    r -> "Failed to prune cache.",
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
                                    + " reachable objects to fit the budget — consider raising --max-size.",
                            pt.settled()));
        }
    }

    public static final class CachePurgeCommand implements CliCommand {
        @Override
        public String name() {
            return "purge";
        }

        @Override
        public String description() {
            return "Delete the entire cache tier (asks to confirm)";
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
            GlobalOptions global = GlobalOptions.from(in);
            boolean nerdfont = cc.jumpkick.config.GlobalConfig.nerdfont();
            Path root = resolveCacheRoot(cacheDir);
            if (!Files.isDirectory(root)) {
                cc.jumpkick.cli.tui.CommandWedge.printOk("Cache", "Nothing to purge — cache directory does not exist.");
                return 0;
            }
            Stats stats = actionCacheStats(root);
            if (stats.files == 0) {
                cc.jumpkick.cli.tui.CommandWedge.printOk("Cache", "Nothing to purge — the cache tier is empty.");
                return 0;
            }
            if (dryRun) {
                cc.jumpkick.cli.tui.CommandWedge.printOk(
                        "Cache",
                        "Dry run: would remove " + fmtCount(stats.files) + " files, " + fmtBytes(stats.bytes) + ".");
                return 0;
            }
            if (!confirmPurge(root, stats)) {
                cc.jumpkick.cli.tui.CommandWedge.envelopeStart();
                CliOutput.out(cc.jumpkick.cli.tui.PipelineWedge.chipLine(
                        cc.jumpkick.cli.tui.Glyphs.CROSS, "Cache", nerdfont, "Purge aborted."));
                return 1;
            }
            // Confirm/dry-run/stats stay client-side; the delete is engine-hosted at an idle boundary.
            long[] result = {stats.files, stats.bytes};
            ConsoleSpec spec = new ConsoleSpec(
                    "Cache",
                    r -> "Purged " + fmtCount(result[0]) + " files, " + fmtBytes(result[1]) + " freed.",
                    r -> "Failed to purge cache.",
                    true);
            PipelineConsole.Mode mode = PipelineConsole.modeFor(global);

            cc.jumpkick.run.PipelineResult pipelineResult;
            try {
                pipelineResult = cc.jumpkick.cli.engine.EngineClient.runCacheMaintenance(
                        cc.jumpkick.engine.EnginePaths.current(),
                        new cc.jumpkick.cli.engine.EngineClient.CacheMaintRequest(
                                "purge", root, 0, false, false, null, false),
                        steps -> PipelineConsole.chooseConsoleListener(steps, mode, spec, "Cache"),
                        CacheCommand::printWait,
                        new cc.jumpkick.cli.engine.EngineClient.CacheMaintSummary[1]);
            } catch (IOException e) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Cache", e.getMessage()));
                return cc.jumpkick.model.command.Exit.SOFTWARE;
            }
            return pipelineResult.success() ? 0 : 1;
        }

        /**
         * Cache-tier footprint the purge will delete: {@code actions/}, {@code format-stamps/}, and
         * cache {@code sha256/} (mirrors {@code CachePipelines.purgeActionCache}). Collocated
         * {@code repos/} and {@code runs/} are excluded — artifact store stays under {@code
         * JK_STORE_DIR}.
         */
        static Stats actionCacheStats(Path root) throws IOException {
            long files = 0;
            long bytes = 0;
            for (String tree : new String[] {"actions", "format-stamps", "sha256"}) {
                Path dir = root.resolve(tree);
                if (!Files.isDirectory(dir)) continue;
                Stats s = statsOf(dir);
                files += s.files;
                bytes += s.bytes;
            }
            return new Stats(files, bytes);
        }

        /** Stern, default-to-no confirmation before wiping the cache tier. */
        private static boolean confirmPurge(Path root, Stats stats) {
            Theme t = Theme.active();
            String bang = Theme.colorize(Glyphs.BANG, t.warning());
            CliOutput.out();
            CliOutput.out(bang
                    + " "
                    + Theme.colorize("This permanently deletes the ENTIRE cache tier.", t.errorLabel()));
            CliOutput.out("  " + root);
            CliOutput.stdout()
                    .printf(
                            "  %s files, %s — action index, cache CAS (sha256/), and format stamps.%n",
                            fmtCount(stats.files), fmtBytes(stats.bytes));
            CliOutput.out(
                    "  Artifact store (deps under JK_STORE_DIR) is kept. Rebuildable — the next build re-runs work.");
            return cc.jumpkick.cli.tui.Confirm.of(bang + " Purge the cache tier?", false).ask();
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
                    "note: jk cache search moved to jk repo search", Theme.active().dim()));
            return target.run(in);
        }
    }

    // ---- shared table chrome for jk repo storage -------------------------------------------

    private static final String[] REPO_STORAGE_HEADERS = {"Element", "File Count", "Size"};

    /**
     * Box table for {@code jk repo storage}: CAS + worker jars + run logs, utilization vs store
     * {@code max-store-size-mb}, last-pruned footer.
     */
    static List<String> renderRepoStorageTable(
            Stats cas, Stats repos, Stats runs, long totalFiles, long totalBytes, long maxBytes, String lastPruned) {
        String[][] rows = {
            {"CAS Blobs", fmtCount(cas.files), fmtSize(cas.bytes)},
            {"Worker JARs", fmtCount(repos.files), fmtSize(repos.bytes)},
            {"Run Logs", fmtCount(runs.files), fmtSize(runs.bytes)},
        };
        String[] total = {"Total", fmtCount(totalFiles), fmtSize(totalBytes)};

        int[] w = new int[3];
        for (int i = 0; i < 3; i++) w[i] = cc.jumpkick.cli.tui.BoxTable.visibleWidth(REPO_STORAGE_HEADERS[i]);
        for (String[] r : rows)
            for (int i = 0; i < 3; i++) w[i] = Math.max(w[i], cc.jumpkick.cli.tui.BoxTable.visibleWidth(r[i]));
        for (int i = 0; i < 3; i++) w[i] = Math.max(w[i], cc.jumpkick.cli.tui.BoxTable.visibleWidth(total[i]));
        int inner = (w[0] + 2) + (w[1] + 2) + (w[2] + 2) + 2;

        List<String> out = new ArrayList<>();
        out.add(cc.jumpkick.cli.tui.BoxTable.titleBar("Repo Storage", inner + 2));
        out.add(divider("├", "┬", "┤", w));
        out.add(headerRow(REPO_STORAGE_HEADERS, w));
        out.add(divider("├", "┼", "┤", w));
        for (String[] r : rows) out.add(metricRow(r, w));
        out.add(divider("├", "┼", "┤", w));
        out.add(metricRow(total, w));
        out.add(divider("├", "┴", "┤", w));
        out.add(utilizationRow(totalBytes, maxBytes, inner));
        out.add(border("╰", "╯", inner));
        Theme t = Theme.active();
        out.add("  Last pruned: "
                + Theme.colorize(lastPruned, "never".equals(lastPruned) ? t.warning() : t.normalGray()));
        return out;
    }

    private static String border(String left, String right, int inner) {
        return Theme.colorize(left + "─".repeat(inner) + right, Theme.active().darkGray());
    }

    private static String divider(String left, String junction, String right, int[] w) {
        StringBuilder sb = new StringBuilder(left);
        for (int i = 0; i < w.length; i++) {
            sb.append("─".repeat(w[i] + 2));
            sb.append(i == w.length - 1 ? right : junction);
        }
        return Theme.colorize(sb.toString(), Theme.active().darkGray());
    }

    private static String headerRow(String[] headers, int[] w) {
        String bar = Theme.colorize("│", Theme.active().darkGray());
        StringBuilder sb = new StringBuilder(bar);
        for (int i = 0; i < headers.length; i++) {
            sb.append(" ")
                    .append(cc.jumpkick.cli.tui.BoxTable.headerCell(padRight(headers[i], w[i])))
                    .append(" ")
                    .append(bar);
        }
        return sb.toString();
    }

    private static String metricRow(String[] r, int[] w) {
        String bar = Theme.colorize("│", Theme.active().darkGray());
        return bar
                + " "
                + Theme.colorize(padLeft(r[0], w[0]), Theme.active().brightWhite())
                + " "
                + bar
                + " "
                + padRight(r[1], w[1])
                + " "
                + bar
                + " "
                + padRight(r[2], w[2])
                + " "
                + bar;
    }

    private static String utilizationRow(long used, long max, int inner) {
        Theme t = Theme.active();
        int pct = (int) Math.round(cc.jumpkick.cli.tui.ProgressBar.fraction(used, max) * 100);
        String prefix = " Utilization  ";
        String suffix = "  " + pct + "% ";
        int barWidth = Math.max(0, inner - prefix.length() - suffix.length());
        String bar = cc.jumpkick.cli.tui.ProgressBar.renderBar(
                used, max, barWidth, t.bright(t.planBadgeColor()), t.darkGray());
        String rail = Theme.colorize("│", t.darkGray());
        return rail + prefix + bar + suffix + rail;
    }

    /** ANSI-aware pads ({@code BoxTable.visibleWidth}) so colored cells keep the box aligned. */
    private static String padRight(String s, int w) {
        int len = cc.jumpkick.cli.tui.BoxTable.visibleWidth(s);
        return len >= w ? s : s + " ".repeat(w - len);
    }

    private static String padLeft(String s, int w) {
        int len = cc.jumpkick.cli.tui.BoxTable.visibleWidth(s);
        return len >= w ? s : " ".repeat(w - len) + s;
    }
}
