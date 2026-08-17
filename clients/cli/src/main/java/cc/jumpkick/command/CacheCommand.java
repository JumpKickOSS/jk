// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.Cas;
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
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code jk cache} — manage the <strong>cache tier</strong> under {@code $JK_CACHE_DIR}: action
 * index ({@code actions/}), cache CAS ({@code sha256/}), and format stamps. Long-lived artifact
 * CAS and Maven/repo mirrors live under the store ({@code JK_STORE_DIR}); see {@code jk storage}
 * / {@code jk repo search}.
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
     * <p>Byte sizes are exclusive across store sections (CAS first), so hard-linked repo jars do not
     * inflate "Size on Disk" or the utilization bar. Cache-tier {@code actions} stats include the
     * cache CAS blob tree; plain (non-exclusive) counting there is exact because the cache CAS is
     * copy-only — no blob is ever hard-linked across tiers (verified for ).
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
     * Used by status / dashboard parity; {@code jk cache usage} uses {@link #cacheUsageStats}.
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
     * Detailed cache-tier breakdown for {@code jk cache usage}. Action-output CAS blobs are
     * attributed by task type from {@code actions/keys/} (exclusive by digest). Event logs and
     * format stamps are trees under the cache root. {@link CacheUsageStats#totalFiles()} /
     * {@link CacheUsageStats#totalBytes()} cover the <em>entire</em> cache root (hash-memo, Graal
     * catalog, action index, access ledger, …).
     */
    static CacheUsageStats cacheUsageStats(Path cacheRoot) throws IOException {
        long[] classFiles = {0, 0};
        long[] testResults = {0, 0};
        long[] normalJars = {0, 0};
        long[] shadowJars = {0, 0};
        long[] minifiedJars = {0, 0};
        long[] nativeBins = {0, 0};
        long[] ociImages = {0, 0};

        Cas cas = new Cas(cacheRoot);
        Set<String> seenShas = new HashSet<>();
        Path keysDir = cacheRoot.resolve("actions").resolve("keys");
        if (Files.isDirectory(keysDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(keysDir)) {
                for (Path keyFile : stream) {
                    if (!Files.isRegularFile(keyFile)) continue;
                    String body;
                    try {
                        body = Files.readString(keyFile, StandardCharsets.UTF_8);
                    } catch (IOException unreadable) {
                        continue;
                    }
                    String taskName = taskNameFromKeyBody(body);
                    long[] bucket = bucketCounters(
                            taskName,
                            classFiles,
                            testResults,
                            normalJars,
                            shadowJars,
                            minifiedJars,
                            nativeBins,
                            ociImages);
                    // run-tests mostly stores scalar markers on the key itself (no CAS digests).
                    if (bucket == testResults) {
                        testResults[0]++;
                        try {
                            testResults[1] += Files.size(keyFile);
                        } catch (IOException ignored) {
                        }
                    }
                    for (String sha : outputShasFromKeyBody(body)) {
                        if (!seenShas.add(sha)) continue; // exclusive: first claim wins
                        Path blob = cas.pathFor(sha);
                        if (!Files.isRegularFile(blob)) continue;
                        if (bucket == null) continue; // uncategorized task — still in total via full walk
                        bucket[0]++;
                        try {
                            bucket[1] += Files.size(blob);
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }

        Stats eventLogs = statsOf(cacheRoot.resolve("runs"));
        Stats stamps = statsOf(cacheRoot.resolve("format-stamps"));
        // Whole-tree total (every file under the cache root).
        Stats total = statsOf(cacheRoot);
        return new CacheUsageStats(
                new Stats(classFiles[0], classFiles[1]),
                new Stats(testResults[0], testResults[1]),
                eventLogs,
                new Stats(normalJars[0], normalJars[1]),
                new Stats(shadowJars[0], shadowJars[1]),
                new Stats(minifiedJars[0], minifiedJars[1]),
                new Stats(nativeBins[0], nativeBins[1]),
                new Stats(ociImages[0], ociImages[1]),
                stamps,
                total);
    }

    /** Task name before {@code @} in a key body's {@code TASK} line, lowercased. */
    private static String taskNameFromKeyBody(String body) {
        for (String line : body.split("\n")) {
            if (!line.startsWith("TASK ")) continue;
            String id = line.substring("TASK ".length()).trim();
            int at = id.indexOf('@');
            String name = at < 0 ? id : id.substring(0, at);
            return name.toLowerCase(Locale.ROOT);
        }
        return "";
    }

    /** CAS digests on {@code OUTPUT <sha> <rel>} lines (64-char hex only). */
    private static List<String> outputShasFromKeyBody(String body) {
        List<String> shas = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (!line.startsWith("OUTPUT ")) continue;
            String rest = line.substring("OUTPUT ".length()).trim();
            int sp = rest.indexOf(' ');
            String maybe = sp < 0 ? rest : rest.substring(0, sp);
            if (maybe.length() == 64 && isHex(maybe)) shas.add(maybe.toLowerCase(Locale.ROOT));
        }
        return shas;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    /**
     * Map a task name to the mutable {@code [files, bytes]} counters for its usage row, or
     * {@code null} when the task is not one of the displayed categories.
     */
    private static long[] bucketCounters(
            String taskName,
            long[] classFiles,
            long[] testResults,
            long[] normalJars,
            long[] shadowJars,
            long[] minifiedJars,
            long[] nativeBins,
            long[] ociImages) {
        if (taskName.isEmpty()) return null;
        if (TaskNames.RUN_TESTS.equals(taskName)) return testResults;
        if (TaskNames.PACKAGE_JAR.equals(taskName)) return normalJars;
        if (TaskNames.PACKAGE_ASSEMBLY.equals(taskName)) return shadowJars;
        if (TaskNames.PACKAGE_MINIFIED.equals(taskName)) return minifiedJars;
        if (TaskNames.NATIVE_IMAGE.equals(taskName)) return nativeBins;
        if (TaskNames.WRITE_IMAGE.equals(taskName)) return ociImages;
        // compile-main / compile-test / compile-java / compile-kotlin / …
        if (taskName.startsWith("compile-") || TaskNames.ASSEMBLE_CLASSES.equals(taskName)) return classFiles;
        return null;
    }

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
                statFromAck(ack, "workers"));
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

    /**
     * Artifact-store usage breakdown for {@code jk storage usage}: packaging-class jars / natives /
     * OCI from the store CAS (content sniff), worker jars under {@code store/lib/}. Format stamps
     * belong to {@code jk cache usage} (cache tier). Run logs are state and are omitted.
     *
     * <p>Byte sizes are exclusive (store CAS first, then {@code lib/}, then {@code repos/}) so
     * hard-linked materializations do not double-count.
     */
    static StoreUsageStats storeUsageStats(Path cacheRoot) throws IOException {
        Path storeRoot = JkStores.storeRootFor(cacheRoot);
        Path storeCas = storeRoot.resolve("sha256");
        Path lib = storeRoot.resolve("lib");
        Path repos = JkStores.resolve(cacheRoot, "repos");

        java.util.Set<Object> seen = new java.util.HashSet<>();
        long jarFiles = 0, jarBytes = 0;
        long execFiles = 0, execBytes = 0;
        long ociFiles = 0, ociBytes = 0;

        if (Files.isDirectory(storeCas)) {
            try (var walk = Files.walk(storeCas)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    java.nio.file.attribute.BasicFileAttributes attrs;
                    try {
                        attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
                    } catch (IOException unreadable) {
                        continue;
                    }
                    if (!attrs.isRegularFile()) continue;
                    Object key = attrs.fileKey();
                    if (key == null) key = p.toAbsolutePath().normalize();
                    long size = seen.add(key) ? attrs.size() : 0L;
                    // File count always counts directory entries; bytes are exclusive.
                    switch (sniffArtifactKind(p)) {
                        case EXECUTABLE -> {
                            execFiles++;
                            execBytes += size;
                        }
                        case OCI -> {
                            ociFiles++;
                            ociBytes += size;
                        }
                        case JAR, OTHER -> {
                            jarFiles++;
                            jarBytes += size;
                        }
                    }
                }
            }
        }

        // repos/ materializations that are not hard-linked into CAS (poms, checksums, …) count as
        // jar-adjacent artifact store content — exclusive of CAS + lib inodes already seen.
        Stats reposExtra = walkExclusiveAdding(repos, seen);
        jarFiles += reposExtra.files;
        jarBytes += reposExtra.bytes;

        Stats workers = walkExclusiveAdding(lib, seen);
        return new StoreUsageStats(
                new Stats(jarFiles, jarBytes), new Stats(execFiles, execBytes), new Stats(ociFiles, ociBytes), workers);
    }

    /** Content-class for a store CAS blob (or any regular file under the store). */
    private enum ArtifactKind {
        JAR,
        EXECUTABLE,
        OCI,
        OTHER
    }

    /**
     * Sniff the first bytes of {@code file} to classify jar / native binary / OCI tarball. Falls
     * back to path hints ({@code .jar}, {@code .tar}, …) when the head is unreadable.
     */
    private static ArtifactKind sniffArtifactKind(Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString().toLowerCase() : "";
        if (name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".war") || name.endsWith(".ear")) {
            return ArtifactKind.JAR;
        }
        if (name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".oci")) {
            return ArtifactKind.OCI;
        }
        try (var in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(8);
            if (head.length >= 4
                    && head[0] == 'P'
                    && head[1] == 'K'
                    && (head[2] == 3 || head[2] == 5 || head[2] == 7)
                    && (head[3] == 4 || head[3] == 6 || head[3] == 8)) {
                return ArtifactKind.JAR; // ZIP local/central/empty header
            }
            if (head.length >= 4 && head[0] == 0x7f && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') {
                return ArtifactKind.EXECUTABLE; // ELF
            }
            // Mach-O 32/64 (incl. fat/universal)
            if (head.length >= 4) {
                int be = ((head[0] & 0xff) << 24)
                        | ((head[1] & 0xff) << 16)
                        | ((head[2] & 0xff) << 8)
                        | (head[3] & 0xff);
                if (be == 0xFEEDFACE || be == 0xFEEDFACF || be == 0xCAFEBABE || be == 0xCFFAEDFE || be == 0xCEFAEDFE) {
                    return ArtifactKind.EXECUTABLE;
                }
            }
        } catch (IOException ignored) {
            return ArtifactKind.OTHER;
        }
        // POSIX ustar magic sits at offset 257 — second open for the seek-less path.
        try (var in = Files.newInputStream(file)) {
            byte[] skip = in.readNBytes(257);
            if (skip.length == 257) {
                byte[] magic = in.readNBytes(5);
                if (magic.length == 5
                        && magic[0] == 'u'
                        && magic[1] == 's'
                        && magic[2] == 't'
                        && magic[3] == 'a'
                        && magic[4] == 'r') {
                    return ArtifactKind.OCI;
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return ArtifactKind.OTHER;
    }

    /** Walk {@code dir} counting every regular file; bytes only for unseen {@code fileKey}s. */
    private static Stats walkExclusiveAdding(Path dir, java.util.Set<Object> seenKeys) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return new Stats(0, 0);
        long files = 0;
        long bytes = 0;
        try (var walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                java.nio.file.attribute.BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
                } catch (IOException unreadable) {
                    continue;
                }
                if (!attrs.isRegularFile()) continue;
                files++;
                Object key = attrs.fileKey();
                if (key == null) key = p.toAbsolutePath().normalize();
                if (seenKeys.add(key)) bytes += attrs.size();
            }
        }
        return new Stats(files, bytes);
    }

    /** Rows for {@code jk storage usage} (store-tier only). */
    record StoreUsageStats(Stats jars, Stats executables, Stats oci, Stats workers) {
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

        @Override
        public int run(Invocation in) {
            CliOutput.out(String.valueOf(
                    resolveCacheRoot(in.value("cache-dir").map(Path::of).orElse(null))));
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
            Path root = resolveCacheRoot(in.value("cache-dir").map(Path::of).orElse(null));
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
            Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
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
            Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
            boolean dryRun = in.isSet("dry-run");
            GlobalOptions global = GlobalOptions.from(in);
            return CacheCommand.runNuke(resolveCacheRoot(cacheDir), dryRun, global, false);
        }

        /**
         * Cache-tier footprint the purge will delete: {@code actions/}, {@code format-stamps/}, and
         * cache {@code sha256/} (mirrors {@code CachePlans.purgeActionCache}). Collocated
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
        return renderUsageTable("Artifact Storage", rows, s.totalFiles(), s.totalBytes(), maxBytes, lastCleaned);
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
