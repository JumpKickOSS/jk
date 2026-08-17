// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Durable per-project build history under {@code ~/.local/state/jk/builds/projects/<key>/}.
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
 * <p>Key = {@link ProjectIdentity#id()} (hybrid: explicit / lock / git / path). Run directories are the
 * plain build number (e.g. {@code 27}), not a timestamp. Absolute path is operational checkout
 * metadata in {@code identity.toml}, not the key.
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

    /** Opaque project id for this checkout ({@link ProjectIdentity#resolve(Path)}). */
    public static String key(Path projectDir) {
        return ProjectIdentity.resolve(projectDir).id();
    }

    /** @deprecated use {@link #key(Path)} — path-only hash is no longer the identity. */
    @Deprecated
    public static String key(String coord, Path projectDir) {
        return ProjectIdentity.resolve(projectDir == null ? Path.of(".") : projectDir)
                .id();
    }

    public static Path projectHome(Path projectDir) {
        return projectHome(buildsRoot(), ProjectIdentity.resolve(projectDir));
    }

    public static Path projectHome(String coord, Path projectDir) {
        return projectHome(projectDir);
    }

    public static Path projectHome(Path buildsRoot, String coord, Path projectDir) {
        return projectHome(buildsRoot, ProjectIdentity.resolve(projectDir));
    }

    public static Path projectHome(Path buildsRoot, ProjectIdentity identity) {
        return projectsRoot(buildsRoot).resolve(identity.id());
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
        ProjectIdentity identity = ProjectIdentity.resolve(abs);
        // Prefer the live coord from the tree when resolve fell back to unknown.
        if ((identity.coord() == null || identity.coord().startsWith("unknown:"))
                && coord != null
                && !coord.isBlank()) {
            identity = new ProjectIdentity(
                    identity.id(), coord.strip(), abs, identity.source(), identity.gitRemote(), identity.gitRelPath());
        }
        Path home = projectHome(buildsRoot, identity);
        Files.createDirectories(home.resolve(RUNS));
        ProjectIdentity.IdentityFile.write(home, identity);
        long n = allocateRunNumber(home);
        Path runDir = runDir(home, n);
        Files.createDirectories(runDir);
        return new RunDir(identity.id(), home, runDir, n, identity.coord(), abs);
    }

    public static void writeIdentity(Path home, String coord, Path projectDir) throws IOException {
        Path abs = projectDir.toAbsolutePath().normalize();
        ProjectIdentity identity = ProjectIdentity.resolve(abs);
        if ((identity.coord() == null || identity.coord().startsWith("unknown:"))
                && coord != null
                && !coord.isBlank()) {
            identity = new ProjectIdentity(
                    identity.id(), coord.strip(), abs, identity.source(), identity.gitRemote(), identity.gitRelPath());
        }
        ProjectIdentity.IdentityFile.write(home, identity);
    }

    /**
     * Allocate this project's next build number.
     *
     * <p>Guarded across <em>processes</em>, not just threads: two engines are routinely alive at
     * once (a displaced engine drains in-flight work while its successor already serves). With a
     * JVM-only lock both could read {@code 7},
     * both write {@code 8}, and the second {@code runs/8} write would delete the first's completed
     * run tree. Threads first, then a file lock — the same nesting {@code AotManifest.withLock}
     * uses, since a second {@code FileChannel.lock()} in one JVM throws.
     */
    public static long allocateRunNumber(Path projectHome) throws IOException {
        Files.createDirectories(projectHome);
        Path f = projectHome.resolve(RUN_NUMBER);
        Path lockPath =
                projectHome.resolve(RUN_NUMBER + ".lock").toAbsolutePath().normalize();
        RUN_NUMBER_LOCK.lock();
        try {
            java.nio.channels.FileChannel ch = null;
            try {
                ch = java.nio.channels.FileChannel.open(
                        lockPath,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.READ);
            } catch (IOException | RuntimeException noLockFile) {
                // No usable lock file (exotic or read-only filesystem): still allocate under the
                // JVM lock rather than failing the build — degrades to the previous
                // single-process guarantee instead of breaking.
                return bumpRunNumber(f);
            }
            try (java.nio.channels.FileChannel channel = ch) {
                java.nio.channels.FileLock fileLock = null;
                try {
                    fileLock = channel.lock();
                } catch (IOException | RuntimeException noFlock) {
                    return bumpRunNumber(f); // e.g. NFS without lockd
                }
                try {
                    return bumpRunNumber(f);
                } finally {
                    fileLock.release();
                }
            }
        } finally {
            RUN_NUMBER_LOCK.unlock();
        }
        // The 0-byte .lock file intentionally stays on disk: unlinking it while another process
        // holds the flock would let a third process lock a fresh inode at the same path.
    }

    private static long bumpRunNumber(Path f) throws IOException {
        long next = 1;
        if (Files.isRegularFile(f)) {
            try {
                next = Long.parseLong(
                                Files.readString(f, StandardCharsets.UTF_8).trim())
                        + 1;
            } catch (NumberFormatException ignored) {
                next = 1;
            }
        }
        if (next < 1) next = 1;
        AtomicWrites.replace(f, Long.toString(next) + "\n");
        return next;
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

    /**
     * Project homes to use for metrics harvest / global loadAll — at most one home per absolute
     * checkout path. When lock re-key left a stale sibling home for the same path (different id),
     * keep the preferred one (source=lock, then most runs, then newest identity) so one-sample
     * outliers cannot re-enter aggregates.
     */
    public static List<Path> listProjectHomesForMetrics(Path buildsRoot) {
        List<Path> all = listProjectHomes(buildsRoot);
        if (all.size() <= 1) return all;
        Map<String, Path> bestByPath = new java.util.LinkedHashMap<>();
        Map<String, Long> scoreByPath = new HashMap<>();
        List<Path> noPath = new ArrayList<>();
        for (Path home : all) {
            var idf = ProjectIdentity.IdentityFile.read(home);
            if (idf.isEmpty() || idf.get().path() == null || idf.get().path().isBlank()) {
                noPath.add(home);
                continue;
            }
            String pathKey;
            try {
                pathKey = Path.of(idf.get().path()).toAbsolutePath().normalize().toString();
            } catch (RuntimeException e) {
                noPath.add(home);
                continue;
            }
            long score = metricsHomeScore(home, idf.get());
            Long prev = scoreByPath.get(pathKey);
            if (prev == null || score > prev) {
                scoreByPath.put(pathKey, score);
                bestByPath.put(pathKey, home);
            }
        }
        List<Path> out = new ArrayList<>(bestByPath.values());
        out.addAll(noPath);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /**
     * Higher is better: source (lock > git > path), then complete id, then <em>recency</em>
     * (newest run's mtime), then run count. Recency outranks run count on equal-source ties: a
     * lock→lock re-key leaves the stale home with more accumulated runs than the active one, and
     * preferring raw count starved the active home of harvest until its samples were reaped
     *. Run number is not comparable across homes (each restarts at 1), so wall mtime is
     * the recency signal.
     */
    static long metricsHomeScore(Path home, ProjectIdentity.IdentityFile idf) {
        long score = 0;
        if (idf.source() != null && "lock".equalsIgnoreCase(idf.source())) score += 4_000_000_000_000_000L;
        if (idf.source() != null && "git".equalsIgnoreCase(idf.source())) score += 2_000_000_000_000_000L;
        if (idf.id() != null && !idf.id().isBlank()) score += 1_000_000_000_000_000L;
        List<Path> runs = listRuns(home);
        long newestSec = 0;
        if (!runs.isEmpty()) {
            try {
                newestSec = Files.getLastModifiedTime(runs.getFirst()).to(java.util.concurrent.TimeUnit.SECONDS);
            } catch (IOException ignored) {
                // recency unavailable — fall through to run count
            }
        }
        // epoch seconds (< ~4.3e9 until year 2106) * 1e4 stays well under the 1e15 id tier.
        score += Math.max(0, Math.min(newestSec, 4_294_967_295L)) * 10_000L;
        score += Math.min(runs.size(), 9_999);
        return score;
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
        // Decorate-sort-undecorate: mtimeOf is a stat(2), and a comparator key extractor is
        // re-evaluated O(n log n) times — ~44k syscalls for 2000 runs instead of 2000.
        record Stamped(Path path, long mtime) {}
        List<Stamped> stamped = new ArrayList<>(all.size());
        for (Path p : all) stamped.add(new Stamped(p, mtimeOf(p)));
        stamped.sort(Comparator.comparingLong(Stamped::mtime).reversed());
        List<Path> sorted = new ArrayList<>(stamped.size());
        for (Stamped s : stamped) sorted.add(s.path());
        return sorted;
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
        return findRunDirByName(buildsRoot, runDirName(buildNumber));
    }

    /** Locate a run directory by exact directory name across all projects. */
    public static Optional<Path> findRunDirByName(Path buildsRoot, String dirName) {
        if (dirName == null || dirName.isBlank() || dirName.startsWith(".")) return Optional.empty();
        for (Path home : listProjectHomes(buildsRoot)) {
            Path candidate = home.resolve(RUNS).resolve(dirName);
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

    public record RunDir(String key, Path projectHome, Path runDir, long buildNumber, String coord, Path projectPath) {
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
