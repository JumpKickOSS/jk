// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.builds.MetricsHarvest;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Best-effort build history under {@code ~/.jk/state/builds/projects/&lt;key&gt;/runs/&lt;id&gt;/}
 * with {@code record.json}, {@code details.jsonl}, {@code metrics.toml}, and optional snapshots.
 * Ids are time-sortable; append is atomic temp→move. On complete, requests {@link MetricsHarvest}.
 */
public final class BuildJournal {

    /** Snapshot files a caller may fetch by name; also the traversal whitelist for {@link #artifact}. */
    public static final String TEST_RESULTS_MD = "test-results.md";

    public static final String LOCKFILE = "jk-lock.toml";

    public static final String DIAGNOSTICS_TXT = "diagnostics.txt";

    private static final String RECORD = "record.json";

    private static final DateTimeFormatter ID_TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS");

    private static final int APPEND_RETRIES = 8;

    /** Builds root ({@link JkDirs#builds()}); runs live under projects/.../runs/. */
    private final Path buildsRoot;

    public BuildJournal(Path buildsRoot) {
        this.buildsRoot = buildsRoot.normalize();
    }

    /** The live store at {@code ~/.jk/state/builds/}. */
    public static BuildJournal current() {
        return new BuildJournal(JkDirs.builds());
    }

    public Path buildsRoot() {
        return buildsRoot;
    }

    /** Resolve the absolute path of a run directory (for CLI details binding). */
    public Optional<Path> runDir(String id) {
        return findRunDir(id);
    }

    public Optional<Path> detailsFile(String id) {
        return findRunDir(id).map(d -> d.resolve(ProjectBuilds.DETAILS));
    }

    /** The heavy artifacts to snapshot beside {@code record.json}; any field may be {@code null}. */
    public record Snapshot(Path testResultsMd, Path lockfile, String diagnosticsText) {
        public static final Snapshot NONE = new Snapshot(null, null, null);
    }

    /** What a {@link #prune} pass reclaimed. */
    public record PruneResult(int removedEntries, long removedBytes) {}

    /**
     * Persist {@code record} (its {@code id} is (re)assigned here) plus {@code snapshot}. Returns the
     * assigned id, or {@code null} if persistence failed — never throws.
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
            String stamp = ID_TS.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(stampMillis), ZoneOffset.UTC));
            long n = record.buildNumber() > 0 ? record.buildNumber() : ProjectBuilds.allocateRunNumber(home);
            for (int attempt = 0; attempt < APPEND_RETRIES; attempt++) {
                String id = String.format(java.util.Locale.ROOT, "%04d-%s-%s", n, stamp, randomHex());
                Path target = home.resolve(ProjectBuilds.RUNS).resolve(id);
                Path tmp = home.resolve(ProjectBuilds.RUNS).resolve("." + id + ".tmp");
                try {
                    Files.createDirectory(tmp);
                } catch (FileAlreadyExistsException e) {
                    continue;
                }
                try {
                    Files.writeString(tmp.resolve(RECORD), Json.write(withId(record, id)), StandardCharsets.UTF_8);
                    writeSnapshot(tmp, snapshot);
                    if (!record.running()) writeRunMetricsToml(tmp, record);
                    move(tmp, target);
                    if (!record.running()) MetricsHarvest.get().request();
                    return id;
                } catch (FileAlreadyExistsException e) {
                    deleteTreeQuietly(tmp);
                } catch (IOException e) {
                    deleteTreeQuietly(tmp);
                    return null;
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Open an in-flight journal entry at request-start. Returns the assigned id, or
     * {@code null} on failure.
     */
    public String begin(BuildRecord running) {
        if (running == null) return null;
        return append(running, Snapshot.NONE);
    }

