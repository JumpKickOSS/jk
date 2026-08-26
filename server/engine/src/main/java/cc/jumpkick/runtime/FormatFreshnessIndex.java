// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.CacheTree;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Path → (mtime, size) index so {@code jk format} can skip the formatter JVM when nothing on disk
 * has changed. Content-addressed worker stamps remain a second line of defense for files we do
 * send; this index is the cheap first filter (stat only, no reads).
 *
 * <p>Stored under {@code <cache>/format-freshness/<digest>.idx}, where the digest is
 * {@link FormatKey#digest()} — the one owner of what a formatting run is keyed by. A config or
 * worker-jar identity change is a different file, so the previous index is simply unused.
 */
public final class FormatFreshnessIndex {

    static final String VERSION = "format-freshness-v1";

    private final Path file;
    private final Path projectDir;
    private final Map<String, Entry> entries;

    private FormatFreshnessIndex(Path file, Path projectDir, Map<String, Entry> entries) {
        this.file = file;
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.entries = entries;
    }

    /** Empty index that treats every file as dirty (I/O or config failures fail open). */
    public static FormatFreshnessIndex disabled(Path projectDir) {
        return new FormatFreshnessIndex(null, projectDir, new LinkedHashMap<>());
    }

    /** Open the index named by a {@link FormatKey#digest()}. */
    public static FormatFreshnessIndex open(Path cacheDir, Path projectDir, String configHash) {
        if (cacheDir == null || configHash == null || configHash.length() < 4) {
            return disabled(projectDir);
        }
        Path file = CacheTree.FORMAT_FRESHNESS
                .under(cacheDir)
                .resolve(configHash.substring(0, 2))
                .resolve(configHash.substring(2) + ".idx");
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] parts = line.split("\t", 3);
                    if (parts.length != 3) continue;
                    entries.put(parts[2], new Entry(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
                }
            } catch (Exception ignored) {
                entries.clear();
            }
        }
        return new FormatFreshnessIndex(file, projectDir, entries);
    }

    public record Split(
            List<Path> dirtyJava, List<Path> dirtyKotlin, List<Path> dirtyGroovy, List<Path> dirtyScala, int clean) {}

    /** Partition sources into dirty (mtime/size mismatch or unknown) vs already-clean. */
    public Split partition(List<Path> javaFiles, List<Path> kotlinFiles) {
        return partition(javaFiles, kotlinFiles, List.of(), List.of());
    }

    public Split partition(
            List<Path> javaFiles, List<Path> kotlinFiles, List<Path> groovyFiles, List<Path> scalaFiles) {
        Map<String, Entry> keep = new LinkedHashMap<>();
        List<Path> dirtyJava = new ArrayList<>();
        List<Path> dirtyKotlin = new ArrayList<>();
        List<Path> dirtyGroovy = new ArrayList<>();
        List<Path> dirtyScala = new ArrayList<>();
        int clean = 0;
        for (Path p : javaFiles) {
            if (rememberIfClean(p, keep)) clean++;
            else dirtyJava.add(p);
        }
        for (Path p : kotlinFiles) {
            if (rememberIfClean(p, keep)) clean++;
            else dirtyKotlin.add(p);
        }
        for (Path p : groovyFiles) {
            if (rememberIfClean(p, keep)) clean++;
            else dirtyGroovy.add(p);
        }
        for (Path p : scalaFiles) {
            if (rememberIfClean(p, keep)) clean++;
            else dirtyScala.add(p);
        }
        entries.clear();
        entries.putAll(keep);
        return new Split(
                List.copyOf(dirtyJava),
                List.copyOf(dirtyKotlin),
                List.copyOf(dirtyGroovy),
                List.copyOf(dirtyScala),
                clean);
    }

    private boolean rememberIfClean(Path file, Map<String, Entry> keep) {
        String key = rel(file);
        Entry seen = entries.get(key);
        if (seen == null) return false;
        Entry now = stat(file);
        if (now == null || now.mtimeNanos != seen.mtimeNanos || now.size != seen.size) return false;
        keep.put(key, seen);
        return true;
    }

    public boolean isClean(Path file) {
        Entry seen = entries.get(rel(file));
        if (seen == null) return false;
        Entry now = stat(file);
        return now != null && now.mtimeNanos == seen.mtimeNanos && now.size == seen.size;
    }

    /** Remember the file's current mtime/size as clean for this config. */
    public void record(Path file) {
        Entry now = stat(file);
        if (now != null) entries.put(rel(file), now);
    }

    public void save() {
        if (file == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(VERSION).append('\n');
        for (var e : entries.entrySet()) {
            sb.append(e.getValue().mtimeNanos)
                    .append('\t')
                    .append(e.getValue().size)
                    .append('\t')
                    .append(e.getKey())
                    .append('\n');
        }
        try {
            AtomicWrites.replace(file, sb.toString());
        } catch (IOException ignored) {
            // advisory
        }
    }

    private String rel(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        try {
            return projectDir.relativize(abs).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return abs.toString().replace('\\', '/');
        }
    }

    private static Entry stat(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            var instant = attrs.lastModifiedTime().toInstant();
            long nanos = instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
            return new Entry(nanos, attrs.size());
        } catch (IOException e) {
            return null;
        }
    }

    private record Entry(long mtimeNanos, long size) {}
}
