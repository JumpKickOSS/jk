// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
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
 *     runs/&lt;build-number&gt;/
 *       record.json
 *       details.jsonl
 *       metrics.toml
 * </pre>
 *
 * <p>Key = SHA-256 of {@code coord + "\\0" + absolute normalized path}. Run directories are the
 * plain build number (e.g. {@code 27}), not a timestamp. No backward compat with legacy layouts.
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

    /** Directory name for a build number ({@code 27}, not zero-padded). */
    public static String runDirName(long buildNumber) {
        return Long.toString(Math.max(0, buildNumber));
    }

    /** Run directory for this project + build number (may not exist yet). */
    public static Path runDir(Path projectHome, long buildNumber) {
        return projectHome.resolve(RUNS).resolve(runDirName(buildNumber));
    }

    public static Path runDir(Path buildsRoot, String coord, Path projectDir, long buildNumber) {
        return runDir(projectHome(buildsRoot, coord, projectDir), buildNumber);
    }

    /**
     * Ensure project home exists, write identity, allocate the next build number, create
     * {@code runs/<build-number>/}.
     */
    public static RunDir openRun(String coord, Path projectDir) throws IOException {
        return openRun(buildsRoot(), coord, projectDir);
    }

    public static RunDir openRun(Path buildsRoot, String coord, Path projectDir) throws IOException {
        Path abs = projectDir == null
                ? Path.of(".").toAbsolutePath().normalize()
                : projectDir.toAbsolutePath().normalize();
        Path home = projectHome(buildsRoot, coord, abs);
        Files.createDirectories(home.resolve(RUNS));
        writeIdentity(home, coord, abs);
        long n = allocateRunNumber(home);
        Path runDir = runDir(home, n);
        Files.createDirectories(runDir);
        return new RunDir(
                key(coord, abs), home, runDir, n, coord == null || coord.isBlank() ? "unknown:unknown" : coord, abs);
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

    /** Newest-first run directories for a project (by numeric build number). */
    public static List<Path> listRuns(Path projectHome) {
        Path runs = projectHome.resolve(RUNS);
        if (!Files.isDirectory(runs)) return List.of();
        try (Stream<Path> s = Files.list(runs)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparingLong(ProjectBuilds::runNumberOf).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Parse build number from a run directory name; non-numeric → 0. */
    public static long runNumberOf(Path runDir) {
        if (runDir == null) return 0;
        try {
            return Long.parseLong(runDir.getFileName().toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Every run dir across all projects, newest-first by directory mtime (build numbers are
     * per-project and not globally comparable).
     */
    public static List<Path> listAllRuns() {
        return listAllRuns(buildsRoot());
    }

    public static List<Path> listAllRuns(Path buildsRoot) {
        List<Path> all = new ArrayList<>();
        for (Path home : listProjectHomes(buildsRoot)) {
            all.addAll(listRuns(home));
        }
        all.sort(Comparator.comparingLong(ProjectBuilds::mtimeOf).reversed());
        return all;
    }

    private static long mtimeOf(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Locate {@code runs/<buildNumber>/} under a specific project home.
     */
    public static Optional<Path> findRunDir(Path projectHome, long buildNumber) {
        if (projectHome == null || buildNumber <= 0) return Optional.empty();
        Path candidate = runDir(projectHome, buildNumber);
        return Files.isDirectory(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    /**
     * Locate a run directory by build-number directory name across all projects.
     * Prefer {@link #findRunDir(Path, long)} when the project is known (numbers collide across projects).
     */
    public static Optional<Path> findRunDirByNumber(Path buildsRoot, long buildNumber) {
        if (buildNumber <= 0) return Optional.empty();
        String name = runDirName(buildNumber);
        for (Path home : listProjectHomes(buildsRoot)) {
            Path candidate = home.resolve(RUNS).resolve(name);
            if (Files.isDirectory(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** True when {@code name} is a safe run directory name (digits only, no traversal). */
    public static boolean validRunDirName(String name) {
        if (name == null || name.isBlank() || name.startsWith(".")) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return false;
        }
        return true;
    }

    public record RunDir(
            String key, Path projectHome, Path runDir, long buildNumber, String coord, Path projectPath) {
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