    /**
     * Replace an in-flight entry with its finished record (same id). Returns {@code false} if the
     * entry is missing or the write fails — never throws.
     */
    public boolean complete(String id, BuildRecord finished, Snapshot snapshot) {
        if (!validId(id) || finished == null) return false;
        Path target = findRunDir(id).orElse(null);
        if (target == null || !Files.isDirectory(target)) return false;
        Path parent = target.getParent();
        Path tmp = parent.resolve("." + id + ".complete.tmp");
        try {
            deleteTreeQuietly(tmp);
            Files.createDirectory(tmp);
            Files.writeString(tmp.resolve(RECORD), Json.write(withId(finished, id)), StandardCharsets.UTF_8);
            writeSnapshot(tmp, snapshot);
            writeRunMetricsToml(tmp, finished);
            Path rec = target.resolve(RECORD);
            Files.move(tmp.resolve(RECORD), rec, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (Files.isRegularFile(tmp.resolve(ProjectBuilds.METRICS))) {
                Files.move(
                        tmp.resolve(ProjectBuilds.METRICS),
                        target.resolve(ProjectBuilds.METRICS),
                        StandardCopyOption.REPLACE_EXISTING);
            }
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
            MetricsHarvest.get().request();
            return true;
        } catch (IOException | RuntimeException e) {
            deleteTreeQuietly(tmp);
            return false;
        }
    }

    /**
     * Append host-rate samples into an existing run's {@code metrics.toml} (success path). Best-effort.
     */
    public void appendHostSamples(String id, List<HostSampleLine> samples) {
        if (!validId(id) || samples == null || samples.isEmpty()) return;
        Path run = findRunDir(id).orElse(null);
        if (run == null) return;
        Path metrics = run.resolve(ProjectBuilds.METRICS);
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
                sb.append("host.").append(sanitize(s.key())).append(" = ").append(Math.round(s.ms())).append('\n');
            }
            Files.writeString(metrics, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public record HostSampleLine(String key, double ms) {}

    /** Per-run successful step/module walls for MetricsHarvest (scalars only). */
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
        if (finished.steps() != null) {
            for (BuildRecord.Step s : finished.steps()) {
                appendStepMetrics(sb, s, finished.dir());
            }
        }
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m == null || m.steps() == null) continue;
                for (BuildRecord.Step s : m.steps()) {
                    appendStepMetrics(sb, s, m.dir());
                }
            }
        }
        if (sb.length() > 40) {
            Files.writeString(dir.resolve(ProjectBuilds.METRICS), sb.toString(), StandardCharsets.UTF_8);
        }
    }

    private static void appendStepMetrics(StringBuilder sb, BuildRecord.Step s, String moduleDir) {
        if (s == null || s.millis() <= 0) return;
        if (s.status() == null || !"SUCCESS".equalsIgnoreCase(s.status())) return;
        String step = sanitize(s.name());
        sb.append("step.").append(step).append(".wall-ms = ").append(s.millis()).append('\n');
        if (moduleDir != null && !moduleDir.isBlank()) {
            sb.append("module.")
                    .append(sanitize(moduleDir))
                    .append(".step.")
                    .append(step)
                    .append(".wall-ms = ")
                    .append(s.millis())
                    .append('\n');
        }
    }

    static String sanitize(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9._:/-]+", "_");
    }

    /**
     * On engine start: mark leftover {@code running=true} entries as cancelled (process died).
     * Returns how many were abandoned.
     */
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
            if (complete(r.id(), done, Snapshot.NONE)) n++;
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

    /** Newest-first list of every readable entry. Bounded implicitly by {@link #prune}. */
    public List<BuildRecord> list() {
        List<BuildRecord> out = new ArrayList<>();
        for (Path dir : entryDirs()) {
            readRecord(dir).ifPresent(out::add);
        }
        return out;
    }

    public Optional<BuildRecord> get(String id) {
        if (!validId(id)) return Optional.empty();
        return findRunDir(id).flatMap(BuildJournal::readRecord);
    }

    /**
     * The newest {@code limit} entries' raw {@code record.json} contents (already valid JSON), for
     * assembling a list response verbatim without a parse/re-serialize round-trip.
     */
    public List<String> rawRecords(int limit) {
        List<String> out = new ArrayList<>();
        for (Path dir : entryDirs()) {
            if (out.size() >= limit) break;
            Path rec = dir.resolve(RECORD);
            try {
                if (Files.isRegularFile(rec)) out.add(Files.readString(rec, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    /** The raw {@code record.json} path (for verbatim HTTP passthrough), if the entry exists. */
    public Optional<Path> recordFile(String id) {
        if (!validId(id)) return Optional.empty();
        return findRunDir(id).map(d -> d.resolve(RECORD)).filter(Files::isRegularFile);
    }

    /** A snapshot artifact by whitelisted {@code name}, if present. */
    public Optional<Path> artifact(String id, String name) {
        if (!validId(id) || !isArtifactName(name)) return Optional.empty();
        return findRunDir(id).map(d -> d.resolve(name)).filter(Files::isRegularFile);
    }

    /** Delete one entry. {@code true} if it existed and was removed. */
    public boolean delete(String id) {
        if (!validId(id)) return false;
        Path dir = findRunDir(id).orElse(null);
        if (dir == null || !Files.isDirectory(dir)) return false;
        deleteTreeQuietly(dir);
        return true;
    }

    /**
     * Enforce retention: delete entries older than {@code maxAgeMillis} (0 = no age limit), then, if
     * the remaining total exceeds {@code maxDiskBytes} (0 = no cap), delete oldest-first until under
     * it. Ages come from the id timestamp (no file read), falling back to the dir's mtime.
     *
     * <p>Note: {@link MetricsHarvest} also enforces 50-run / 90-day per project; this is the
     * history-config disk budget path.
     */
    public PruneResult prune(long maxAgeMillis, long maxDiskBytes, long nowMillis) {
        List<Entry> entries = new ArrayList<>();
        for (Path dir : entryDirs()) {
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
                kept.sort(Comparator.comparingLong(Entry::millis)); // oldest first
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

    // ---------------------------------------------------------------- internals

    private record Entry(Path dir, long millis, long size) {}

    private Optional<Path> findRunDir(String id) {
        return ProjectBuilds.findRunDir(buildsRoot, id);
    }

    /** Entry directories (dot-prefixed staging names excluded), newest id first. */
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

    /**
     * Ids look like {@code 0027-20260802T035529298-a1b2}. Parse the timestamp segment after the first
     * dash.
     */
    private long entryMillis(Path dir, long fallback) {
        String name = dir.getFileName().toString();
        int first = name.indexOf('-');
        if (first < 0) return mtime(dir, fallback);
        int second = name.indexOf('-', first + 1);
        String stamp = second > first ? name.substring(first + 1, second) : name.substring(first + 1);
        try {
            return LocalDateTime.parse(stamp, ID_TS).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            return mtime(dir, fallback);
        }
    }

    private static long mtime(Path dir, long fallback) {
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException io) {
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
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
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

    /** Reject ids that could escape via path traversal. */
    private boolean validId(String id) {
        if (id == null || id.isBlank() || id.startsWith(".")) return false;
        if (id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.contains("..")) return false;
        return true;
    }

    private static String randomHex() {
        String hex = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
        return "0000".substring(hex.length()) + hex;
    }

    private static BuildRecord withId(BuildRecord r, String id) {
        return r.withId(id);
    }
}
