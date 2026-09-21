// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

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
 *       jk-results.md
 *       metrics.toml
 * </pre>
 *
 * <p>Key = {@link ProjectIdentity#id()} (hybrid: explicit / lock / git / path). Run directories are the
 * plain build number (e.g. {@code 27}), not a timestamp. Every checkout of one id — each git
 * worktree of a repository whose lock carries the id — shares the home and the build-number
 * sequence; {@code identity.toml} lists the checkouts, and each run's {@code record.json} names
 * the one it ran in ({@link #runCheckout}).
 */
public final class ProjectBuilds {

    public static final String HOST_METRICS = "host-metrics.toml";
    public static final String IDENTITY = "identity.toml";
    public static final String RUN_NUMBER = "run-number.txt";
    public static final String PROJECT_METRICS = "project-metrics.toml";
    public static final String RUNS = "runs";
    public static final String RECORD = "record.json";
    public static final String DETAILS = "details.jsonl";
    public static final String RESULTS = "jk-results.md";
    public static final String METRICS = "metrics.toml";

    private ProjectBuilds() {}

    public static Path buildsRoot() {
        return JkDirs.builds();
    }

    public static Path hostMetricsFile() {
        return hostMetricsFile(buildsRoot());
    }

    /**
     * The lock beside a whole-file ledger ({@code <ledger>.lock}): every writer that reads,
     * folds and replaces the file holds it, so two engines on one builds root cannot lose each
     * other's rows.
     */
    public static Path ledgerLock(Path ledger) {
        return ledger.resolveSibling(ledger.getFileName() + ".lock");
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

    public static Path projectHome(Path projectDir) {
        return projectHome(buildsRoot(), ProjectIdentity.resolve(projectDir));
    }

    public static Path projectHome(@Nullable String coord, Path projectDir) {
        return projectHome(projectDir);
    }

    public static Path projectHome(Path buildsRoot, @Nullable String coord, Path projectDir) {
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

    public static void writeIdentity(Path home, @Nullable String coord, Path projectDir) throws IOException {
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
     * run tree. {@link FileLocks#withLock} is the cross-process guard.
     */
    public static long allocateRunNumber(Path projectHome) throws IOException {
        Files.createDirectories(projectHome);
        Path f = projectHome.resolve(RUN_NUMBER);
        return FileLocks.withLock(projectHome.resolve(RUN_NUMBER + ".lock"), () -> bumpRunNumber(f));
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
        // Durable, and the sharpest case for it: a lost increment lets a later run delete a completed
        // run tree, which is the hazard the flock around this exists for.
        AtomicWrites.replaceDurably(f, Long.toString(next) + "\n");
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
     * outliers cannot re-enter aggregates. A home listing several checkouts claims each of them.
     */
    public static List<Path> listProjectHomesForMetrics(Path buildsRoot) {
        List<Path> all = listProjectHomes(buildsRoot);
        if (all.size() <= 1) return all;
        Map<String, Path> bestByPath = new LinkedHashMap<>();
        Map<String, Long> scoreByPath = new HashMap<>();
        List<Path> noPath = new ArrayList<>();
        for (Path home : all) {
            var idf = ProjectIdentity.IdentityFile.read(home);
            if (idf.isEmpty() || idf.get().checkouts().isEmpty()) {
                noPath.add(home);
                continue;
            }
            long score = metricsHomeScore(home, idf.get());
            for (ProjectIdentity.Checkout checkout : idf.get().checkouts()) {
                String pathKey = checkout.path().toString();
                Long prev = scoreByPath.get(pathKey);
                if (prev == null || score > prev) {
                    scoreByPath.put(pathKey, score);
                    bestByPath.put(pathKey, home);
                }
            }
        }
        List<Path> out = new ArrayList<>(new LinkedHashSet<>(bestByPath.values()));
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
                newestSec = Files.getLastModifiedTime(runs.getFirst()).to(TimeUnit.SECONDS);
            } catch (IOException ignored) {
                // recency unavailable — fall through to run count
            }
        }
        // epoch seconds (< ~4.3e9 until year 2106) * 1e4 stays well under the 1e15 id tier.
        score += Math.max(0, Math.min(newestSec, 4_294_967_295L)) * 10_000L;
        score += Math.min(runs.size(), 9_999);
        return score;
    }

    /**
     * Newest-first run directories for a project: by numeric build number, then — for the
     * unnumbered {@code j-<UTC stamp>-<rid>} job dirs, which all read as 0 — by name, so the
     * latest job is the latest stamp rather than whatever order the directory listed.
     */
    public static List<Path> listRuns(Path projectHome) {
        Path runs = projectHome.resolve(RUNS);
        if (!Files.isDirectory(runs)) return List.of();
        try (Stream<Path> s = Files.list(runs)) {
            Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString());
            return s.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparingLong(ProjectBuilds::runNumberOf)
                            .thenComparing(byName)
                            .reversed())
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
            long t = Files.getLastModifiedTime(p).toMillis();
            // An epoch-0 stamp is as unbelievable as a failed stat — over-include it too.
            return t == 0 ? Long.MAX_VALUE : t;
        } catch (IOException e) {
            // Over-include: newest-N selection cuts this list BEFORE sorting by finishedAt, so a
            // transient stat failure mapped to 0 pushed a true-newest run outside the window and
            // it silently vanished from history. MAX_VALUE keeps it in the cut; the real sort
            // downstream puts it where it belongs.
            return Long.MAX_VALUE;
        }
    }

    /**
     * Newest run of the checkout {@code projectDir} that contains {@code fileName} (e.g. {@link
     * #RESULTS}, {@link #DETAILS}). Runs are newest-first by build number; a run whose {@code
     * record.json} names another checkout of the same id is not this checkout's and is skipped, as
     * is an in-progress run without the file. {@code fileName} must be a single path segment.
     */
    public static Optional<Path> latestRunFile(Path projectDir, String fileName) {
        return latestRunFile(buildsRoot(), projectDir, fileName);
    }

    public static Optional<Path> latestRunFile(Path buildsRoot, @Nullable Path projectDir, String fileName) {
        if (fileName == null || fileName.isBlank()) return Optional.empty();
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 || fileName.contains("..")) {
            return Optional.empty();
        }
        Path abs = projectDir == null
                ? Path.of(".").toAbsolutePath().normalize()
                : projectDir.toAbsolutePath().normalize();
        Path home = projectHome(buildsRoot, ProjectIdentity.resolve(abs));
        for (Path run : listRuns(home)) {
            Path f = run.resolve(fileName);
            if (!Files.isRegularFile(f)) continue;
            Path checkout = runCheckout(run);
            if (checkout != null && sameCheckout(checkout, abs)) return Optional.of(f);
        }
        return Optional.empty();
    }

    /**
     * The checkout a run was recorded for: the top-level {@code dir} of its {@code record.json}.
     * {@code null} when the run has no record or the record names none.
     */
    public static @Nullable Path runCheckout(Path runDir) {
        Path record = runDir.resolve(RECORD);
        if (!Files.isRegularFile(record)) return null;
        try {
            String dir = Jsonl.topStr(Files.readString(record, StandardCharsets.UTF_8), "dir");
            return dir == null || dir.isBlank() ? null : Path.of(dir);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * True when {@code a} and {@code b} are one directory: by real path while both exist, so a
     * symlinked spelling still matches; by normalized absolute path once either is gone.
     */
    public static boolean sameCheckout(Path a, Path b) {
        Path na = a.toAbsolutePath().normalize();
        Path nb = b.toAbsolutePath().normalize();
        if (na.equals(nb)) return true;
        try {
            return na.toRealPath().equals(nb.toRealPath());
        } catch (IOException | RuntimeException gone) {
            return false;
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

        public Path resultsFile() {
            return runDir.resolve(RESULTS);
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
}
