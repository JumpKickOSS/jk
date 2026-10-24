// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.builds.MetricsHarvest;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.engine.api.BuildHistoryKinds;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
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
 * the build-number directory name. On complete, {@link #current} requests a {@link MetricsHarvest}.
 *
 * <p>A numbered run admitted through {@link #beginLater} has no directory until it has run for
 * {@link #STUB_DELAY_MILLIS}: a run that ends sooner is finished by {@link #complete}, which writes the
 * whole entry at once, or by {@link #confirm}, which leaves a no-op as one line of {@link
 * #RUN_LOG} instead.
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

    /** The diagnostics past {@link #DIAGNOSTICS_BUDGET}, a JSON array the record names as {@code diagnosticsSpill}. */
    public static final String DIAGNOSTICS_SPILL = "diagnostics-spill.json";

    /** Characters of diagnostic JSON a {@code record.json} carries inline; the rest spill. */
    static final long DIAGNOSTICS_BUDGET = 256 * 1024;

    /** Every test's outcome this run, {@link RunSnapshots#encodeTests}. */
    public static final String TEST_OUTCOMES_TSV = "test-outcomes.tsv";

    /** Every project file's content hash at the end of this run, {@link RunSnapshots#encodeSources}. */
    public static final String SOURCES_TSV = "sources.tsv";

    private static final String RECORD = "record.json";

    /** UTC timestamp form stored as {@link BuildRecord#id()} (not the directory name). */
    private static final DateTimeFormatter ID_TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS");

    /**
     * The project home's log of runs that left no directory: one tab-separated line per no-op,
     * {@code <build-number> <finished-at UTC> <kind> <millis> <trigger>}.
     */
    public static final String RUN_LOG = "runs.log";

    /**
     * How long a run admitted through {@link #beginLater} goes without its in-flight entry. Long
     * enough that a no-op (tens of milliseconds) is over before it, short enough that a run a
     * person or an agent could watch has its entry on disk while it does.
     */
    static final long STUB_DELAY_MILLIS = 150;

    private final Path buildsRoot;

    /** Runs admitted through {@link #beginLater} whose in-flight entry is not written yet. */
    private final PendingRuns pending = new PendingRuns((running, header) -> append(running, Snapshot.NONE, header));

    private final long stubDelayMillis;

    /** Whether a finished run requests a {@link MetricsHarvest} of {@link #buildsRoot}. */
    private final boolean harvests;

    /** A journal under {@code buildsRoot} that leaves harvesting to its caller. */
    public BuildJournal(Path buildsRoot) {
        this(buildsRoot, STUB_DELAY_MILLIS, false);
    }

    /** {@code stubDelayMillis}: how long {@link #beginLater} waits before writing the in-flight entry. */
    BuildJournal(Path buildsRoot, long stubDelayMillis) {
        this(buildsRoot, stubDelayMillis, false);
    }

    private BuildJournal(Path buildsRoot, long stubDelayMillis, boolean harvests) {
        this.buildsRoot = buildsRoot.normalize();
        this.stubDelayMillis = stubDelayMillis;
        this.harvests = harvests;
    }

    /** This process's journal: every finished build-like run requests a harvest of its root. */
    public static BuildJournal current() {
        return new BuildJournal(JkDirs.builds(), STUB_DELAY_MILLIS, true);
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

    /**
     * The run directory of {@code buildNumber} in {@code projectDir}'s home: one on disk, or the one
     * a run admitted through {@link #beginLater} will have, which may never exist.
     */
    public Optional<Path> runDir(@Nullable String coord, String projectDir, long buildNumber) {
        if (buildNumber <= 0) return Optional.empty();
        Path planned = plannedRunDir(projectDir, buildNumber);
        if (Files.isDirectory(planned)) return Optional.of(planned);
        return pending.plannedAt(planned) ? Optional.of(planned) : Optional.empty();
    }

    private Path plannedRunDir(@Nullable String projectDir, long buildNumber) {
        Path home = ProjectBuilds.projectHome(
                buildsRoot, null, Path.of(projectDir == null || projectDir.isBlank() ? "." : projectDir));
        return ProjectBuilds.runDir(home, buildNumber);
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
        return append(record, snapshot, null);
    }

    /** {@link #append}, opening the run's {@code details.jsonl} with {@code transcriptHeader} when given. */
    private @Nullable String append(BuildRecord record, Snapshot snapshot, @Nullable String transcriptHeader) {
        try {
            Path projectPath = Path.of(record.dir() == null || record.dir().isBlank() ? "." : record.dir());
            String coord = record.coord() == null || record.coord().isBlank() ? "unknown:unknown" : record.coord();
            Path home = ProjectBuilds.projectHome(buildsRoot, coord, projectPath);
            OwnerOnlyFiles.createDirectories(home.resolve(ProjectBuilds.RUNS));
            ProjectBuilds.writeIdentity(home, coord, projectPath);
            long stampMillis = record.finishedAt() > 0
                    ? record.finishedAt()
                    : (record.startedAt() > 0 ? record.startedAt() : Clock.SYSTEM.millis());
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
                OwnerOnlyFiles.createDirectory(tmp);
            } catch (FileAlreadyExistsException e) {
                // A stale staging dir from a killed engine; numbers are allocated under a lock and
                // job dirs carry the pid, so no live writer shares this name.
                PathUtil.deleteRecursively(tmp);
                OwnerOnlyFiles.createDirectory(tmp);
            }
            try {
                // Non-build rows share a millisecond timestamp. The directory name is unique
                // (pid and request id), so that is the id the sweep looks up.
                BuildRecord withIds = withId(record.withBuildNumber(n), n > 0 ? timestamp : dirName);
                writeRecord(tmp, withIds);
                if (record.dir() != null && !record.dir().isBlank()) {
                    // One line a reader scanning for a checkout's run takes before the record.
                    Files.writeString(tmp.resolve(ProjectBuilds.CHECKOUT), record.dir() + "\n", StandardCharsets.UTF_8);
                }
                writeSnapshot(tmp, snapshot, record.running() ? null : previousRun(target));
                if (transcriptHeader != null) {
                    Files.writeString(
                            tmp.resolve(ProjectBuilds.DETAILS), transcriptHeader + "\n", StandardCharsets.UTF_8);
                }
                if (record.running()) {
                    Files.writeString(
                            tmp.resolve(ENGINE_OWNER), EngineOwner.current().line(), StandardCharsets.UTF_8);
                } else {
                    RunMetricsToml.write(tmp, withIds);
                }
                if (Files.isDirectory(target)) {
                    // A live entry (its details.jsonl is being written) is only ever rewritten in
                    // place, never deleted from under its readers.
                    PathUtil.deleteRecursively(tmp);
                    return complete(dirName, withIds, snapshot) ? dirName : null;
                }
                OwnerOnlyFiles.seal(tmp);
                move(tmp, target);
                if (harvests
                        && !record.running()
                        && !record.synthetic()
                        && BuildHistoryKinds.isBuildLike(record.kind()))
                    MetricsHarvest.get().request(buildsRoot);
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
     * {@link #begin}, after {@link #STUB_DELAY_MILLIS}: the locator now, the in-flight entry only if
     * the run is still going then. Most builds of an unchanged tree finish first, and those leave
     * no directory to write and delete. A run with no build number begins at once.
     */
    public @Nullable String beginLater(BuildRecord running) {
        if (running == null) return null;
        long requestId = running.requestId();
        if (requestId <= 0
                || running.buildNumber() <= 0
                || running.dir() == null
                || running.dir().isBlank()) {
            return begin(running);
        }
        try {
            pending.defer(requestId, running, plannedRunDir(running.dir(), running.buildNumber()), stubDelayMillis);
            return ProjectBuilds.runDirName(running.buildNumber());
        } catch (RuntimeException e) {
            return begin(running);
        }
    }

    /**
     * Hold {@code header}, the first line of the transcript the engine writes for request {@code
     * requestId}, until its run has a directory: it is written with the entry, and dropped with a
     * no-op. False when the run is not pending, and the caller writes the line itself.
     */
    public boolean holdTranscriptHeader(long requestId, String header) {
        return pending.hold(requestId, header);
    }

    /** Settle every pending entry planned at {@code dir}'s run {@code n}: a run deleted before it began never begins. */
    private void settlePendingAt(@Nullable String dir, long n) {
        if (n <= 0 || dir == null || dir.isBlank()) return;
        try {
            pending.settleAt(plannedRunDir(dir, n));
        } catch (RuntimeException e) {
            Log.debug("settle pending: best-effort", e);
        }
    }

    /**
     * Finish {@code finished} as a no-op: a line in its project's {@link #RUN_LOG} in place of a run
     * directory. Only a run whose entry is still unwritten qualifies; for one that already has its
     * directory (it ran past {@link #STUB_DELAY_MILLIS}, or began through {@link #begin}) this
     * returns false and writes nothing, and the caller {@link #complete completes} it as any other.
     */
    public boolean confirm(String locator, BuildRecord finished) {
        if (finished == null || !validLocator(locator) || finished.buildNumber() <= 0) return false;
        PendingRuns.Entry entry = pending.settle(finished.requestId());
        if (entry == null || Files.exists(entry.planned)) return false;
        pending.written(finished.requestId());
        PendingRuns.logNoop(entry.planned, finished);
        return true;
    }

    /**
     * Replace an in-flight entry with its finished record. {@code locator} is the build-number
     * directory name returned by {@link #begin}. Preserves the timestamp {@code id} from the
     * in-flight record when {@code finished.id()} is blank.
     */
    public boolean complete(String locator, BuildRecord finished, Snapshot snapshot) {
        if (!validLocator(locator) || finished == null) return false;
        PendingRuns.Entry never = pending.settle(finished.requestId());
        if (never != null && !Files.isDirectory(never.planned)) {
            if (never.planned.equals(plannedRunDir(finished.dir(), finished.buildNumber()))) {
                // Never begun: the whole entry is written now, where admission planned it.
                if (append(finished, snapshot, never.transcriptHeader()) == null) return false;
                pending.written(finished.requestId());
                return true;
            }
            // The finished record names the checkout by another path than admission did (a
            // symlink): begin where admission would have, and complete that entry as always.
            if (append(never.running, Snapshot.NONE, never.transcriptHeader()) == null) return false;
        }
        if (never != null) pending.written(finished.requestId());
        Path target = resolveForComplete(locator, finished).orElse(null);
        if (target == null || !Files.isDirectory(target)) return false;
        Path parent = Objects.requireNonNull(target.getParent(), "journal entry has no parent");
        String dirName = target.getFileName().toString();
        Path tmp = parent.resolve("." + dirName + ".complete.tmp");
        try {
            PathUtil.deleteRecursively(tmp);
            OwnerOnlyFiles.createDirectory(tmp);
            String timestamp = finished.id();
            if (timestamp == null || timestamp.isBlank()) {
                timestamp = readHead(target).map(h -> h.record().id()).orElse(null);
            }
            if (timestamp == null || timestamp.isBlank()) {
                long stampMillis = finished.finishedAt() > 0
                        ? finished.finishedAt()
                        : (finished.startedAt() > 0 ? finished.startedAt() : System.currentTimeMillis());
                timestamp = ID_TS.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(stampMillis), ZoneOffset.UTC));
            }
            long n = finished.buildNumber() > 0 ? finished.buildNumber() : ProjectBuilds.runNumberOf(target);
            BuildRecord toWrite = withId(finished.withBuildNumber(n), timestamp);
            writeRecord(tmp, toWrite);
            writeSnapshot(tmp, snapshot, previousRun(target));
            RunMetricsToml.write(tmp, toWrite);
            OwnerOnlyFiles.seal(tmp);
            // Every file lands by rename from a sibling under the same runs/ tree: a reader polling
            // the record sees the old bytes or the new ones, and a Windows handle on it is waited
            // out rather than failing the run. The spill lands before the record that names it,
            // and a stale one goes after.
            boolean spilled = Files.isRegularFile(tmp.resolve(DIAGNOSTICS_SPILL));
            moveIfPresent(tmp, target, DIAGNOSTICS_SPILL);
            AtomicWrites.moveInto(tmp.resolve(RECORD), target.resolve(RECORD));
            if (!spilled) Files.deleteIfExists(target.resolve(DIAGNOSTICS_SPILL));
            Files.deleteIfExists(target.resolve(ENGINE_OWNER));
            moveIfPresent(tmp, target, ProjectBuilds.METRICS);
            if (snapshot != null) {
                if (snapshot.resultsMd() != null) moveIfPresent(tmp, target, RESULTS_MD);
                if (snapshot.lockfile() != null) moveIfPresent(tmp, target, ManifestPaths.LOCK);
                if (snapshot.diagnosticsText() != null) moveIfPresent(tmp, target, DIAGNOSTICS_TXT);
                moveIfPresent(tmp, target, TEST_OUTCOMES_TSV);
                moveIfPresent(tmp, target, SOURCES_TSV);
            }
            PathUtil.deleteRecursively(tmp);
            // Synthetic optimize/calibrate fixtures must not train host ETA aggregates.
            if (harvests && !toWrite.synthetic() && BuildHistoryKinds.isBuildLike(toWrite.kind())) {
                MetricsHarvest.get().request(buildsRoot);
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
            // A numbered row lives under its project's runs/<n>; a lock or format row under a
            // j-… directory only its record id finds.
            Optional<Path> dir = r.buildNumber() > 0
                    ? runDir(ProjectBuilds.runDirName(r.buildNumber()), r)
                    : (r.id() == null ? Optional.empty() : locateById(r.id()));
            if (dir.isEmpty()) continue;
            String locator = dir.get().getFileName().toString();
            EngineOwner owner = ownerOf(dir.get());
            if (owner != null && alive.test(owner)) {
                live++;
                continue;
            }
            BuildRecord whole = readRecord(dir.get()).orElse(r);
            if (complete(locator, whole.abandoned(now, jkVersion), Snapshot.NONE)) abandoned++;
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
            AtomicWrites.moveInto(from.resolve(name), to.resolve(name));
        }
    }

    /** {@code record.json}, and {@link #DIAGNOSTICS_SPILL} when the diagnostics overran their budget. */
    private static void writeRecord(Path dir, BuildRecord record) throws IOException {
        Json.Written written = Json.write(record, DIAGNOSTICS_BUDGET, DIAGNOSTICS_SPILL);
        if (written.spill() != null) {
            Files.writeString(dir.resolve(DIAGNOSTICS_SPILL), written.spill(), StandardCharsets.UTF_8);
        }
        Files.writeString(dir.resolve(RECORD), written.record(), StandardCharsets.UTF_8);
    }

    /**
     * The snapshot files of a run. {@code previous} is the run before it, when one is on disk: a
     * source list or lockfile byte-identical to that run's is linked to its copy rather than
     * written again. Most runs change neither, and the lockfile alone is tens of kilobytes a run.
     */
    private static void writeSnapshot(Path dir, Snapshot s, @Nullable Path previous) throws IOException {
        if (s == null) return;
        if (s.testOutcomes() != null) {
            Files.writeString(dir.resolve(TEST_OUTCOMES_TSV), s.testOutcomes(), StandardCharsets.UTF_8);
        }
        if (s.sources() != null) {
            shareOrWrite(dir.resolve(SOURCES_TSV), s.sources().getBytes(StandardCharsets.UTF_8), previous);
        }
        if (s.resultsMd() != null && Files.isRegularFile(s.resultsMd())) {
            Files.copy(s.resultsMd(), dir.resolve(RESULTS_MD), StandardCopyOption.REPLACE_EXISTING);
        }
        if (s.lockfile() != null && Files.isRegularFile(s.lockfile())) {
            shareOrWrite(dir.resolve(ManifestPaths.LOCK), Files.readAllBytes(s.lockfile()), previous);
        }
        if (s.diagnosticsText() != null && !s.diagnosticsText().isBlank()) {
            Files.writeString(dir.resolve(DIAGNOSTICS_TXT), s.diagnosticsText(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Write {@code bytes} at {@code target}, or link it to the file of the same name in {@code
     * previous} when that holds exactly these bytes. A run's files are never rewritten once it
     * completes, so a shared inode cannot change under either run, and pruning one run leaves the
     * other's copy whole. A filesystem without links gets the bytes.
     */
    static void shareOrWrite(Path target, byte[] bytes, @Nullable Path previous) throws IOException {
        if (previous != null) {
            Path prior = previous.resolve(target.getFileName().toString());
            try {
                if (Files.isRegularFile(prior, LinkOption.NOFOLLOW_LINKS)
                        && Files.size(prior) == bytes.length
                        && Arrays.equals(Files.readAllBytes(prior), bytes)) {
                    Files.createLink(target, prior);
                    return;
                }
            } catch (IOException | UnsupportedOperationException noLink) {
                Files.deleteIfExists(target);
            }
        }
        Files.write(target, bytes);
    }

    /** How many lower-numbered directories {@link #previousRun} tries for a finished record. */
    private static final int PREVIOUS_RUN_SCAN = 8;

    /**
     * The nearest lower-numbered run directory beside {@code runDir} with a record, or {@code null}.
     * Listed rather than counted down: a run of no-ops leaves numbers with no directory.
     */
    static @Nullable Path previousRun(Path runDir) {
        Path runs = runDir.getParent();
        if (runs == null) return null;
        long n = ProjectBuilds.runNumberOf(runDir);
        List<Long> lower = new ArrayList<>();
        try {
            PathUtil.forEachChild(runs, (p, attrs) -> {
                String name = p.getFileName().toString();
                if (!attrs.isDirectory() || !ProjectBuilds.validRunDirName(name)) return true;
                try {
                    long k = Long.parseLong(name);
                    if (k > 0 && k < n) lower.add(k);
                } catch (NumberFormatException ignored) {
                    // not a run number
                }
                return true;
            });
        } catch (IOException | RuntimeException e) {
            return null;
        }
        lower.sort(Comparator.reverseOrder());
        for (int i = 0; i < Math.min(lower.size(), PREVIOUS_RUN_SCAN); i++) {
            Path candidate = runs.resolve(Long.toString(lower.get(i)));
            if (Files.isRegularFile(candidate.resolve(RECORD))) return candidate;
        }
        return null;
    }

    /**
     * Every non-synthetic record's head, newest first: module rows, tasks, coverage and diagnostics
     * read back empty. {@link #get} reads a whole record.
     */
    public List<BuildRecord> list() {
        List<BuildRecord> out = new ArrayList<>();
        for (Head h : heads(Integer.MAX_VALUE)) out.add(h.record());
        return out;
    }

    /**
     * A record's fields before its tail ({@link BuildRecord#modules}, steps, coverage and
     * diagnostics empty), with the module counts its writer stamped there.
     */
    public record Head(BuildRecord record, int moduleCount, int failedModules) {
        /** The head of a record text that carries no tail mark, counted from its own module rows. */
        static Head of(BuildRecord whole) {
            int failed =
                    (int) whole.modules().stream().filter(m -> !m.success()).count();
            return new Head(whole, whole.modules().size(), failed);
        }
    }

    /**
     * The newest {@code limit} non-synthetic heads, newest first. {@code entryDirs()} is
     * newest-first by run-dir mtime (one stat per dir), so stopping after {@code limit} loads is
     * O(limit) head reads.
     */
    public List<Head> heads(int limit) {
        List<Head> out = new ArrayList<>();
        if (limit <= 0) return out;
        for (Path dir : entryDirs()) {
            if (out.size() >= limit) break;
            Optional<Head> head = readHead(dir);
            // Defense in depth: never surface optimize/calibrate fixtures.
            if (head.isPresent() && !head.get().record().synthetic()) out.add(head.get());
        }
        // Newest first by startedAt / finishedAt
        out.sort(Comparator.comparingLong((Head h) -> h.record().finishedAt() > 0
                        ? h.record().finishedAt()
                        : h.record().startedAt())
                .reversed());
        return out;
    }

    /** How many earlier runs of a project the coverage delta looks back through. */
    static final int PREVIOUS_COVERAGE_LOOKBACK = 50;

    /**
     * The newest earlier run of {@code current}'s checkout that measured coverage — the baseline
     * its {@code jk-results.md} shows deltas against. Runs without coverage in between and runs
     * from another worktree of the same id are skipped, so a plain build does not erase the
     * comparison; the scan stops after {@value #PREVIOUS_COVERAGE_LOOKBACK} runs.
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
            boolean candidate = readHead(run)
                    .map(Head::record)
                    .filter(r -> !r.running() && JournalLineage.sameCheckout(r.dir(), current.dir()))
                    .isPresent();
            if (!candidate) continue;
            Optional<BuildRecord> record =
                    readRecord(run).filter(r -> !r.coverage().isEmpty());
            if (record.isPresent()) return record;
        }
        return Optional.empty();
    }

    /**
     * The run before {@code current} from the same origin, with the directory its artifacts sit
     * in; see {@link JournalLineage#previousInSession}.
     */
    public Optional<JournalLineage.Previous> previousInSession(BuildRecord current) {
        return JournalLineage.previousInSession(buildsRoot, dir -> readHead(dir).map(Head::record), current)
                .flatMap(p -> readRecord(p.dir()).map(r -> new JournalLineage.Previous(r, p.dir())));
    }

    /** Look up by build-number directory name, {@code j-…} job directory, or record {@code id}. */
    public Optional<BuildRecord> get(String idOrLocator) {
        return switch (kindOf(idOrLocator)) {
            case RECORD_ID -> readById(idOrLocator, this::readRecord, BuildRecord::id);
            case RUN_NUMBER, JOB_DIR -> findRunDir(idOrLocator).flatMap(this::readRecord);
            case INVALID -> Optional.empty();
        };
    }

    /**
     * Run dirs located by {@link #rawFinishedRecordByRequestId} — a wait loop re-reads one
     * {@code record.json} head per poll instead of re-scanning the journal. Bounded residue: cleared
     * wholesale once full — safe for a value cache (nothing holds these entries).
     */
    private final ConcurrentHashMap<Long, Path> runDirsByRequestId = new ConcurrentHashMap<>();

    /**
     * Raw JSON of the <em>finished</em> record stamped with {@code requestId}, or empty while the
     * run is absent or still {@code running}. The run dir is found with one newest-first scan of
     * record heads and memoized, so a poll reads one head until the run finishes.
     */
    public Optional<String> rawFinishedRecordByRequestId(long requestId) {
        if (requestId <= 0) return Optional.empty();
        Path dir = runDirsByRequestId.get(requestId);
        if (dir != null) {
            if (Files.isDirectory(dir)) return readFinished(dir, requestId);
            runDirsByRequestId.remove(requestId, dir); // pruned since memoized — re-locate
        }
        for (RawLoaded l : loadNewestRaw(200, null)) {
            if (scanLong(l.head(), "requestId") != requestId) continue;
            if (runDirsByRequestId.size() >= 1_024) runDirsByRequestId.clear();
            runDirsByRequestId.put(requestId, l.dir());
            return scanTrue(l.head(), "running") ? Optional.empty() : readRaw(l.dir());
        }
        return Optional.empty();
    }

    /** The whole record when its head carries {@code requestId} and says it finished. */
    private Optional<String> readFinished(Path dir, long requestId) {
        String head = rawHead(dir);
        if (head == null || scanLong(head, "requestId") != requestId || scanTrue(head, "running")) {
            return Optional.empty();
        }
        return readRaw(dir);
    }

    /**
     * A record's head as text, cut where its tail starts so a lexical scan never reaches into a
     * module row or a diagnostic; {@code null} when the record is unreadable or not an object.
     */
    private @Nullable String rawHead(Path dir) {
        Path record = dir.resolve(RECORD);
        if (!Files.isRegularFile(record)) return null;
        recordReads.incrementAndGet();
        try {
            String head = ProjectBuilds.recordHead(record).strip();
            if (head.isEmpty() || head.charAt(0) != '{') return null;
            int cut = Json.tailStart(head);
            return cut < 0 ? head : head.substring(0, cut);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * A whole record's text, the diagnostics its writer spilled to {@link #DIAGNOSTICS_SPILL} merged
     * inline; empty when torn or not an object, so it never rides into a JSON array verbatim. A
     * record that names no spill is returned as written, never parsed.
     */
    private Optional<String> readRaw(Path dir) {
        recordReads.incrementAndGet();
        try {
            String json = Files.readString(dir.resolve(RECORD), StandardCharsets.UTF_8)
                    .strip();
            if (json.isEmpty() || json.charAt(0) != '{' || json.charAt(json.length() - 1) != '}') {
                return Optional.empty();
            }
            String spill = readSpill(dir, json);
            return Optional.of(spill == null ? json : Json.writeWithSpill(json, spill));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The spill {@code json} (a record in {@code dir}) names in its head, or {@code null} when it names none. */
    private static @Nullable String readSpill(Path dir, String json) throws IOException {
        int cut = Json.tailStart(json);
        String head = cut < 0 ? json : json.substring(0, cut);
        if (!DIAGNOSTICS_SPILL.equals(scanString(head, "diagnosticsSpill"))) return null;
        Path spill = dir.resolve(DIAGNOSTICS_SPILL);
        return Files.isRegularFile(spill) ? Files.readString(spill, StandardCharsets.UTF_8) : null;
    }

    /** {@link #get}'s record as its whole raw JSON, as {@link #rawRecords} returns each row. */
    public Optional<String> rawRecord(String idOrLocator) {
        return switch (kindOf(idOrLocator)) {
            case RECORD_ID -> readById(idOrLocator, this::readRaw, raw -> scanString(raw, "id"));
            case RUN_NUMBER, JOB_DIR -> findRunDir(idOrLocator).flatMap(this::readRaw);
            case INVALID -> Optional.empty();
        };
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
            readRaw(l.dir()).ifPresent(out::add);
        }
        return out;
    }

    /** A record's head text with its run dir and a lexical sort key — no {@link BuildRecord} graph built. */
    private record RawLoaded(String head, Path dir, long sortKey) {}

    /**
     * The raw-only twin of {@link #heads}: the synthetic filter, {@code filter} and the
     * newest-first sort are lexical scans of each record's head ({@link #rawHead}), so a caller
     * reads a whole record only for the rows it keeps.
     */
    private List<RawLoaded> loadNewestRaw(int limit, @Nullable Predicate<String> filter) {
        List<RawLoaded> out = new ArrayList<>();
        for (Path dir : entryDirs()) {
            if (out.size() >= limit) break;
            String head = rawHead(dir);
            if (head == null) continue;
            index(scanString(head, "id"), dir);
            if (BuildRecord.isSyntheticTrigger(scanString(head, "trigger"))) continue;
            if (filter != null && !filter.test(head)) continue;
            long sort = scanLong(head, "finishedAt");
            if (sort <= 0) sort = scanLong(head, "startedAt");
            out.add(new RawLoaded(head, dir, sort));
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
     * First {@code "name": "<value>"} occurrence, lexically, unescaped; null when absent or not a
     * string.
     */
    static @Nullable String scanString(String json, String name) {
        int start = scanValueStart(json, name);
        if (start < 0 || start >= json.length() || json.charAt(start) != '"') return null;
        StringBuilder value = new StringBuilder();
        int at = start + 1;
        while (at < json.length()) {
            char c = json.charAt(at);
            if (c == '"') return value.toString();
            if (c != '\\' || at + 1 >= json.length()) {
                value.append(c);
                at++;
                continue;
            }
            char e = json.charAt(at + 1);
            if (e == 'u' && at + 6 <= json.length()) {
                value.append((char) Integer.parseInt(json.substring(at + 2, at + 6), 16));
                at += 6;
                continue;
            }
            value.append(
                    switch (e) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        default -> e;
                    });
            at += 2;
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
                settlePendingAt(dir, n);
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
            Optional<BuildRecord> record = readHead(dir).map(Head::record);
            if (record.map(BuildRecord::running).orElse(false)) continue;
            entries.add(new Entry(dir, entryMillis(dir, record, nowMillis), sizeOf(dir)));
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
            case RECORD_ID -> locateById(locator);
            case INVALID -> Optional.empty();
        };
    }

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
    private Optional<Path> locateById(String id) {
        Path indexed = runDirsById.get(id);
        if (indexed != null) {
            if (carriesId(indexed, id)) return Optional.of(indexed);
            runDirsById.remove(id, indexed); // pruned or rewritten since indexed — re-locate
        }
        for (Path dir : entryDirs()) {
            if (carriesId(dir, id)) return Optional.of(dir);
        }
        return Optional.empty();
    }

    /**
     * As {@link #locateById}, reading the indexed record whole with {@code read} so a remembered id
     * costs one read; {@code idOf} is the id the read record carries.
     */
    private <T> Optional<T> readById(String id, Function<Path, Optional<T>> read, Function<T, @Nullable String> idOf) {
        Path indexed = runDirsById.get(id);
        if (indexed != null) {
            Optional<T> r = read.apply(indexed).filter(found -> id.equals(idOf.apply(found)));
            if (r.isPresent()) return r;
            runDirsById.remove(id, indexed);
        }
        return locateById(id).flatMap(read);
    }

    private boolean carriesId(Path dir, String id) {
        return readHead(dir).map(h -> id.equals(h.record().id())).orElse(false);
    }

    /**
     * Non-build journal dirs: {@code j-<timestamp>-<pid>[-<requestId>]} (no run-number
     * allocation). The pid keeps two engines on one builds root apart: request ids are
     * engine-local, and two lock jobs in one millisecond would otherwise share a directory.
     */
    static String jobDirName(String timestamp, long requestId) {
        String base = "j-" + timestamp + "-" + ProcessHandle.current().pid();
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

    /** A whole record, the diagnostics its writer spilled to {@link #DIAGNOSTICS_SPILL} included. */
    private Optional<BuildRecord> readRecord(Path dir) {
        Path record = dir.resolve(RECORD);
        if (!Files.isRegularFile(record)) return Optional.empty();
        recordReads.incrementAndGet();
        try {
            String json = Files.readString(record, StandardCharsets.UTF_8);
            BuildRecord parsed = Json.read(json, readSpill(dir, json));
            index(parsed.id(), dir);
            return Optional.of(parsed);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * A record's {@link Head}, read from the first {@link ProjectBuilds#RECORD_HEAD_BYTES}; a head
     * longer than that is read from the whole file.
     */
    private Optional<Head> readHead(Path dir) {
        Path record = dir.resolve(RECORD);
        if (!Files.isRegularFile(record)) return Optional.empty();
        recordReads.incrementAndGet();
        try {
            Head head = Json.readHead(ProjectBuilds.recordHead(record));
            if (head == null) {
                String whole = Files.readString(record, StandardCharsets.UTF_8);
                head = Json.readHead(whole);
                if (head == null) head = Head.of(Json.read(whole));
            }
            index(head.record().id(), dir);
            return Optional.of(head);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** When the run under {@code dir} happened, from its record {@code r} when it has one. */
    private static long entryMillis(Path dir, Optional<BuildRecord> r, long fallback) {
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
