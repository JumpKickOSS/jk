// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.builds.MetricsHarvest;
import cc.jumpkick.builds.ProjectBuilds;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Best-effort build history under
 * {@code ~/.local/state/jk/builds/projects/&lt;key&gt;/runs/&lt;build-number&gt;/} with
 * {@code record.json}, {@code details.jsonl}, {@code metrics.toml}, and optional snapshots.
 *
 * <p>Directory name is the project build number (e.g. {@code 27}). {@link BuildRecord#id()} is a
 * UTC timestamp stamp for the run, not a path key. {@link #begin}/{@link #complete} locators are
 * the build-number directory name. On complete, requests {@link MetricsHarvest}.
 */
public final class BuildJournal {

    public static final String TEST_RESULTS_MD = "test-results.md";

    public static final String LOCKFILE = "jk-lock.toml";

    public static final String DIAGNOSTICS_TXT = "diagnostics.txt";

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

    public Optional<Path> runDir(String coord, String projectDir, long buildNumber) {
        if (buildNumber <= 0) return Optional.empty();
        Path home = ProjectBuilds.projectHome(
                buildsRoot, coord, Path.of(projectDir == null || projectDir.isBlank() ? "." : projectDir));
        return ProjectBuilds.findRunDir(home, buildNumber);
    }

    public Optional<Path> detailsFile(String locator) {
        return findRunDir(locator).map(d -> d.resolve(ProjectBuilds.DETAILS));
    }

    public Optional<Path> detailsFile(String coord, String projectDir, long buildNumber) {
        return runDir(coord, projectDir, buildNumber).map(d -> d.resolve(ProjectBuilds.DETAILS));
    }

    public record Snapshot(Path testResultsMd, Path lockfile, String diagnosticsText) {
        public static final Snapshot NONE = new Snapshot(null, null, null);
    }

    public record PruneResult(int removedEntries, long removedBytes) {}

    /**
     * Persist {@code record} under {@code runs/<buildNumber>/}. Returns the <strong>directory
     * locator</strong> (build-number string) for {@link #complete}, or {@code null} on failure.
     * {@link BuildRecord#id()} on disk is a UTC timestamp stamp, not the directory name.
     */
    public String append(BuildRecord record, Snapshot snapshot) {
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
            long n = record.buildNumber() > 0 ? record.buildNumber() : ProjectBuilds.allocateRunNumber(home);
            String dirName = ProjectBuilds.runDirName(n);
            Path target = home.resolve(ProjectBuilds.RUNS).resolve(dirName);
            Path tmp = home.resolve(ProjectBuilds.RUNS).resolve("." + dirName + ".tmp");
            deleteTreeQuietly(tmp);
            try {
                Files.createDirectory(tmp);
            } catch (FileAlreadyExistsException e) {
                // concurrent same number should not happen under allocator lock; last write wins via replace
                deleteTreeQuietly(tmp);
                Files.createDirectory(tmp);
            }
            try {
                BuildRecord withIds = withId(record.withBuildNumber(n), timestamp);
                Files.writeString(tmp.resolve(RECORD), Json.write(withIds), StandardCharsets.UTF_8);
                writeSnapshot(tmp, snapshot);
                if (!record.running()) writeRunMetricsToml(tmp, withIds);
                if (Files.exists(target)) {
                    // Replacing an existing run dir (complete path uses complete(); append for finished
                    // orphan may overwrite). Prefer atomic replace of contents.
                    deleteTreeQuietly(target);
                }
                move(tmp, target);
                if (!record.running() && !record.synthetic())
                    MetricsHarvest.get().request();
                return dirName;
            } catch (IOException e) {
                deleteTreeQuietly(tmp);
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
    public String begin(BuildRecord running) {
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
        Path parent = target.getParent();
        String dirName = target.getFileName().toString();
        Path tmp = parent.resolve("." + dirName + ".complete.tmp");
        try {
            deleteTreeQuietly(tmp);
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
            if (Files.isRegularFile(tmp.resolve(ProjectBuilds.METRICS))) {
                synchronized (metricsLock(target)) {
                    Files.move(
                            tmp.resolve(ProjectBuilds.METRICS),
                            target.resolve(ProjectBuilds.METRICS),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            METRICS_LOCKS.remove(target.toAbsolutePath().normalize());
            if (snapshot != null) {
                if (snapshot.testResultsMd() != null && Files.isRegularFile(tmp.resolve(TEST_RESULTS_MD))) {
                    Files.move(
                            tmp.resolve(TEST_RESULTS_MD),
                            target.resolve(TEST_RESULTS_MD),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                if (snapshot.lockfile() != null && Files.isRegularFile(tmp.resolve(LOCKFILE))) {
                    Files.move(tmp.resolve(LOCKFILE), target.resolve(LOCKFILE), StandardCopyOption.REPLACE_EXISTING);
                }
                if (snapshot.diagnosticsText() != null && Files.isRegularFile(tmp.resolve(DIAGNOSTICS_TXT))) {
                    Files.move(
                            tmp.resolve(DIAGNOSTICS_TXT),
                            target.resolve(DIAGNOSTICS_TXT),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            deleteTreeQuietly(tmp);
            // Synthetic optimize/calibrate fixtures must not train host ETA aggregates.
            if (!toWrite.synthetic()) {
                MetricsHarvest.get().request();
            }
            return true;
        } catch (IOException | RuntimeException e) {
            deleteTreeQuietly(tmp);
            return false;
        }
    }

    /**
     * Drop an entire project home (all runs + identity) after a synthetic optimize/calibrate pass
     * so temp fixture paths never appear in history or the web UI.
     */
    public void purgeProject(String coord, String projectDir) {
        try {
            Path projectPath = Path.of(projectDir == null || projectDir.isBlank() ? "." : projectDir);
            String c = coord == null || coord.isBlank() ? "unknown:unknown" : coord;
            Path home = ProjectBuilds.projectHome(buildsRoot, c, projectPath);
            if (Files.isDirectory(home)) deleteTreeQuietly(home);
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /**
     * Serializes every mutation of a run's {@code metrics.toml}: {@link #appendHostSamples} is a
     * read-modify-write and {@code complete()} moves a freshly written file over the same path, so
     * without this one of the two silently loses (JK-1491). Keyed by run dir; entries are dropped
     * once the run is complete.
     */
    private static final ConcurrentHashMap<Path, Object> METRICS_LOCKS = new ConcurrentHashMap<>();

    private static Object metricsLock(Path runDir) {
        // complete() removes entries, but a crashed/cancelled run leaks its key — bound the
        // residue (ProjectIds idiom, JK-1942).
        if (METRICS_LOCKS.size() >= 4_096) METRICS_LOCKS.clear();
        return METRICS_LOCKS.computeIfAbsent(runDir.toAbsolutePath().normalize(), k -> new Object());
    }

    public void appendHostSamples(String locator, List<HostSampleLine> samples) {
        if (!validLocator(locator) || samples == null || samples.isEmpty()) return;
        Path run = findRunDir(locator).orElse(null);
        if (run == null) return;
        Path metrics = run.resolve(ProjectBuilds.METRICS);
        synchronized (metricsLock(run)) {
            try {
                StringBuilder sb = new StringBuilder();
                if (Files.isRegularFile(metrics)) {
                    sb.append(Files.readString(metrics, StandardCharsets.UTF_8));
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                } else {
                    sb.append("# run metrics\n");
                }
                for (HostSampleLine s : samples) {
                    if (s == null || s.key() == null || s.key().isBlank() || !(s.ms() > 0)) continue;
                    sb.append("host.")
                            .append(sanitize(s.key()))
                            .append(" = ")
                            .append(Math.round(s.ms()))
                            .append('\n');
                }
                Files.writeString(metrics, sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // best-effort: host samples are diagnostics, never worth failing a build
            }
        }
    }

    public record HostSampleLine(String key, double ms) {}

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
                String dirKey = sanitize(finished.dir()) + (dirty > 0 ? "#d" + dirty : "");
                sb.append("invocation.")
                        .append(sanitize(kind))
                        .append(".")
                        .append(dirKey)
                        .append(".wall-ms = ")
                        .append(finished.millis())
                        .append('\n');
            }
        }
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m == null || m.millis() <= 0 || !m.success()) continue;
                String key = "module." + sanitize(m.coord() != null ? m.coord() : m.dir());
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
                appendStepMetrics(sb, s, finished.dir(), phaseTotals, modulePhaseTotals);
            }
        }
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m == null || m.steps() == null) continue;
                for (BuildRecord.Task s : m.steps()) {
                    appendStepMetrics(sb, s, m.dir(), phaseTotals, modulePhaseTotals);
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
        appendTestClassWalls(sb, finished.dir());
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m != null) appendTestClassWalls(sb, m.dir());
            }
        }
        if (sb.length() > 40) {
            Files.writeString(dir.resolve(ProjectBuilds.METRICS), sb.toString(), StandardCharsets.UTF_8);
        }
    }

    private static void appendTestClassWalls(StringBuilder sb, String moduleDir) {
        if (moduleDir == null || moduleDir.isBlank()) return;
        Map<String, Long> walls = cc.jumpkick.runtime.TestClassWalls.take(moduleDir);
        if (walls.isEmpty()) return;
        String mod = sanitize(moduleDir);
        for (var e : walls.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) continue;
            sb.append("module.")
                    .append(mod)
                    .append(".test-class.")
                    .append(sanitize(e.getKey()))
                    .append(".wall-ms = ")
                    .append(e.getValue())
                    .append('\n');
        }
    }

    private static void appendStepMetrics(
            StringBuilder sb,
            BuildRecord.Task s,
            String moduleDir,
            Map<String, Long> phaseTotals,
            Map<String, Map<String, Long>> modulePhaseTotals) {
        if (s == null || s.millis() <= 0) return;
        if (s.status() == null || !"SUCCESS".equalsIgnoreCase(s.status())) return;
        String task = sanitize(s.name());
        // Cache-restore / token hits can land as SUCCESS with absurdly short walls (e.g. native-image
        // 32ms). Those poison ETA means — never teach heavy steps below a floor.
        if (isImplausibleHeavyWall(task, s.millis())) return;
        // The record already carries the stage the plan declared (wire `stage`). Re-deriving it
        // from the task name put the metrics rollup on a different taxonomy than the UI fold —
        // plugin-android-res reported `generate` on the wire and landed in `phase.compile` here,
        // and every stage(RESOLVE) task in ScriptPlans landed in `other` (JK-1610). Name inference
        // stays as the fallback for records that carry no stage.
        String declared = s.stage();
        String phase = sanitize(
                declared != null && !declared.isBlank() ? declared : cc.jumpkick.runtime.TaskPhases.of(s.name()));
        sb.append("task.").append(task).append(".wall-ms = ").append(s.millis()).append('\n');
        phaseTotals.merge(phase, s.millis(), Long::sum);
        if (moduleDir != null && !moduleDir.isBlank()) {
            String mod = sanitize(moduleDir);
            sb.append("module.")
                    .append(mod)
                    .append(".task.")
                    .append(task)
                    .append(".wall-ms = ")
                    .append(s.millis())
                    .append('\n');
            // No input-bytes field here: nothing ever read it, and its value was wrong anyway —
            // BuildService's success fold takes (removes) the recorded bytes before the journal
            // write runs, so this always fell back to a fresh disk walk that could differ from
            // what ms/MB learning actually used. Size learning rides the in-memory
            // recordSuccessInputBytes → hostSamples path (JK-1831).
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
        if (t.contains("native-image") || t.equals("native")) return millis < 5_000L;
        if (t.contains("write-image") || t.equals("image")) return millis < 3_000L;
        return false;
    }

    static String sanitize(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9._:/-]+", "_");
    }

    public int abandonStaleRunning(String jkVersion) {
        int n = 0;
        long now = System.currentTimeMillis();
        for (BuildRecord r : list()) {
            if (r == null || !r.running()) continue;
            BuildRecord done = new BuildRecord(
                    r.id(),
                    r.buildNumber(),
                    r.schema(),
                    r.kind(),
                    r.dir(),
                    r.coord(),
                    r.projectId(),
                    r.startedAt(),
                    now,
                    Math.max(0, now - r.startedAt()),
                    false,
                    true,
                    130,
                    jkVersion != null ? jkVersion : r.jkVersion(),
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    r.trigger(),
                    r.commit(),
                    null,
                    false,
                    r.io());
            String locator = ProjectBuilds.runDirName(r.buildNumber());
            if (complete(locator, done, Snapshot.NONE)) n++;
        }
        return n;
    }

    private static void writeSnapshot(Path dir, Snapshot s) throws IOException {
        if (s == null) return;
        if (s.testResultsMd() != null && Files.isRegularFile(s.testResultsMd())) {
            Files.copy(s.testResultsMd(), dir.resolve(TEST_RESULTS_MD), StandardCopyOption.REPLACE_EXISTING);
        }
        if (s.lockfile() != null && Files.isRegularFile(s.lockfile())) {
            Files.copy(s.lockfile(), dir.resolve(LOCKFILE), StandardCopyOption.REPLACE_EXISTING);
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
     * Every non-synthetic record, newest first, read <em>once</em>.
     *
     * <p>Callers used to parse the whole journal and then re-read the same {@code record.json}
     * files as raw text, so a dashboard refresh cost two full passes over every run on disk
     * (JK-1479). Keeping the source JSON alongside the parsed record makes the second pass free.
     */
    private List<Loaded> loadAll() {
        return loadNewest(Integer.MAX_VALUE);
    }

    /**
     * The newest {@code limit} records, reading no further down the journal than the limit
     * requires. {@code entryDirs()} is already newest-first by run-dir mtime (one stat per dir,
     * JK-1480), so stopping after {@code limit} loads bounds the whole pass at O(limit) reads +
     * parses instead of materialising every record on disk — at the 2000-run retention default a
     * 200-row dashboard page used to read and parse 10× what it returned (JK-1942).
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
            // Defense in depth: never surface optimize/calibrate fixtures (JK-1390).
            if (parsed != null && !parsed.synthetic()) out.add(new Loaded(parsed, json, dir));
        }
        // Newest first by startedAt / finishedAt
        out.sort(Comparator.comparingLong((Loaded l) -> l.record().finishedAt() > 0
                        ? l.record().finishedAt()
                        : l.record().startedAt())
                .reversed());
        return out;
    }

    /**
     * Look up by build-number directory name or by record timestamp {@code id}.
     */
    public Optional<BuildRecord> get(String idOrLocator) {
        if (idOrLocator == null || idOrLocator.isBlank()) return Optional.empty();
        Optional<Path> byDir = findRunDir(idOrLocator);
        if (byDir.isPresent()) return readRecord(byDir.get());
        // Timestamp id: scan records
        for (Path dir : entryDirs()) {
            Optional<BuildRecord> r = readRecord(dir);
            if (r.isPresent() && idOrLocator.equals(r.get().id())) return r;
        }
        return Optional.empty();
    }

    /** The newest {@code limit} records as their raw JSON — no second read (see {@link #loadNewest}). */
    public List<String> rawRecords(int limit) {
        List<String> out = new ArrayList<>();
        for (Loaded l : loadNewest(Math.max(limit, 0))) {
            if (out.size() >= limit) break;
            out.add(l.json());
        }
        return out;
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
        return getRunPath(idOrLocator).map(d -> d.resolve(RECORD)).filter(Files::isRegularFile);
    }

    public Optional<Path> artifact(String idOrLocator, String name) {
        if (!isArtifactName(name)) return Optional.empty();
        return getRunPath(idOrLocator).map(d -> d.resolve(name)).filter(Files::isRegularFile);
    }

    public boolean delete(String idOrLocator) {
        Path dir = getRunPath(idOrLocator).orElse(null);
        if (dir == null || !Files.isDirectory(dir)) return false;
        deleteTreeQuietly(dir);
        return true;
    }

    /**
     * Delete a run, resolving the locator <em>within one project</em>.
     *
     * <p>Build numbers are allocated per project, so a bare number like {@code "8"} names a
     * different run in every project home; the unscoped {@link #delete(String)} resolves it by
     * scanning project homes in sorted order and taking the first hit, which can wipe an unrelated
     * project's history. Callers that know the project (they just wrote the record) must use this
     * (JK-1471). Falls back to the unscoped lookup only when the project is unknown or the number
     * does not exist under it — e.g. a history id rather than a build number.
     */
    public boolean delete(String idOrLocator, String coord, String dir) {
        if (idOrLocator != null && !idOrLocator.isBlank() && ProjectBuilds.validRunDirName(idOrLocator)) {
            try {
                long n = Long.parseLong(idOrLocator);
                Optional<Path> scoped = runDir(coord, dir, n);
                if (scoped.isPresent()) {
                    if (!Files.isDirectory(scoped.get())) return false;
                    deleteTreeQuietly(scoped.get());
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
            // from under it (JK-1491).
            if (readRecord(dir).map(BuildRecord::running).orElse(false)) continue;
            entries.add(new Entry(dir, entryMillis(dir, nowMillis), sizeOf(dir)));
        }
        int removed = 0;
        long removedBytes = 0;
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries) {
            if (maxAgeMillis > 0 && nowMillis - e.millis > maxAgeMillis) {
                deleteTreeQuietly(e.dir);
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
                    deleteTreeQuietly(e.dir);
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
        if (finished != null && finished.buildNumber() > 0 && finished.dir() != null) {
            Optional<Path> scoped = runDir(finished.coord(), finished.dir(), finished.buildNumber());
            if (scoped.isPresent()) return scoped;
        }
        return findRunDir(locator);
    }

    private Optional<Path> getRunPath(String idOrLocator) {
        if (idOrLocator == null || idOrLocator.isBlank()) return Optional.empty();
        Optional<Path> byDir = findRunDir(idOrLocator);
        if (byDir.isPresent()) return byDir;
        for (Path dir : entryDirs()) {
            Optional<BuildRecord> r = readRecord(dir);
            if (r.isPresent() && idOrLocator.equals(r.get().id())) return Optional.of(dir);
        }
        return Optional.empty();
    }

    private Optional<Path> findRunDir(String locator) {
        if (!validLocator(locator)) return Optional.empty();
        if (ProjectBuilds.validRunDirName(locator)) {
            try {
                long n = Long.parseLong(locator);
                return ProjectBuilds.findRunDirByNumber(buildsRoot, n);
            } catch (NumberFormatException ignored) {
            }
        }
        return Optional.empty();
    }

    private List<Path> entryDirs() {
        return ProjectBuilds.listAllRuns(buildsRoot);
    }

    private static Optional<BuildRecord> readRecord(Path dir) {
        Path record = dir.resolve(RECORD);
        if (!Files.isRegularFile(record)) return Optional.empty();
        try {
            return Optional.of(Json.read(Files.readString(record, StandardCharsets.UTF_8)));
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
                } catch (RuntimeException ignored) {
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
        try (Stream<Path> w = Files.walk(dir)) {
            return w.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static void deleteTreeQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> w = Files.walk(dir)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static boolean isArtifactName(String name) {
        return TEST_RESULTS_MD.equals(name) || LOCKFILE.equals(name) || DIAGNOSTICS_TXT.equals(name);
    }

    private static boolean validLocator(String locator) {
        if (locator == null || locator.isBlank() || locator.startsWith(".")) return false;
        if (locator.indexOf('/') >= 0 || locator.indexOf('\\') >= 0 || locator.contains("..")) return false;
        return true;
    }

    private static BuildRecord withId(BuildRecord r, String id) {
        return r.withId(id);
    }
}
