// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Cheap mtime-based up-to-date check stamped into a compile output dir ({@link #JAVA_STAMP} /
 * {@link #KOTLIN_STAMP}). Sits in front of the content-hashed {@link ActionCache}: one {@code
 * stat} per input, fall through to CAS when anything looks stale. Mtime equality is treated as
 * stale (ms truncation); spoofed mtimes are caught by the action-cache layer.
 */
public final class FreshnessStamp {

    /** Sentinel stamped into the output directory by the Java compile. */
    public static final String JAVA_STAMP = ".jstamp";

    /** Sentinel stamped into the output directory by the Kotlin compile. */
    public static final String KOTLIN_STAMP = ".kstamp";

    /** Sentinel stamped into the output directory by the Groovy compile. */
    public static final String GROOVY_STAMP = ".gstamp";

    private FreshnessStamp() {}

    /** True when {@code fileName} is a compile stamp sentinel (excluded from action-cache outputs). */
    public static boolean isStampFile(String fileName) {
        return JAVA_STAMP.equals(fileName) || KOTLIN_STAMP.equals(fileName) || GROOVY_STAMP.equals(fileName);
    }

    /** True when the stamp matches the current source/classpath sets and no input is newer. */
    public static boolean isFresh(
            Path outputDir, String stampName, List<Path> sources, List<Path> classpath, int release)
            throws IOException {
        Optional<Stamp> read = read(outputDir, stampName);
        if (read.isEmpty()) return false;
        Stamp stamp = read.get();

        // A stamp written before release tracking was added has release=0; treat as stale.
        if (stamp.release() != release) return false;

        Set<Path> currentSrc = normalise(sources);
        Set<Path> recordedSrc = normalise(stamp.sources());
        if (!currentSrc.equals(recordedSrc)) return false;

        Set<Path> currentCp = normalise(classpath);
        Set<Path> recordedCp = normalise(stamp.classpath());
        if (!currentCp.equals(recordedCp)) return false;

        for (Path src : currentSrc) {
            if (newerThan(src, stamp.stampMillis())) return false;
        }
        for (Path cp : currentCp) {
            if (newerThan(cp, stamp.stampMillis())) return false;
        }
        return true;
    }

