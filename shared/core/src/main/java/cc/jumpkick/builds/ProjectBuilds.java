// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Durable per-project build history under {@code ~/.jk/state/builds/projects/<key>/}.
 *
 * <pre>
 *   host-metrics.toml
 *   projects/&lt;key&gt;/
 *     identity.toml
 *     run-number.txt
 *     project-metrics.toml
 *     runs/&lt;run-id&gt;/
 *       record.json
 *       details.jsonl
 *       metrics.toml
 * </pre>
 *
 * <p>Key = SHA-256 of {@code coord + "\\0" + absolute normalized path}. No backward compat with
 * legacy journal/ or metrics.json layouts.
 */
public final class ProjectBuilds {

    public static final String HOST_METRICS = "host-metrics.toml";
    public static final String IDENTITY = "identity.toml";
    public static final String RUN_NUMBER = "run-number.txt";
    public static final String PROJECT_METRICS = "project-metrics.toml";
    public static final String RUNS = "runs";
    public static final String RECORD = "record.json";
    public static final String DETAILS = "details.jsonl";
    public static final String METRICS = "metrics.toml";

    private static final DateTimeFormatter RUN_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);
    private static final ReentrantLock RUN_NUMBER_LOCK = new ReentrantLock();

    private ProjectBuilds() {}

    public static Path buildsRoot() {
        return JkDirs.builds();
    }

    public static Path hostMetricsFile() {
        return hostMetricsFile(buildsRoot());
    }

    public static Path hostMetricsFile(Path buildsRoot) {
        return buildsRoot.resolve(HOST_METRICS);
    }

    public static Path projectsRoot() {
        return projectsRoot(buildsRoot());
    }

    public static Path projectsRoot(Path buildsRoot) {
        return buildsRoot.resolve("projects");
    }

    /** Stable directory name for {@code coord} + {@code projectDir}. */
    public static String key(String coord, Path projectDir) {
        String c = coord == null || coord.isBlank() ? "unknown:unknown" : coord.strip();
        Path p = projectDir == null
                ? Path.of(".")
                : projectDir.toAbsolutePath().normalize();
        String raw = c + "\0" + p;
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig).substring(0, 24);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    public static Path projectHome(String coord, Path projectDir) {
        return projectHome(buildsRoot(), coord, projectDir);
    }

    public static Path projectHome(Path buildsRoot, String coord, Path projectDir) {
        return projectsRoot(buildsRoot).resolve(key(coord, projectDir));
    }

    public static Path projectHome(String key) {
        return projectHome(buildsRoot(), key);
    }

    public static Path projectHome(Path buildsRoot, String key) {
        return projectsRoot(buildsRoot).resolve(key);
    }

    /**
     * Ensure project home exists, write identity, allocate the next run number, create
     * {@code runs/<id>/}. Returns the run directory.
     */
    public static RunDir openRun(String coord, Path projectDir) throws IOException {
        return openRun(buildsRoot(), coord, projectDir);
    }

    public static RunDir openRun(Path buildsRoot, String coord, Path projectDir) throws IOException {
        Path abs = projectDir == null ? Path.of(".").toAbsolutePath().normalize() : projectDir.toAbsolutePath().normalize();
        Path home = projectHome(buildsRoot, coord, abs);
        Files.createDirectories(home.resolve(RUNS));
        writeIdentity(home, coord, abs);
        long n = allocateRunNumber(home);
        String id = String.format(Locale.ROOT, "%04d-%s", n, RUN_TS.format(Instant.now()));
        Path runDir = home.resolve(RUNS).resolve(id);
        Files.createDirectories(runDir);
        return new RunDir(key(coord, abs), home, runDir, id, n, coord == null ? "unknown:unknown" : coord, abs);
    }

    public static void writeIdentity(Path home, String coord, Path projectDir) throws IOException {
        Path p = projectDir.toAbsolutePath().normalize();
        String c = coord == null || coord.isBlank() ? "unknown:unknown" : coord.strip();
        String body = "coord = "
                + quote(c)
                + "\npath = "
                + quote(p.toString())
                + "\n";
        AtomicWrites.replace(home.resolve(IDENTITY), body);
    }

    public static long allocateRunNumber(Path projectHome) throws IOException {
        RUN_NUMBER_LOCK.lock();
        try {
            Path f = projectHome.resolve(RUN_NUMBER);
            long next = 1;
            if (Files.isRegularFile(f)) {
                try {
                    next = Long.parseLong(Files.readString(f, StandardCharsets.UTF_8).trim()) + 1;
                } catch (NumberFormatException ignored) {
                    next = 1;
                }
            }
            if (next < 1) next = 1;
            AtomicWrites.replace(f, Long.toString(next) + "\n");
            return next;
        } finally {
            RUN_NUMBER_LOCK.unlock();
        }
    }

    public static long readRunNumber(Path projectHome) {
        Path f = projectHome.resolve(RUN_NUMBER);
        if (!Files.isRegularFile(f)) return 0;
        try {
            return Long.parseLong(Files.readString(f, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** All project home directories under {@code projects/}. */
    public static List<Path> listProjectHomes() {
        return listProjectHomes(buildsRoot());
    }

    public static List<Path> listProjectHomes(Path buildsRoot) {
        Path root = projectsRoot(buildsRoot);
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Newest-first run directories for a project home. */
    public static List<Path> listRuns(Path projectHome) {
        Path runs = projectHome.resolve(RUNS);
        if (!Files.isDirectory(runs)) return List.of();
        try (Stream<Path> s = Files.list(runs)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Every run dir across all projects, newest-first by directory name. */
    public static List<Path> listAllRuns() {
        return listAllRuns(buildsRoot());
    }

    public static List<Path> listAllRuns(Path buildsRoot) {
        List<Path> all = new ArrayList<>();
        for (Path home : listProjectHomes(buildsRoot)) {
            all.addAll(listRuns(home));
        }
        all.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return all;
    }

    /** Locate a run directory by id across all projects. */
    public static Optional<Path> findRunDir(String runId) {
        return findRunDir(buildsRoot(), runId);
    }

    public static Optional<Path> findRunDir(Path buildsRoot, String runId) {
        if (runId == null || runId.isBlank() || runId.startsWith(".") || runId.contains("..")
                || runId.indexOf('/') >= 0
                || runId.indexOf('\\') >= 0) {
            return Optional.empty();
        }
        for (Path home : listProjectHomes(buildsRoot)) {
            Path candidate = home.resolve(RUNS).resolve(runId);
            if (Files.isDirectory(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    public record RunDir(
            String key, Path projectHome, Path runDir, String runId, long runNumber, String coord, Path projectPath) {
        public Path detailsFile() {
            return runDir.resolve(DETAILS);
        }

        public Path metricsFile() {
            return runDir.resolve(METRICS);
        }

        public Path recordFile() {
            return runDir.resolve(RECORD);
        }

        public Path projectMetricsFile() {
            return projectHome.resolve(PROJECT_METRICS);
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
