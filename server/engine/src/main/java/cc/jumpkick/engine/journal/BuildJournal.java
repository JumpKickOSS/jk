// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.builds.MetricsFile;
import cc.jumpkick.builds.MetricsHarvest;
import cc.jumpkick.builds.ModuleKeys;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.engine.api.BuildHistoryKinds;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.TaskPhases;
import cc.jumpkick.runtime.base.TestClassWalls;
import cc.jumpkick.runtime.base.TestSuiteRunners;
import cc.jumpkick.runtime.base.TestSuiteScaling;
import cc.jumpkick.util.DirKeys;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Best-effort build history under
 * {@code ~/.jk/state/builds/projects/&lt;key&gt;/runs/&lt;build-number&gt;/} with
 * {@code record.json}, {@code details.jsonl}, {@code jk-results.md}, {@code metrics.toml}, and
 * optional snapshots.
 *
 * <p>Directory name is the project build number (e.g. {@code 27}). {@link BuildRecord#id()} is a
 * UTC timestamp stamp for the run, not a path key. {@link #begin}/{@link #complete} locators are
 * the build-number directory name. On complete, requests {@link MetricsHarvest}.
 */
public final class BuildJournal {

    public static final String RESULTS_MD = ProjectBuilds.RESULTS;

    /** Whitelist name for journal rows that still carry a test-only markdown snapshot. */
    public static final String TEST_RESULTS_MD = "test-results.md";

    /**
     * Sidecar of an in-flight entry naming the engine that owns it: {@code <pid> <startMillis>},
     * the process's own start instant so a recycled pid is not mistaken for it. Read by a starting
     * engine to tell a row a dead engine left from one a draining predecessor is still finishing.
     */
    static final String ENGINE_OWNER = "engine-owner.txt";

    public static final String DIAGNOSTICS_TXT = "diagnostics.txt";

    /** Every test's outcome this run, {@link RunSnapshots#encodeTests}. */
    public static final String TEST_OUTCOMES_TSV = "test-outcomes.tsv";

    /** Every project file's content hash at the end of this run, {@link RunSnapshots#encodeSources}. */
    public static final String SOURCES_TSV = "sources.tsv";

    private static final String RECORD = "record.json";

    /** UTC timestamp form stored as {@link BuildRecord#id()} (not the directory name). */
    private static final DateTimeFormatter ID_TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS");

    private final Path buildsRoot;

    public BuildJournal(Path buildsRoot) {
        this.buildsRoot = buildsRoot.normalize();
    }

    public static BuildJournal current() {
        return new BuildJournal(JkDirs.builds());
    }

    public Path buildsRoot() {
        return buildsRoot;
    }

    /**
     * Resolve a run directory. {@code locator} is the build-number directory name (digits), or a
     * record timestamp id (scanned). Prefer project-scoped lookup when dir/coord are known.
     */
    public Optional<Path> runDir(String locator) {
        return findRunDir(locator);
    }

    public Optional<Path> runDir(@Nullable String coord, String projectDir, long buildNumber) {
        if (buildNumber <= 0) return Optional.empty();
        Path home = ProjectBuilds.projectHome(
                buildsRoot, coord, Path.of(projectDir == null || projectDir.isBlank() ? "." : projectDir));
        return ProjectBuilds.findRunDir(home, buildNumber);
    }

    public Optional<Path> detailsFile(String locator) {
        return findRunDir(locator).map(d -> d.resolve(ProjectBuilds.DETAILS));
    }

    public Optional<Path> detailsFile(@Nullable String coord, String projectDir, long buildNumber) {
        return runDir(coord, projectDir, buildNumber).map(d -> d.resolve(ProjectBuilds.DETAILS));
    }

    /**
     * Optional files copied into the run dir. {@code resultsMd} is the {@code jk-results.md} source
     * when the caller already materialised it.
     */
    public record Snapshot(
            @Nullable Path resultsMd,
            @Nullable Path lockfile,
            @Nullable String diagnosticsText,
            @Nullable String testOutcomes,
            @Nullable String sources) {
        public static final Snapshot NONE = new Snapshot(null, null, null, null, null);

        /** Without the per-run snapshots a {@link JobDelta} compares. */
        public Snapshot(@Nullable Path resultsMd, @Nullable Path lockfile, @Nullable String diagnosticsText) {
            this(resultsMd, lockfile, diagnosticsText, null, null);
        }
    }

    public record PruneResult(int removedEntries, long removedBytes) {}

    /**
     * Persist {@code record} under {@code runs/<buildNumber>/}. Returns the <strong>directory
     * locator</strong> (build-number string) for {@link #complete}, or {@code null} on failure.
     * {@link BuildRecord#id()} on disk is a UTC timestamp stamp, not the directory name.
     */
    public @Nullable String append(BuildRecord record, Snapshot snapshot) {
        try {
            Path projectPath = Path.of(record.dir() == null || record.dir().isBlank() ? "." : record.dir());
            String coord = record.coord() == null || record.coord().isBlank() ? "unknown:unknown" : record.coord();
            Path home = ProjectBuilds.projectHome(buildsRoot, coord, projectPath);
            Files.createDirectories(home.resolve(ProjectBuilds.RUNS));
            ProjectBuilds.writeIdentity(home, coord, projectPath);
            long stampMillis = record.finishedAt() > 0
                    ? record.finishedAt()
                    : (record.startedAt() > 0 ? record.startedAt() : System.currentTimeMillis());
            String timestamp = ID_TS.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(stampMillis), ZoneOffset.UTC));
            long n = record.buildNumber();
            String dirName;
            if (n > 0) {
                dirName = ProjectBuilds.runDirName(n);
            } else if (BuildHistoryKinds.isBuildLike(record.kind())) {
                n = ProjectBuilds.allocateRunNumber(home);
                dirName = ProjectBuilds.runDirName(n);
            } else {
                // format/lock/… — persist, but do not bump the per-project #N sequence.
                dirName = jobDirName(timestamp, record.requestId());
                n = 0;
            }
            Path target = home.resolve(ProjectBuilds.RUNS).resolve(dirName);
            Path tmp = home.resolve(ProjectBuilds.RUNS).resolve("." + dirName + ".tmp");
            PathUtil.deleteRecursively(tmp);
            try {
                Files.createDirectory(tmp);
            } catch (FileAlreadyExistsException e) {
                // concurrent same number should not happen under allocator lock; last write wins via replace
                PathUtil.deleteRecursively(tmp);
                Files.createDirectory(tmp);
            }
            try {
                BuildRecord withIds = withId(record.withBuildNumber(n), timestamp);
                Files.writeString(tmp.resolve(RECORD), Json.write(withIds), StandardCharsets.UTF_8);
                writeSnapshot(tmp, snapshot);
                if (record.running()) {
                    Files.writeString(
                            tmp.resolve(ENGINE_OWNER), EngineOwner.current().line(), StandardCharsets.UTF_8);
                } else {
                    writeRunMetricsToml(tmp, withIds);
                }
                if (Files.exists(target)) {
                    // Replacing an existing run dir (complete path uses complete(); append for finished
                    // orphan may overwrite). Prefer atomic replace of contents.
                    PathUtil.deleteRecursively(target);
                }
                move(tmp, target);
                if (!record.running() && !record.synthetic() && BuildHistoryKinds.isBuildLike(record.kind()))
                    MetricsHarvest.get().request();
                return dirName;
            } catch (IOException e) {
                PathUtil.deleteRecursively(tmp);
                return null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Open an in-flight journal entry at request-start. Returns the build-number directory locator
     * for {@link #complete}, or {@code null} on failure.
     */
    public @Nullable String begin(BuildRecord running) {
        if (running == null) return null;
        return append(running, Snapshot.NONE);
    }

    /**
     * Replace an in-flight entry with its finished record. {@code locator} is the build-number
     * directory name returned by {@link #begin}. Preserves the timestamp {@code id} from the
     * in-flight record when {@code finished.id()} is blank.
     */
    public boolean complete(String locator, BuildRecord finished, Snapshot snapshot) {
        if (!validLocator(locator) || finished == null) return false;
        Path target = resolveForComplete(locator, finished).orElse(null);
        if (target == null || !Files.isDirectory(target)) return false;
        Path parent = Objects.requireNonNull(target.getParent(), "journal entry has no parent");
        String dirName = target.getFileName().toString();
        Path tmp = parent.resolve("." + dirName + ".complete.tmp");
        try {
            PathUtil.deleteRecursively(tmp);
            Files.createDirectory(tmp);
            String timestamp = finished.id();
            if (timestamp == null || timestamp.isBlank()) {
                timestamp = readRecord(target).map(BuildRecord::id).orElse(null);
            }
            if (timestamp == null || timestamp.isBlank()) {
                long stampMillis = finished.finishedAt() > 0
                        ? finished.finishedAt()
                        : (finished.startedAt() > 0 ? finished.startedAt() : System.currentTimeMillis());
                timestamp = ID_TS.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(stampMillis), ZoneOffset.UTC));
            }
            long n = finished.buildNumber() > 0 ? finished.buildNumber() : ProjectBuilds.runNumberOf(target);
            BuildRecord toWrite = withId(finished.withBuildNumber(n), timestamp);
            Files.writeString(tmp.resolve(RECORD), Json.write(toWrite), StandardCharsets.UTF_8);
            writeSnapshot(tmp, snapshot);
            writeRunMetricsToml(tmp, toWrite);
            Files.move(tmp.resolve(RECORD), target.resolve(RECORD), StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(target.resolve(ENGINE_OWNER));
            if (Files.isRegularFile(tmp.resolve(ProjectBuilds.METRICS))) {
                Files.move(
                        tmp.resolve(ProjectBuilds.METRICS),
                        target.resolve(ProjectBuilds.METRICS),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            if (snapshot != null) {
                if (snapshot.resultsMd() != null && Files.isRegularFile(tmp.resolve(RESULTS_MD))) {
                    Files.move(
                            tmp.resolve(RESULTS_MD), target.resolve(RESULTS_MD), StandardCopyOption.REPLACE_EXISTING);
                }
                if (snapshot.lockfile() != null && Files.isRegularFile(tmp.resolve(ManifestPaths.LOCK))) {
                    Files.move(
                            tmp.resolve(ManifestPaths.LOCK),
                            target.resolve(ManifestPaths.LOCK),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                if (snapshot.diagnosticsText() != null && Files.isRegularFile(tmp.resolve(DIAGNOSTICS_TXT))) {
                    Files.move(
                            tmp.resolve(DIAGNOSTICS_TXT),
                            target.resolve(DIAGNOSTICS_TXT),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                moveIfPresent(tmp, target, TEST_OUTCOMES_TSV);
                moveIfPresent(tmp, target, SOURCES_TSV);
            }
            PathUtil.deleteRecursively(tmp);
            // Synthetic optimize/calibrate fixtures must not train host ETA aggregates.
            if (!toWrite.synthetic() && BuildHistoryKinds.isBuildLike(toWrite.kind())) {
                MetricsHarvest.get().request();
            }
            return true;
        } catch (IOException | RuntimeException e) {
            PathUtil.deleteRecursively(tmp);
            return false;
        }
    }

    /**
     * Drop an entire project home (all runs + identity) after a synthetic optimize/calibrate pass
     * so temp fixture paths never appear in history or the web UI.
     */
    public void purgeProject(@Nullable String coord, String projectDir) {
        try {
            Path projectPath = Path.of(projectDir == null || projectDir.isBlank() ? "." : projectDir);
            String c = coord == null || coord.isBlank() ? "unknown:unknown" : coord;
            Path home = ProjectBuilds.projectHome(buildsRoot, c, projectPath);
            if (Files.isDirectory(home)) PathUtil.deleteRecursively(home);
        } catch (RuntimeException e) {
            // best-effort
            Log.debug("purgeProject: best-effort", e);
        }
    }

    private static void writeRunMetricsToml(Path dir, BuildRecord finished) throws IOException {
        if (finished == null || finished.running()) return;
        if (!finished.success() || finished.cancelled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("# run metrics — successful steps/modules only\n");
        if (finished.millis() > 0) {
            sb.append("workspace.wall-ms = ").append(finished.millis()).append('\n');
            String kind = finished.kind() == null ? "build" : finished.kind();
            sb.append("invocation.")
                    .append(sanitize(kind))
                    .append(".wall-ms = ")
                    .append(finished.millis())
                    .append('\n');
            if (finished.dir() != null && !finished.dir().isBlank()) {
                int dirty = finished.modules() == null ? 0 : finished.modules().size();
                if (dirty == 0 && finished.steps() != null && !finished.steps().isEmpty()) dirty = 1;
                String dirKey = ModuleKeys.ROOT + (dirty > 0 ? "#d" + dirty : "");
                sb.append("invocation.")
                        .append(sanitize(kind))
                        .append(".")
                        .append(dirKey)
                        .append(".wall-ms = ")
                        .append(finished.millis())
                        .append('\n');
            }
        }
        String root = finished.dir();
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m == null || m.millis() <= 0 || !m.success()) continue;
                String key = "module." + (m.coord() != null ? sanitize(m.coord()) : ModuleKeys.relative(m.dir(), root));
                sb.append(key).append(".wall-ms = ").append(m.millis()).append('\n');
            }
        }
        // Phase walls are summed per run — one key per phase. Per-task emission would fold as a
        // MEAN in MetricsHarvest (duplicate keys), deflating the phase dimension the taxonomy
        // exists to calibrate (4×2s compile tasks must read 8s, not 2s).
        Map<String, Long> phaseTotals = new LinkedHashMap<>();
        Map<String, Map<String, Long>> modulePhaseTotals = new LinkedHashMap<>();
        if (finished.steps() != null) {
            for (BuildRecord.Task s : finished.steps()) {
                appendStepMetrics(sb, s, root, root, phaseTotals, modulePhaseTotals);
            }
        }
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m == null || m.steps() == null) continue;
                for (BuildRecord.Task s : m.steps()) {
                    appendStepMetrics(sb, s, m.dir(), root, phaseTotals, modulePhaseTotals);
                }
            }
        }
        for (Map.Entry<String, Long> e : phaseTotals.entrySet()) {
            sb.append("phase.")
                    .append(e.getKey())
                    .append(".wall-ms = ")
                    .append(e.getValue())
                    .append('\n');
        }
        for (Map.Entry<String, Map<String, Long>> me : modulePhaseTotals.entrySet()) {
            for (Map.Entry<String, Long> e : me.getValue().entrySet()) {
                sb.append("module.")
                        .append(me.getKey())
                        .append(".phase.")
                        .append(e.getKey())
                        .append(".wall-ms = ")
                        .append(e.getValue())
                        .append('\n');
            }
        }
        // Test-class walls buffered during run-tests (FQCN → ms); train harvest without recounting methods.
        appendTestClassWalls(sb, root, root);
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m != null) appendTestClassWalls(sb, m.dir(), root);
            }
        }
        if (sb.length() > 40) {
            Files.writeString(dir.resolve(ProjectBuilds.METRICS), sb.toString(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The module's class walls as one {@code [test-class."<dir>"."<pkg>"]} table per package
     * ({@link MetricsFile}). Written after every scalar row: a row after a header is one of that
     * package's classes.
     */
    private static void appendTestClassWalls(StringBuilder sb, String moduleDir, String root) {
        if (moduleDir == null || moduleDir.isBlank()) return;
        Map<String, Long> walls = TestClassWalls.take(moduleDir);
        if (walls.isEmpty()) return;
        Map<String, String> rows = new LinkedHashMap<>();
        for (var e : walls.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) continue;
            rows.put(sanitize(e.getKey()), Long.toString(e.getValue()));
        }
        MetricsFile.appendClassWalls(sb, ModuleKeys.relative(moduleDir, root), rows);
    }

    private static void appendStepMetrics(
            StringBuilder sb,
            BuildRecord.Task s,
            String moduleDir,
            String root,
            Map<String, Long> phaseTotals,
            Map<String, Map<String, Long>> modulePhaseTotals) {
        if (s == null || s.millis() <= 0) return;
        if (s.status() == null || !"SUCCESS".equalsIgnoreCase(s.status())) return;
        String task = sanitize(s.name());
        // Cache-restore / token hits can land as SUCCESS with absurdly short walls (e.g. native-image
        // 32ms). Those poison ETA means — never teach heavy steps below a floor.
        if (isImplausibleHeavyWall(task, s.millis())) return;
        // Prefer the stage the plan declared (wire `stage`) so metrics rollup and the UI fold
        // share one taxonomy. Name inference is only for records that carry none.
        String declared = s.stage();
        String phase = sanitize(declared != null && !declared.isBlank() ? declared : TaskPhases.of(s.name()));
        sb.append("task.").append(task).append(".wall-ms = ").append(s.millis()).append('\n');
        // wall-ms is the wall clock, queue wait included; wait-ms is the part of it spent blocked on
        // a shared resource (one compiler worker serving thirty modules). Summed walls across a
        // build read as machine-busy time, not work — the wait beside them says how much of that.
        if (s.waitMillis() > 0) {
            sb.append("task.")
                    .append(task)
                    .append(".wait-ms = ")
                    .append(s.waitMillis())
                    .append('\n');
        }
        phaseTotals.merge(phase, s.millis(), Long::sum);
        if (moduleDir != null && !moduleDir.isBlank()) {
            String mod = ModuleKeys.relative(moduleDir, root);
            sb.append("module.")
                    .append(mod)
                    .append(".task.")
                    .append(task)
                    .append(".wall-ms = ")
                    .append(s.millis())
                    .append('\n');
            if (s.waitMillis() > 0) {
                sb.append("module.")
                        .append(mod)
                        .append(".task.")
                        .append(task)
                        .append(".wait-ms = ")
                        .append(s.waitMillis())
                        .append('\n');
            }
            // A suite wall is only re-usable next time if we also say how many runners produced it,
            // and the re-usable form is the *normalized* one — see TestSuiteScaling.
            if (TaskNames.RUN_TESTS.equals(task)) {
                int runners = TestSuiteRunners.take(moduleDir);
                if (runners > 0) {
                    sb.append("module.")
                            .append(mod)
                            .append(".task.")
                            .append(task)
                            .append(".workers = ")
                            .append(runners)
                            .append('\n');
                    sb.append("module.")
                            .append(mod)
                            .append(".task.")
                            .append(task)
                            .append(".wall1-ms = ")
                            .append(TestSuiteScaling.normalize(s.millis(), runners))
                            .append('\n');
                }
            }
            // No input-bytes field here: nothing ever read it, and its value was wrong anyway —
            // BuildService's success fold takes (removes) the recorded bytes before the journal
            // write runs, so this always fell back to a fresh disk walk that could differ from
            // what ms/MB learning actually used. Size learning rides the in-memory
            // recordSuccessInputBytes → hostSamples path.
            modulePhaseTotals.computeIfAbsent(mod, k -> new LinkedHashMap<>()).merge(phase, s.millis(), Long::sum);
        }
    }

    /**
     * Heavy IO steps whose real wall is tens of seconds — sub-floor samples are action-cache
     * restore noise, not real work. Keep in sync with {@code MetricsHarvest} floors.
     */
    static boolean isImplausibleHeavyWall(String task, long millis) {
        if (task == null || millis <= 0) return false;
        String t = task.toLowerCase(Locale.ROOT);
        if (t.contains(TaskNames.NATIVE_IMAGE) || t.equals("native")) return millis < 5_000L;
        if (t.contains(TaskNames.WRITE_IMAGE) || t.equals("image")) return millis < 3_000L;
        return false;
    }

    static String sanitize(String s) {
        if (s == null) return "unknown";
        // Forward slashes first so Windows paths stay one key family with Unix; a POSIX
        // backslash name is NOT a separator and folds to '_' like any other odd character.
        return DirKeys.slashes(s).replaceAll("[^a-zA-Z0-9._:/-]+", "_");
    }

    /** Close out every {@code running=true} row an engine left behind — see {@link BuildRecord#abandoned}. */
    public StaleSweep abandonStaleRunning(String jkVersion) {
        return abandonStaleRunning(jkVersion, EngineOwner::alive);
    }

    /**
     * Close out every {@code running} row whose engine is gone. A row's owner is the engine that
     * wrote it ({@link #ENGINE_OWNER}); one {@code alive} accepts is a draining predecessor's, still
     * finishing, and is left alone. A row with no owner sidecar is a dead engine's.
     */
    StaleSweep abandonStaleRunning(String jkVersion, Predicate<EngineOwner> alive) {
        int abandoned = 0;
        int live = 0;
        long now = Clock.SYSTEM.millis();
        for (BuildRecord r : list()) {
            if (r == null || !r.running()) continue;
            String locator = r.buildNumber() > 0
                    ? ProjectBuilds.runDirName(r.buildNumber())
                    : (r.id() != null ? "j-" + r.id() : null);
            if (locator == null) continue;
            EngineOwner owner = runDir(locator, r).map(BuildJournal::ownerOf).orElse(null);
            if (owner != null && alive.test(owner)) {
                live++;
                continue;
            }
            if (complete(locator, r.abandoned(now, jkVersion), Snapshot.NONE)) abandoned++;
        }
        return new StaleSweep(abandoned, live);
    }

    /** What a startup sweep did: rows closed out, and rows left to an engine still alive. */
    public record StaleSweep(int abandoned, int live) {}

    private static @Nullable EngineOwner ownerOf(Path runDir) {
        Path sidecar = runDir.resolve(ENGINE_OWNER);
        if (!Files.isRegularFile(sidecar)) return null;
        try {
            return EngineOwner.parse(Files.readString(sidecar, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private static void moveIfPresent(Path from, Path to, String name) throws IOException {
        if (Files.isRegularFile(from.resolve(name))) {
            Files.move(from.resolve(name), to.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeSnapshot(Path dir, Snapshot s) throws IOException {
        if (s == null) return;
        if (s.testOutcomes() != null) {
            Files.writeString(dir.resolve(TEST_OUTCOMES_TSV), s.testOutcomes(), StandardCharsets.UTF_8);
        }
        if (s.sources() != null) {
            Files.writeString(dir.resolve(SOURCES_TSV), s.sources(), StandardCharsets.UTF_8);
        }
        if (s.resultsMd() != null && Files.isRegularFile(s.resultsMd())) {
            Files.copy(s.resultsMd(), dir.resolve(RESULTS_MD), StandardCopyOption.REPLACE_EXISTING);
        }
        if (s.lockfile() != null && Files.isRegularFile(s.lockfile())) {
            Files.copy(s.lockfile(), dir.resolve(ManifestPaths.LOCK), StandardCopyOption.REPLACE_EXISTING);
        }
        if (s.diagnosticsText() != null && !s.diagnosticsText().isBlank()) {
            Files.writeString(dir.resolve(DIAGNOSTICS_TXT), s.diagnosticsText(), StandardCharsets.UTF_8);
        }
    }

    public List<BuildRecord> list() {
        List<BuildRecord> out = new ArrayList<>();
        for (Loaded l : loadAll()) out.add(l.record());
        return out;
    }

    /** A record with the JSON text it came from and the directory holding it. */
    private record Loaded(BuildRecord record, String json, Path dir) {}

    /**
     * Every non-synthetic record, newest first, read once. Source JSON rides with the parsed
     * record so a later raw-text consumer does not re-read the file.
     */
    private List<Loaded> loadAll() {
        return loadNewest(Integer.MAX_VALUE);
    }

    /**
     * The newest {@code limit} records. {@code entryDirs()} is newest-first by run-dir mtime
     * (one stat per dir), so stopping after {@code limit} loads is O(limit) reads.
     */
    private List<Loaded> loadNewest(int limit) {
        List<Loaded> out = new ArrayList<>();
        for (Path dir : entryDirs()) {
            if (out.size() >= limit) break;
            Path record = dir.resolve(RECORD);
            if (!Files.isRegularFile(record)) continue;
            String json;
            try {
                json = Files.readString(record, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            BuildRecord parsed;
            try {
                parsed = Json.read(json);
            } catch (RuntimeException e) {
                continue;
            }
            if (parsed == null) continue;
            index(parsed.id(), dir);
            // Defense in depth: never surface optimize/calibrate fixtures.
            if (!parsed.synthetic()) out.add(new Loaded(parsed, json, dir));
        }
        // Newest first by startedAt / finishedAt
        out.sort(Comparator.comparingLong((Loaded l) -> l.record().finishedAt() > 0
                        ? l.record().finishedAt()
                        : l.record().startedAt())
                .reversed());
        return out;
    }

    /** How many earlier runs of a project the coverage delta looks back through. */
    static final int PREVIOUS_COVERAGE_LOOKBACK = 50;

    /**
     * The newest earlier run of {@code current}'s project that measured coverage — the baseline its
     * {@code jk-results.md} shows deltas against. Runs without coverage in between are skipped, so
     * a plain build does not erase the comparison; the scan stops after {@value
     * #PREVIOUS_COVERAGE_LOOKBACK} runs.
     */
    public Optional<BuildRecord> previousWithCoverage(BuildRecord current) {
        if (current.buildNumber() <= 0 || current.dir() == null || current.dir().isBlank()) return Optional.empty();
        Path home;
        try {
            home = ProjectBuilds.projectHome(buildsRoot, current.coord(), Path.of(current.dir()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        int seen = 0;
        for (Path run : ProjectBuilds.listRuns(home)) {
            long number = ProjectBuilds.runNumberOf(run);
            if (number <= 0 || number >= current.buildNumber()) continue;
            if (seen++ >= PREVIOUS_COVERAGE_LOOKBACK) break;
            Optional<BuildRecord> record =
                    readRecord(run).filter(r -> !r.running() && !r.coverage().isEmpty());
            if (record.isPresent()) return record;
        }
        return Optional.empty();
    }

    /**
     * The run before {@code current} from the same origin, with the directory its artifacts sit
     * in; see {@link JournalLineage#previousInSession}.
     */
    public Optional<JournalLineage.Previous> previousInSession(BuildRecord current) {
        return JournalLineage.previousInSession(buildsRoot, this::readRecord, current);
    }

    /** Look up by build-number directory name, {@code j-…} job directory, or record {@code id}. */
    public Optional<BuildRecord> get(String idOrLocator) {
        return switch (kindOf(idOrLocator)) {
            case RECORD_ID -> locateById(idOrLocator).map(Located::record);
            case RUN_NUMBER, JOB_DIR -> findRunDir(idOrLocator).flatMap(this::readRecord);
            case INVALID -> Optional.empty();
        };
    }

    /**
     * Run dirs located by {@link #rawFinishedRecordByRequestId} — a wait loop re-reads one
     * {@code record.json} per poll instead of re-scanning the journal. Bounded residue: cleared
     * wholesale once full — safe for a value cache (nothing holds these entries).
     */
    private final ConcurrentHashMap<Long, Path> runDirsByRequestId = new ConcurrentHashMap<>();

    /**
     * Raw JSON of the <em>finished</em> record stamped with {@code requestId}, or empty while the
     * run is absent or still {@code running}. The run dir is found with one newest-first scan and
     * memoized, so repeated polls for the same id cost a single file read.
     */
    public Optional<String> rawFinishedRecordByRequestId(long requestId) {
        if (requestId <= 0) return Optional.empty();
        Path dir = runDirsByRequestId.get(requestId);
        if (dir != null) {
            if (Files.isDirectory(dir)) return readFinished(dir, requestId);
            runDirsByRequestId.remove(requestId, dir); // pruned since memoized — re-locate
        }
        for (RawLoaded l : loadNewestRaw(200, null)) {
            if (scanLong(l.json(), "requestId") != requestId) continue;
            if (runDirsByRequestId.size() >= 1_024) runDirsByRequestId.clear();
            runDirsByRequestId.put(requestId, l.dir());
            return scanTrue(l.json(), "running") ? Optional.empty() : Optional.of(l.json());
        }
        return Optional.empty();
    }

    private static Optional<String> readFinished(Path dir, long requestId) {
        Path record = dir.resolve(RECORD);
        if (!Files.isRegularFile(record)) return Optional.empty();
        try {
            String json = Files.readString(record, StandardCharsets.UTF_8).strip();
            if (scanLong(json, "requestId") != requestId || scanTrue(json, "running")) return Optional.empty();
            return Optional.of(json);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The newest {@code limit} records as their raw JSON, never parsed — see {@link #loadNewestRaw}. */
    public List<String> rawRecords(int limit) {
        return rawRecords(limit, null);
    }

    /**
     * As {@link #rawRecords(int)}, counting only rows passing {@code filter} (a lexical gate such
     * as the history kind check) toward {@code limit} — the caller stops over-reading N× to be
     * left with enough survivors.
     */
    public List<String> rawRecords(int limit, @Nullable Predicate<String> filter) {
        List<String> out = new ArrayList<>();
        for (RawLoaded l : loadNewestRaw(Math.max(limit, 0), filter)) {
            out.add(l.json());
        }
        return out;
    }

    /** Raw JSON with its run dir and a lexical sort key — no {@link BuildRecord} graph built. */
    private record RawLoaded(String json, Path dir, long sortKey) {}

    /**
     * The raw-only twin of {@link #loadNewest}: the synthetic filter and the newest-first sort
     * are lexical scans, so a raw-history consumer never pays a full {@code Json.read} per row.
     * Rows are journal-authored, so the same first-occurrence lexical posture as
     * {@code HttpHistoryApi}'s scans holds.
     */
    private List<RawLoaded> loadNewestRaw(int limit, @Nullable Predicate<String> filter) {
        List<RawLoaded> out = new ArrayList<>();
        for (Path dir : entryDirs()) {
            if (out.size() >= limit) break;
            Path record = dir.resolve(RECORD);
            if (!Files.isRegularFile(record)) continue;
            String json;
            try {
                json = Files.readString(record, StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                continue;
            }
            // A torn or non-object file must never ride verbatim into a JSON array response.
            if (json.isEmpty() || json.charAt(0) != '{' || json.charAt(json.length() - 1) != '}') continue;
            index(scanString(json, "id"), dir);
            if (BuildRecord.isSyntheticTrigger(scanString(json, "trigger"))) continue;
            if (filter != null && !filter.test(json)) continue;
            long sort = scanLong(json, "finishedAt");
            if (sort <= 0) sort = scanLong(json, "startedAt");
            out.add(new RawLoaded(json, dir, sort));
        }
        out.sort(Comparator.comparingLong(RawLoaded::sortKey).reversed());
        return out;
    }

    /** First {@code "name": <integer>} occurrence, lexically; 0 when absent or not a number. */
    static long scanLong(String json, String name) {
        int start = scanValueStart(json, name);
        if (start < 0) return 0;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == start) return 0;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * First {@code "name": "<value>"} occurrence, lexically; null when absent or not a string. The
     * value is returned as written (escapes intact) — callers compare short enum-like words.
     */
    static @Nullable String scanString(String json, String name) {
        int start = scanValueStart(json, name);
        if (start < 0 || start >= json.length() || json.charAt(start) != '"') return null;
        int end = start + 1;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\') end += 2;
            else if (c == '"') return json.substring(start + 1, end);
            else end++;
        }
        return null;
    }

    /** True when {@code "name": true} occurs, lexically. */
    static boolean scanTrue(String json, String name) {
        int start = scanValueStart(json, name);
        return start >= 0 && json.startsWith("true", start);
    }

    /**
     * Index just past {@code "name" <ws> : <ws>}, or -1. Records are pretty-printed
     * ({@code MiniJson.writePretty}), so the whitespace around the colon is real.
     */
    private static int scanValueStart(String json, String name) {
        String quoted = '"' + name + '"';
        int at = json.indexOf(quoted);
        while (at >= 0) {
            int i = at + quoted.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i < json.length() && json.charAt(i) == ':') {
                i++;
                while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
                return i;
            }
            at = json.indexOf(quoted, at + 1);
        }
        return -1;
    }

    /** The newest {@code limit} records, parsed — the rest of the journal is never read. */
    public List<BuildRecord> list(int limit) {
        if (limit <= 0) return List.of();
        List<BuildRecord> out = new ArrayList<>();
        for (Loaded l : loadNewest(limit)) {
            if (out.size() >= limit) break;
            out.add(l.record());
        }
        return out;
    }

    public Optional<Path> recordFile(String idOrLocator) {
        return findRunDir(idOrLocator).map(d -> d.resolve(RECORD)).filter(Files::isRegularFile);
    }

    public Optional<Path> artifact(String idOrLocator, String name) {
        if (!isArtifactName(name)) return Optional.empty();
        return findRunDir(idOrLocator).map(d -> d.resolve(name)).filter(Files::isRegularFile);
    }

    public boolean delete(String idOrLocator) {
        Path dir = findRunDir(idOrLocator).orElse(null);
        if (dir == null || !Files.isDirectory(dir)) return false;
        PathUtil.deleteRecursively(dir);
        return true;
    }

    /**
     * Delete a run, resolving the locator <em>within one project</em>.
     *
     * <p>Build numbers are allocated per project, so a bare number like {@code "8"} names a
     * different run in every project home; the unscoped {@link #delete(String)} resolves it by
     * scanning project homes in sorted order and taking the first hit, which can wipe an unrelated
     * project's history. Callers that know the project (they just wrote the record) must use this
     *. Falls back to the unscoped lookup only when the project is unknown or the number
     * does not exist under it — e.g. a history id rather than a build number.
     */
    public boolean delete(String idOrLocator, @Nullable String coord, String dir) {
        if (idOrLocator != null && !idOrLocator.isBlank() && ProjectBuilds.validRunDirName(idOrLocator)) {
            try {
                long n = Long.parseLong(idOrLocator);
                Optional<Path> scoped = runDir(coord, dir, n);
                if (scoped.isPresent()) {
                    if (!Files.isDirectory(scoped.get())) return false;
                    PathUtil.deleteRecursively(scoped.get());
                    return true;
                }
                // A per-project number that does not exist under this project is not ours to
                // resolve globally — another project's run of the same number is not the target.
                return false;
            } catch (NumberFormatException ignored) {
                // not a build number — fall through to the id lookup
            }
        }
        return delete(idOrLocator);
    }

    public PruneResult prune(long maxAgeMillis, long maxDiskBytes, long nowMillis) {
        List<Entry> entries = new ArrayList<>();
        for (Path dir : entryDirs()) {
            // Never reap a run that has not finished: the idle gate is checked before this call,
            // so a build admitted in between would otherwise have its `running` stub deleted out
            // from under it.
            if (readRecord(dir).map(BuildRecord::running).orElse(false)) continue;
            entries.add(new Entry(dir, entryMillis(dir, nowMillis), sizeOf(dir)));
        }
        int removed = 0;
        long removedBytes = 0;
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries) {
            if (maxAgeMillis > 0 && nowMillis - e.millis > maxAgeMillis) {
                PathUtil.deleteRecursively(e.dir);
                removed++;
                removedBytes += e.size;
            } else {
                kept.add(e);
            }
        }
        if (maxDiskBytes > 0) {
            long total = kept.stream().mapToLong(Entry::size).sum();
            if (total > maxDiskBytes) {
                kept.sort(Comparator.comparingLong(Entry::millis));
                for (Entry e : kept) {
                    if (total <= maxDiskBytes) break;
                    PathUtil.deleteRecursively(e.dir);
                    removed++;
                    removedBytes += e.size;
                    total -= e.size;
                }
            }
        }
        return new PruneResult(removed, removedBytes);
    }

    private record Entry(Path dir, long millis, long size) {}

    private Optional<Path> resolveForComplete(String locator, BuildRecord finished) {
        return runDir(locator, finished);
    }

    /**
     * The run directory {@code record} was written to under {@code locator}. Build numbers are
     * per project, so a numbered locator is resolved under the record's own project home; the
     * unscoped lookup is for the locators that name no project (a {@code j-…} job dir, a record
     * id), and for a record that carries no project.
     */
    public Optional<Path> runDir(String locator, @Nullable BuildRecord record) {
        if (record != null && record.buildNumber() > 0 && record.dir() != null) {
            Optional<Path> scoped = runDir(record.coord(), record.dir(), record.buildNumber());
            if (scoped.isPresent()) return scoped;
        }
        return findRunDir(locator);
    }

    /**
     * The three spellings a run is addressed by: its per-project build number (the directory
     * name), a {@code j-…} job directory, or the record's timestamp {@code id} — the dashboard's
     * row key, which names no directory and is found through {@link #locateById}.
     */
    private enum LocatorKind {
        RUN_NUMBER,
        JOB_DIR,
        RECORD_ID,
        INVALID
    }

    private static LocatorKind kindOf(@Nullable String locator) {
        if (locator == null || !validLocator(locator)) return LocatorKind.INVALID;
        if (ProjectBuilds.validRunDirName(locator)) {
            try {
                Long.parseLong(locator);
                return LocatorKind.RUN_NUMBER;
            } catch (NumberFormatException ignored) {
                // digits, but not a number a run can carry — judged like any other id
            }
        }
        return isJobLocator(locator) ? LocatorKind.JOB_DIR : LocatorKind.RECORD_ID;
    }

    private Optional<Path> findRunDir(String locator) {
        return switch (kindOf(locator)) {
            case RUN_NUMBER -> ProjectBuilds.findRunDirByNumber(buildsRoot, Long.parseLong(locator));
            case JOB_DIR -> ProjectBuilds.findRunDirByName(buildsRoot, locator);
            case RECORD_ID -> locateById(locator).map(Located::dir);
            case INVALID -> Optional.empty();
        };
    }

    /** A run found by record id: its directory and the record that proved the id. */
    private record Located(Path dir, BuildRecord record) {}

    /**
     * Run dirs by record {@code id}, filled as records are read and listed, so a run the journal
     * has listed resolves with one record read. Bounded like {@link #runDirsByRequestId}.
     */
    private final ConcurrentHashMap<String, Path> runDirsById = new ConcurrentHashMap<>();

    private void index(@Nullable String id, Path dir) {
        if (id == null || id.isBlank()) return;
        if (runDirsById.size() >= 1_024) runDirsById.clear();
        runDirsById.put(id, dir);
    }

    /**
     * The indexed dir when its record still carries {@code id} (one read); otherwise one scan of
     * the journal, which fills the index for every record it passes on the way.
     */
    private Optional<Located> locateById(String id) {
        Path indexed = runDirsById.get(id);
        if (indexed != null) {
            Optional<BuildRecord> r = readRecord(indexed);
            if (r.isPresent() && id.equals(r.get().id())) return Optional.of(new Located(indexed, r.get()));
            runDirsById.remove(id, indexed); // pruned or rewritten since indexed — re-locate
        }
        for (Path dir : entryDirs()) {
            Optional<BuildRecord> r = readRecord(dir);
            if (r.isPresent() && id.equals(r.get().id())) return Optional.of(new Located(dir, r.get()));
        }
        return Optional.empty();
    }

    /** Non-build journal dirs: {@code j-<timestamp>} (no run-number allocation). */
    static String jobDirName(String timestamp, long requestId) {
        String base = "j-" + timestamp;
        if (requestId > 0) return base + "-" + requestId;
        return base;
    }

    static boolean isJobLocator(String locator) {
        return locator != null && locator.startsWith("j-") && locator.length() > 2;
    }

    private List<Path> entryDirs() {
        return ProjectBuilds.listAllRuns(buildsRoot);
    }

    /** How many {@code record.json} files this journal has read; the lookup-cost seam tests measure. */
    private final AtomicLong recordReads = new AtomicLong();

    long recordReads() {
        return recordReads.get();
    }

    private Optional<BuildRecord> readRecord(Path dir) {
        Path record = dir.resolve(RECORD);
        if (!Files.isRegularFile(record)) return Optional.empty();
        recordReads.incrementAndGet();
        try {
            BuildRecord parsed = Json.read(Files.readString(record, StandardCharsets.UTF_8));
            index(parsed.id(), dir);
            return Optional.of(parsed);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private long entryMillis(Path dir, long fallback) {
        Optional<BuildRecord> r = readRecord(dir);
        if (r.isPresent()) {
            long t = r.get().finishedAt() > 0 ? r.get().finishedAt() : r.get().startedAt();
            if (t > 0) return t;
            String id = r.get().id();
            if (id != null) {
                try {
                    return LocalDateTime.parse(id, ID_TS)
                            .toInstant(ZoneOffset.UTC)
                            .toEpochMilli();
                } catch (RuntimeException e) {
                    Log.debug("entryMillis: RuntimeException ignored", e);
                }
            }
        }
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException e) {
            return fallback;
        }
    }

    private static long sizeOf(Path dir) {
        // Guard G45: the walk already read each entry's size, so the old
        // walk-then-Files.size(p) asked the filesystem twice per file for one answer.
        long[] total = {0L};
        try {
            PathUtil.forEachRegularFile(dir, (f, attrs) -> total[0] += attrs.size());
        } catch (IOException e) {
            return 0L;
        }
        return total[0];
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static boolean isArtifactName(String name) {
        return RESULTS_MD.equals(name)
                || TEST_RESULTS_MD.equals(name)
                || TEST_OUTCOMES_TSV.equals(name)
                || SOURCES_TSV.equals(name)
                || ProjectBuilds.DETAILS.equals(name)
                || ManifestPaths.LOCK.equals(name)
                || DIAGNOSTICS_TXT.equals(name);
    }

    private static boolean validLocator(@Nullable String locator) {
        if (locator == null || locator.isBlank() || locator.startsWith(".")) return false;
        if (locator.indexOf('/') >= 0 || locator.indexOf('\\') >= 0 || locator.contains("..")) return false;
        return true;
    }

    private static BuildRecord withId(BuildRecord r, String id) {
        return r.withId(id);
    }
}
