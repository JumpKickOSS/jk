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
 * Machine-local preflight memo (JK-1100+): caches dirty-set forecasts (and optionally graph
 * structure) so a cold engine process can skip expensive walks when inputs are unchanged. Stored
 * under {@code <entry>/target/.jk/preflight/} — never git-committed; miss or corrupt → full
 * recompute (fail-open).
 *
 * <p>Schema 2 (JK-1108): per-module fingerprints use <strong>content hashes</strong> of sources
 * (not mtime/size) so CI cache restores that preserve timestamps cannot false-hit. Set {@code
 * JK_PREFLIGHT_MEMO_MTIME=1} for the legacy path/size/mtime inventory.
 */
public final class PreflightMemo {

    static final String SCHEMA = "2";
    private static final String DIRTY_FILE = "dirty-memo.txt";
    private static final String GRAPH_FILE = "graph-memo.txt";

    private PreflightMemo() {}

    /** {@code <entryDir>/target/.jk/preflight/dirty-memo.txt}. */
    public static Path memoFile(Path entryDir) {
        return entryDir.resolve("target").resolve(".jk").resolve("preflight").resolve(DIRTY_FILE);
    }

    public static Path graphMemoFile(Path entryDir) {
        return entryDir.resolve("target").resolve(".jk").resolve("preflight").resolve(GRAPH_FILE);
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
            String wantMode = fingerprintMode();
            String gotVersion = null;
            String gotSkip = null;
            String gotMode = null;
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
                if (line.startsWith("fpMode=")) {
                    gotMode = line.substring("fpMode=".length());
                    continue;
                }
                // relPath \t fingerprint \t dirty(0|1)
                String[] parts = line.split("\t", 3);
                if (parts.length != 3) return Optional.empty();
                rows.put(parts[0], new MemoRow(parts[1], "1".equals(parts[2])));
            }
            if (!wantVersion.equals(gotVersion) || !wantSkip.equals(gotSkip)) return Optional.empty();
            if (gotMode != null && !wantMode.equals(gotMode)) return Optional.empty();

            Path root = entryDir.toAbsolutePath().normalize();
            List<BuildGraph.BuildUnit> units = graph.topoOrder();
            if (units.size() != rows.size()) return Optional.empty();