    /**
     * True when a previously stamped source is gone — caller must wipe the output tree so
     * dropped classes do not survive an additive assemble merge.
     */
    public static boolean hasRemovedSources(Path outputDir, String stampName, List<Path> currentSources) {
        try {
            Optional<Stamp> read = read(outputDir, stampName);
            if (read.isEmpty()) return false;
            Set<Path> current = normalise(currentSources);
            for (Path recorded : normalise(read.get().sources())) {
                if (!current.contains(recorded)) return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Cheap bar-sizing probe: stamp exists and no source mtime is newer. Does not compare
     * source/classpath sets (unlike {@link #isFresh}).
     */
    public static boolean looksFresh(Path outputDir, String stampName, List<Path> sources) {
        try {
            Optional<Stamp> read = read(outputDir, stampName);
            if (read.isEmpty()) return false;
            long stampMillis = read.get().stampMillis();
            for (Path src : sources) {
                if (newerThan(src, stampMillis)) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Record inputs that produced {@code outputDir} so the next build can short-circuit. */
    public static void write(
            Path outputDir,
            String stampName,
            String taskId,
            String actionKey,
            List<Path> sources,
            List<Path> classpath,
            int release)
            throws IOException {
        Files.createDirectories(outputDir);
        // Same-clock stamping: isFresh compares stampMillis against input mtimes, which come
        // from the filesystem's coarse clock — and that clock can LAG currentTimeMillis by a
        // tick. Recording wall-clock millis let an input edited in the lag window carry
        // mtime < stampMillis and read as fresh (a silently stale build). Probe the fs clock
        // through the stamp file itself; recording at-or-before the final content write only
        // errs toward staleness, which the action cache resolves correctly.
        Path stampFile = outputDir.resolve(stampName);
        Files.writeString(stampFile, "", StandardCharsets.UTF_8);
        long stampMillis = Files.getLastModifiedTime(stampFile).toMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("TASK ").append(taskId).append('\n');
        sb.append("KEY ").append(actionKey).append('\n');
        sb.append("STAMP_MILLIS ").append(stampMillis).append('\n');
        sb.append("RELEASE ").append(release).append('\n');
        for (Path src : sortedAbs(sources)) {
            sb.append("SOURCE ").append(src).append('\n');
        }
        for (Path cp : sortedAbs(classpath)) {
            sb.append("CP ").append(cp).append('\n');
        }
        Files.writeString(stampFile, sb.toString(), StandardCharsets.UTF_8);
    }

    static Optional<Stamp> read(Path outputDir, String stampName) throws IOException {
        Path file = outputDir.resolve(stampName);
        if (!Files.isRegularFile(file)) return Optional.empty();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String taskId = null;
        String actionKey = null;
        long stampMillis = 0;
        int release = 0;
        List<Path> sources = new ArrayList<>();
        List<Path> classpath = new ArrayList<>();
        for (String line : content.split("\n")) {
            if (line.isBlank()) continue;
            if (line.startsWith("TASK ")) {
                taskId = line.substring("TASK ".length()).trim();
            } else if (line.startsWith("KEY ")) {
                actionKey = line.substring("KEY ".length()).trim();
            } else if (line.startsWith("STAMP_MILLIS ")) {
                stampMillis =
                        Long.parseLong(line.substring("STAMP_MILLIS ".length()).trim());
            } else if (line.startsWith("RELEASE ")) {
                release = Integer.parseInt(line.substring("RELEASE ".length()).trim());
            } else if (line.startsWith("SOURCE ")) {
                sources.add(Path.of(line.substring("SOURCE ".length()).trim()));
            } else if (line.startsWith("CP ")) {
                classpath.add(Path.of(line.substring("CP ".length()).trim()));
            }
        }
        if (taskId == null || actionKey == null) return Optional.empty();
        return Optional.of(new Stamp(taskId, actionKey, stampMillis, release, sources, classpath));
    }

    public record Stamp(
            String taskId, String actionKey, long stampMillis, int release, List<Path> sources, List<Path> classpath) {
        public Stamp {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(actionKey, "actionKey");
            sources = List.copyOf(sources);
            classpath = List.copyOf(classpath);
        }
    }

    private static Set<Path> normalise(List<Path> paths) {
        Set<Path> out = new TreeSet<>();
        for (Path p : paths) out.add(p.toAbsolutePath().normalize());
        return out;
    }

    private static List<Path> sortedAbs(List<Path> paths) {
        List<Path> copy = new ArrayList<>(paths.size());
        for (Path p : paths) copy.add(p.toAbsolutePath().normalize());
        copy.sort(Comparator.comparing(Path::toString));
        return copy;
    }

    private static boolean newerThan(Path file, long stampMillis) throws IOException {
        if (!Files.exists(file)) return true; // disappearing input → treat as changed
        // A directory input (sibling lane's classes dir): its ROOT mtime does not change when
        // nested files are rewritten — walk for the newest nested mtime. Deletions
        // bump the parent dir's mtime, which the walk also sees.
        if (Files.isDirectory(file)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(file)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (Files.getLastModifiedTime(p).toMillis() >= stampMillis) return true;
                }
            }
            return false;
        }
        // Use >=, not >: filesystem mtimes are millisecond-truncated, and a
        // build can finish writing its stamp in the same millisecond a source
        // is edited (fast disks, tiny projects). Treating "mtime == stampMillis"
        // as potentially-changed means we distrust the cheap stat at that
        // boundary and fall through to the content-hashing action cache, which
        // decides correctly. With strict >, a same-millisecond edit is silently
        // skipped — a stale-build bug, not just a test flake.
        return Files.getLastModifiedTime(file).toMillis() >= stampMillis;
    }
}
