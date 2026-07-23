// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Machine-local preflight memo (JK-1100): caches the last dirty-set forecast so a cold engine
 * process can skip the expensive per-step {@link BuildPlanForecast} walk when module inputs are
 * unchanged. Stored under {@code <entry>/target/.jk/preflight/} — never git-committed; miss or
 * corrupt → full recompute (fail-open).
 *
 * <p>Key inputs per module: {@code jk.toml} + {@code jk.lock} content digests, source file
 * path/size/mtime inventory, skipTests, and {@link BuildIdentity#cacheKeyVersion()}. Force/rebuild
 * callers never consult this memo.
 */
public final class PreflightMemo {

    static final String SCHEMA = "1";
    private static final String FILE_NAME = "dirty-memo.txt";

    private PreflightMemo() {}

    /** {@code <entryDir>/target/.jk/preflight/dirty-memo.txt}. */
    public static Path memoFile(Path entryDir) {
        return entryDir.resolve("target").resolve(".jk").resolve("preflight").resolve(FILE_NAME);
    }

    /**
     * When every module's input fingerprint matches the memo and the module set is identical,
     * return the memoized dirty dirs (absolute, normalized). Empty optional = miss.
     */
    public static Optional<Set<Path>> tryLoadDirty(
            Path entryDir, BuildGraph.Result graph, boolean skipTests) {
        Path file = memoFile(entryDir);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().startsWith("schema=" + SCHEMA)) return Optional.empty();
            String wantVersion = BuildIdentity.cacheKeyVersion();
            String wantSkip = skipTests ? "1" : "0";
            String gotVersion = null;
            String gotSkip = null;
            Map<String, MemoRow> rows = new LinkedHashMap<>();
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) continue;
                if (line.startsWith("schema=")) continue;
                if (line.startsWith("cacheKeyVersion=")) {
                    gotVersion = line.substring("cacheKeyVersion=".length());
                    continue;
                }
                if (line.startsWith("skipTests=")) {
                    gotSkip = line.substring("skipTests=".length());
                    continue;
                }
                // relPath \t fingerprint \t dirty(0|1)
                String[] parts = line.split("\t", 3);
                if (parts.length != 3) return Optional.empty();
                rows.put(parts[0], new MemoRow(parts[1], "1".equals(parts[2])));
            }
            if (!wantVersion.equals(gotVersion) || !wantSkip.equals(gotSkip)) return Optional.empty();

            Path root = entryDir.toAbsolutePath().normalize();
            List<BuildGraph.BuildUnit> units = graph.topoOrder();
            if (units.size() != rows.size()) return Optional.empty();

            Set<Path> dirty = new LinkedHashSet<>();
            Set<String> seen = new LinkedHashSet<>();
            for (BuildGraph.BuildUnit u : units) {
                Path dir = u.dir().toAbsolutePath().normalize();
                String rel = root.relativize(dir).toString().replace('\\', '/');
                if (rel.isEmpty()) rel = ".";
                MemoRow row = rows.get(rel);
                if (row == null) return Optional.empty();
                String fp = fingerprintModule(dir, skipTests);
                if (!row.fp().equals(fp)) return Optional.empty();
                seen.add(rel);
                if (row.dirty()) dirty.add(dir);
            }
            // Extra memo rows not in graph → miss
            if (!seen.equals(rows.keySet())) return Optional.empty();
            return Optional.of(dirty);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Persist the dirty set for the next cold process. Best-effort; never throws. */
    public static void storeDirty(
            Path entryDir, BuildGraph.Result graph, boolean skipTests, Set<Path> dirty) {
        try {
            Path root = entryDir.toAbsolutePath().normalize();
            Path file = memoFile(entryDir);
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("schema=").append(SCHEMA).append('\n');
            sb.append("cacheKeyVersion=").append(BuildIdentity.cacheKeyVersion()).append('\n');
            sb.append("skipTests=").append(skipTests ? "1" : "0").append('\n');
            Set<Path> dirtyNorm = new LinkedHashSet<>();
            for (Path d : dirty) dirtyNorm.add(d.toAbsolutePath().normalize());
            for (BuildGraph.BuildUnit u : graph.topoOrder()) {
                Path dir = u.dir().toAbsolutePath().normalize();
                String rel = root.relativize(dir).toString().replace('\\', '/');
                if (rel.isEmpty()) rel = ".";
                String fp = fingerprintModule(dir, skipTests);
                boolean isDirty = dirtyNorm.contains(dir);
                sb.append(rel).append('\t').append(fp).append('\t').append(isDirty ? "1" : "0").append('\n');
            }
            AtomicWrites.replace(file, sb.toString());
        } catch (Exception ignored) {
            // fail-open: next build recomputes
        }
    }

    /**
     * Cheap input fingerprint: toml/lock content digests + source inventory (path, size, mtime).
     * Deliberately not a full content hash of sources — matches incremental-build invalidation
     * style; CI clock skew that preserves mtime+size can false-hit (same class of risk as many
     * build tools); force/rebuild never uses the memo.
     */
    static String fingerprintModule(Path moduleDir, boolean skipTests) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            feed(md, "skip=" + (skipTests ? "1" : "0"));
            feedFile(md, moduleDir.resolve("jk.toml"));
            feedFile(md, moduleDir.resolve("jk.lock"));
            List<Path> roots = new ArrayList<>();
            roots.add(moduleDir.resolve("src"));
            roots.add(moduleDir.resolve("test"));
            for (Path r : roots) {
                if (!Files.isDirectory(r)) continue;
                Files.walkFileTree(r, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.getFileName().toString();
                        if (name.endsWith(".java")
                                || name.endsWith(".kt")
                                || name.endsWith(".kts")
                                || name.endsWith(".proto")) {
                            feed(md, moduleDir.relativize(file).toString().replace('\\', '/'));
                            feed(md, Long.toString(attrs.size()));
                            feed(md, Long.toString(attrs.lastModifiedTime().toMillis()));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "err-" + System.nanoTime(); // unique → force miss
        }
    }

    private static void feedFile(MessageDigest md, Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            feed(md, "missing");
            return;
        }
        feed(md, Files.readString(file, StandardCharsets.UTF_8));
    }

    private static void feed(MessageDigest md, String s) {
        md.update(s.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
    }

    private record MemoRow(String fp, boolean dirty) {}
}
