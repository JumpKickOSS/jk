// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Cheap mtime-based up-to-date check written into a compile output dir under one of the
 * {@link BuildStamps} names. Sits in front of the content-hashed {@link ActionCache}: one
 * {@code stat} per input, fall through to CAS when anything looks stale. Mtime equality is
 * treated as stale (ms truncation); spoofed mtimes are caught by the action-cache layer.
 *
 * <p>Files are not the only compile inputs. Compiler options, compiler plugins and the JDK decide
 * the output too, and none of them touches a source or a classpath entry's mtime — so the stamp
 * also records a digest of those option-bearing inputs and a stamp whose digest differs is stale.
 * The producer of each stamp owns the digest recipe; {@link #optionsDigest} folds the parts.
 *
 * <p>A stamp names its classpath in one of two ways. By path ({@code CP} lines): identity is the
 * jar's content hash or the directory's location, and a non-CAS entry newer than the stamp is
 * stale. By token ({@code TOKEN} lines, {@link ClasspathTokens}): each entry is the very line its
 * action key hashes — for a compile classpath its JVM ABI — so the stamp holds across a sibling
 * jar rewrite the key ignores and moves on exactly the changes that move the key; no mtime is
 * consulted for those entries. A producer picks one spelling and checks with the same one.
 *
 * <p>The stamp names and the predicate that recognises them live on the host leaf instead, where
 * the forked plugin workers that must keep stamps out of their archives can reach them.
 */
public final class FreshnessStamp {

    private FreshnessStamp() {}

    /**
     * A stamp's classpath section as the {@code prefix:token} lines its producer's action key
     * hashes ({@code ActionKey.javacClasspathTokens}, {@code groovycClasspathTokens}), one per
     * entry. Its own type rather than a bare list so a call site cannot hand a path list to the
     * token arm, or the reverse, by accident.
     */
    public record ClasspathTokens(List<String> lines) {
        public ClasspathTokens {
            lines = List.copyOf(lines);
        }

        public static ClasspathTokens of(List<String> lines) {
            return new ClasspathTokens(lines);
        }
    }

    /**
     * True when the stamp matches the current source/classpath sets and option digest and no
     * input is newer.
     */
    public static boolean isFresh(
            Path outputDir,
            String stampName,
            List<Path> sources,
            List<Path> classpath,
            int release,
            String optionsDigest)
            throws IOException {
        Stamp stamp = matching(outputDir, stampName, sources, release, optionsDigest);
        if (stamp == null) return false;
        // A token-spelled stamp names no paths; it matches only a producer that checks by token.
        if (!stamp.classpathTokens().isEmpty()) return false;

        Set<Path> currentCp = normalise(classpath);
        Set<Path> recordedCp = normalise(stamp.classpath());
        if (!currentCp.equals(recordedCp)) return false;

        for (Path src : normalise(sources)) {
            if (newerThan(src, stamp.stampMillis())) return false;
        }
        for (Path cp : currentCp) {
            // Content-addressed store jars encode their payload in the path
            // (…/store/sha256/ab/cd/<hex>). Set equality already proved the locked
            // identity is unchanged; mtime on an immutable CAS blob is not an
            // input — re-materialize / hardlink reclaim / FS churn must not force
            // a recompile after a green build (build→jk run stamp thrash).
            if (isContentAddressed(cp)) continue;
            if (newerThan(cp, stamp.stampMillis())) return false;
        }
        return true;
    }

    /**
     * True when the stamp matches the current source set, option digest and classpath token set
     * and no source is newer. The tokens are compared as a set and never stat'ed: each one is
     * content-derived, so equality is the whole proof.
     */
    public static boolean isFresh(
            Path outputDir,
            String stampName,
            List<Path> sources,
            ClasspathTokens classpath,
            int release,
            String optionsDigest)
            throws IOException {
        Stamp stamp = matching(outputDir, stampName, sources, release, optionsDigest);
        if (stamp == null) return false;
        // A path-spelled stamp carries no tokens; it matches only a producer that checks by path.
        if (!stamp.classpath().isEmpty()) return false;
        if (!new TreeSet<>(classpath.lines()).equals(new TreeSet<>(stamp.classpathTokens()))) return false;
        for (Path src : normalise(sources)) {
            if (newerThan(src, stamp.stampMillis())) return false;
        }
        return true;
    }

    /** The stamp, when its release, option digest and source set are the producer's; else null. */
    private static @Nullable Stamp matching(
            Path outputDir, String stampName, List<Path> sources, int release, String optionsDigest)
            throws IOException {
        Optional<Stamp> read = read(outputDir, stampName);
        if (read.isEmpty()) return null;
        Stamp stamp = read.get();
        // A stamp written before release tracking was added has release=0; treat as stale.
        if (stamp.release() != release) return null;
        // Options, plugins, the JDK: a change there moves no file mtime, only this digest.
        if (!stamp.optionsDigest().equals(optionsDigest)) return null;
        if (!normalise(sources).equals(normalise(stamp.sources()))) return null;
        return stamp;
    }

    /**
     * The action key the stamp under {@code outputDir} records — the key of the compile that
     * produced the tree it sits in — or empty when there is no stamp or it names none.
     */
    public static Optional<String> stampedKey(Path outputDir, String stampName) {
        try {
            return read(outputDir, stampName).map(Stamp::actionKey).filter(k -> !k.isBlank());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * True when {@code p} is a CAS object path whose identity is the content hash
     * (not a mutable local file or classes directory).
     */
    static boolean isContentAddressed(Path p) {
        if (p == null) return false;
        String s = p.toString().replace('\\', '/');
        return s.contains("/sha256/") || s.startsWith("sha256/");
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

    /**
     * One digest over the option-bearing inputs of a compile, in the producer's order. Each part
     * is one fact ({@code arg:-parameters}, {@code jdk:<sha>}); the join is newline-separated so
     * two parts cannot run together into a third.
     */
    public static String optionsDigest(List<String> parts) {
        return Hashing.sha256Hex(String.join("\n", parts));
    }

    /** Record inputs that produced {@code outputDir} so the next build can short-circuit. */
    public static void write(
            Path outputDir,
            String stampName,
            String taskId,
            String actionKey,
            List<Path> sources,
            List<Path> classpath,
            int release,
            String optionsDigest)
            throws IOException {
        write(outputDir, stampName, taskId, actionKey, sources, classpath, List.of(), release, optionsDigest);
    }

    /** As {@link #write(Path, String, String, String, List, List, int, String)} with the classpath spelled as tokens. */
    public static void write(
            Path outputDir,
            String stampName,
            String taskId,
            String actionKey,
            List<Path> sources,
            ClasspathTokens classpath,
            int release,
            String optionsDigest)
            throws IOException {
        write(outputDir, stampName, taskId, actionKey, sources, List.of(), classpath.lines(), release, optionsDigest);
    }

    private static void write(
            Path outputDir,
            String stampName,
            String taskId,
            String actionKey,
            List<Path> sources,
            List<Path> classpath,
            List<String> classpathTokens,
            int release,
            String optionsDigest)
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
        sb.append("DIGEST ").append(optionsDigest).append('\n');
        for (Path src : sortedAbs(sources)) {
            sb.append("SOURCE ").append(src).append('\n');
        }
        for (Path cp : sortedAbs(classpath)) {
            sb.append("CP ").append(cp).append('\n');
        }
        for (String token : new TreeSet<>(classpathTokens)) {
            sb.append("TOKEN ").append(token).append('\n');
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
        // A stamp without a DIGEST line records no options; it matches only a producer with none.
        String optionsDigest = "";
        List<Path> sources = new ArrayList<>();
        List<Path> classpath = new ArrayList<>();
        List<String> classpathTokens = new ArrayList<>();
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
            } else if (line.startsWith("DIGEST ")) {
                optionsDigest = line.substring("DIGEST ".length()).trim();
            } else if (line.startsWith("SOURCE ")) {
                sources.add(Path.of(line.substring("SOURCE ".length()).trim()));
            } else if (line.startsWith("CP ")) {
                classpath.add(Path.of(line.substring("CP ".length()).trim()));
            } else if (line.startsWith("TOKEN ")) {
                classpathTokens.add(line.substring("TOKEN ".length()).trim());
            }
        }
        if (taskId == null || actionKey == null) return Optional.empty();
        return Optional.of(
                new Stamp(taskId, actionKey, stampMillis, release, optionsDigest, sources, classpath, classpathTokens));
    }

    /** One stamp; exactly one of {@code classpath} and {@code classpathTokens} is non-empty. */
    public record Stamp(
            String taskId,
            String actionKey,
            long stampMillis,
            int release,
            String optionsDigest,
            List<Path> sources,
            List<Path> classpath,
            List<String> classpathTokens) {
        public Stamp {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(actionKey, "actionKey");
            Objects.requireNonNull(optionsDigest, "optionsDigest");
            sources = List.copyOf(sources);
            classpath = List.copyOf(classpath);
            classpathTokens = List.copyOf(classpathTokens);
        }
    }

    private static Set<Path> normalise(List<Path> paths) {
        Set<Path> out = new TreeSet<>();
        for (Path p : paths) out.add(identityKey(p));
        return out;
    }

    private static List<Path> sortedAbs(List<Path> paths) {
        List<Path> copy = new ArrayList<>(paths.size());
        for (Path p : paths) copy.add(identityKey(p));
        copy.sort(Comparator.comparing(Path::toString));
        return copy;
    }

    /**
     * Stable classpath identity for stamp compare/write. Locked jars may live under the Maven
     * local repo or {@code repos/<name>/}; identity is the content hash, not the path.
     */
    public static Path identityKey(Path p) {
        if (p == null) return Path.of(".");
        if (p.getNameCount() >= 2 && "sha256".equals(p.getName(0).toString()) && !Files.isRegularFile(p)) {
            return p;
        }
        Path abs = p.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) return abs;
        String name = abs.getFileName().toString();
        if (!name.endsWith(".jar") && !name.endsWith(".aar") && !name.endsWith(".zip")) return abs;
        try {
            return Path.of("sha256", FileHashMemo.contentHash(abs));
        } catch (IOException e) {
            return abs;
        }
    }

    private static boolean newerThan(Path file, long stampMillis) throws IOException {
        // One readAttributes answers present, directory, and mtime together. exists +
        // isDirectory + getLastModifiedTime each re-resolve the path (~10 µs on NTFS vs ~1.5
        // on ext4) — three ops per input, over ~1,300 sources, twice per isFresh.
        Optional<BasicFileAttributes> stat = PathUtil.stat(file);
        if (stat.isEmpty()) return true; // disappearing input → treat as changed
        BasicFileAttributes attrs = stat.get();
        // A directory input (sibling lane's classes dir): its ROOT mtime does not change when
        // nested files are rewritten — walk for the newest nested mtime. Deletions
        // bump the parent dir's mtime, which the walk also sees.
        if (attrs.isDirectory()) {
            // Directory mtimes matter here too, so this walks every entry rather than only files —
            // a deletion bumps the parent and nothing else. The visitor still hands over attributes
            // the walk already read.
            boolean[] newer = {false};
            PathUtil.forEachEntry(file, dir -> false, (p, a) -> {
                if (a.lastModifiedTime().toMillis() >= stampMillis) {
                    newer[0] = true;
                    return false;
                }
                return true;
            });
            return newer[0];
        }
        // Use >=, not >: filesystem mtimes are millisecond-truncated, and a
        // build can finish writing its stamp in the same millisecond a source
        // is edited (fast disks, tiny projects). Treating "mtime == stampMillis"
        // as potentially-changed means we distrust the cheap stat at that
        // boundary and fall through to the content-hashing action cache, which
        // decides correctly. With strict >, a same-millisecond edit is silently
        // skipped — a stale-build bug, not just a test flake.
        return attrs.lastModifiedTime().toMillis() >= stampMillis;
    }
}