            Set<Path> dirty = new LinkedHashSet<>();
            Set<String> seen = new LinkedHashSet<>();
            for (BuildGraph.BuildUnit u : units) {
                Path dir = u.dir().toAbsolutePath().normalize();
                String rel = relKey(root, dir);
                MemoRow row = rows.get(rel);
                if (row == null) return Optional.empty();
                String fp = fingerprintModule(dir, skipTests);
                if (!row.fp().equals(fp)) return Optional.empty();
                seen.add(rel);
                if (row.dirty()) dirty.add(dir);
            }
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
            sb.append("fpMode=").append(fingerprintMode()).append('\n');
            Set<Path> dirtyNorm = new LinkedHashSet<>();
            for (Path d : dirty) dirtyNorm.add(d.toAbsolutePath().normalize());
            for (BuildGraph.BuildUnit u : graph.topoOrder()) {
                Path dir = u.dir().toAbsolutePath().normalize();
                String rel = relKey(root, dir);
                String fp = fingerprintModule(dir, skipTests);
                boolean isDirty = dirtyNorm.contains(dir);
                sb.append(rel).append('\t').append(fp).append('\t').append(isDirty ? "1" : "0").append('\n');
            }
            AtomicWrites.replace(file, sb.toString());
        } catch (Exception ignored) {
            // fail-open
        }
    }

    /**
     * Store a workspace graph snapshot (JK-1109 layer A): module dirs + prereq edges as relative
     * paths, keyed by a workspace structure fingerprint. Best-effort.
     */
    public static void storeGraph(Path entryDir, BuildGraph.Result graph) {
        if (graph == null || graph.hasErrors() || graph.topoOrder().isEmpty()) return;
        try {
            Path root = entryDir.toAbsolutePath().normalize();
            Path file = graphMemoFile(entryDir);
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("schema=").append(SCHEMA).append('\n');
            sb.append("cacheKeyVersion=").append(BuildIdentity.cacheKeyVersion()).append('\n');
            sb.append("structure=").append(structureFingerprint(entryDir, graph)).append('\n');
            for (BuildGraph.BuildUnit u : graph.topoOrder()) {
                Path dir = u.dir().toAbsolutePath().normalize();
                sb.append("unit\t").append(relKey(root, dir)).append('\t').append(u.coord()).append('\n');
            }
            for (var e : graph.edges().entrySet()) {
                String from = relKey(root, e.getKey().toAbsolutePath().normalize());
                for (Path prereq : e.getValue()) {
                    String to = relKey(root, prereq.toAbsolutePath().normalize());
                    sb.append("edge\t").append(from).append('\t').append(to).append('\n');
                }
            }
            AtomicWrites.replace(file, sb.toString());
        } catch (Exception ignored) {
            // fail-open
        }
    }

    /**
     * When the workspace structure fingerprint matches, return true so callers can skip redundant
     * work that depends only on topology (diagnostics / perf). Full {@link BuildGraph#resolve} is
     * still required for {@link BuildGraph.BuildUnit} manifests — this is an advisory hit signal
     * plus a place to hang future rebuild-from-memo.
     */
    public static boolean graphStructureMatches(Path entryDir, BuildGraph.Result graph) {
        Path file = graphMemoFile(entryDir);
        if (!Files.isRegularFile(file) || graph == null || graph.hasErrors()) return false;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().startsWith("schema=" + SCHEMA)) return false;
            String want = structureFingerprint(entryDir, graph);
            String gotVersion = null;
            String gotStruct = null;
            for (String line : lines) {
                if (line.startsWith("cacheKeyVersion=")) gotVersion = line.substring("cacheKeyVersion=".length());
                if (line.startsWith("structure=")) gotStruct = line.substring("structure=".length());
            }
            return BuildIdentity.cacheKeyVersion().equals(gotVersion) && want.equals(gotStruct);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Workspace structure key: ordered module rel paths + each module's jk.toml + jk.lock content
     * digests (membership + edge inputs). Used to invalidate graph memo when modules are added or
     * manifests/locks change.
     */
    static String structureFingerprint(Path entryDir, BuildGraph.Result graph) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            Path root = entryDir.toAbsolutePath().normalize();
            for (BuildGraph.BuildUnit u : graph.topoOrder()) {
                Path dir = u.dir().toAbsolutePath().normalize();
                feed(md, relKey(root, dir));
                feedFile(md, dir.resolve("jk.toml"));
                feedFile(md, dir.resolve("jk.lock"));
            }
            // Edges in stable order
            List<String> edgeLines = new ArrayList<>();
            for (var e : graph.edges().entrySet()) {
                String from = relKey(root, e.getKey().toAbsolutePath().normalize());
                List<String> prereqs = new ArrayList<>();
                for (Path p : e.getValue()) prereqs.add(relKey(root, p.toAbsolutePath().normalize()));
                prereqs.sort(String::compareTo);
                for (String p : prereqs) edgeLines.add(from + "->" + p);
            }
            edgeLines.sort(String::compareTo);
            for (String el : edgeLines) feed(md, el);
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "err-" + System.nanoTime();
        }
    }

    /**
     * Input fingerprint for one module. Default (schema 2): content hash of each source file.
     * {@code JK_PREFLIGHT_MEMO_MTIME=1}: path + size + mtime (faster, less CI-safe).
     */
    static String fingerprintModule(Path moduleDir, boolean skipTests) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            feed(md, "skip=" + (skipTests ? "1" : "0"));
            feed(md, "mode=" + fingerprintMode());
            feedFile(md, moduleDir.resolve("jk.toml"));
            feedFile(md, moduleDir.resolve("jk.lock"));
            boolean mtimeMode = useMtimeMode();
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
                            if (mtimeMode) {
                                feed(md, Long.toString(attrs.size()));
                                feed(md, Long.toString(attrs.lastModifiedTime().toMillis()));
                            } else {
                                try {
                                    md.update(Files.readAllBytes(file));
                                    md.update((byte) 0);
                                } catch (IOException e) {
                                    feed(md, "unreadable");
                                }
                            }
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
            return "err-" + System.nanoTime();
        }
    }

    static String fingerprintMode() {
        return useMtimeMode() ? "mtime" : "content";
    }

    /** {@code JK_PREFLIGHT_MEMO_MTIME=1} opts into path/size/mtime fingerprints. */
    static boolean useMtimeMode() {
        String v = System.getenv("JK_PREFLIGHT_MEMO_MTIME");
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true"));
    }

    private static String relKey(Path root, Path dir) {
        String rel = root.relativize(dir).toString().replace('\\', '/');
        return rel.isEmpty() ? "." : rel;
    }

    private static void feedFile(MessageDigest md, Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            feed(md, "missing");
            return;
        }
        md.update(Files.readAllBytes(file));
        md.update((byte) 0);
    }

    private static void feed(MessageDigest md, String s) {
        md.update(s.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
    }

    private record MemoRow(String fp, boolean dirty) {}
}
