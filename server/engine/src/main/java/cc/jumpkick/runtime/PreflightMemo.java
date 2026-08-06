// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Machine-local preflight memo+): dirty-set, graph structure, and plan shape caches
 * under {@code <entry>/target/.jk/preflight/}. Never git-committed; miss or corrupt → full recompute
 * (fail-open).
 *
 * <p>Schema 3: module fingerprints cover every file under {@code src}/{@code test}/
 * suite resource dirs (resources included); dirty rows are stored with fingerprints captured at
 * preflight time, never post-build.
 */
public final class PreflightMemo {

    static final String SCHEMA = "3";
    private static final String DIRTY_FILE = "dirty-memo.txt";
    private static final String GRAPH_FILE = "graph-memo.txt";
    private static final String SHAPE_FILE = "shape-memo.txt";

    /**
     * Serialize shape-memo upserts per entry directory. Parallel prepare races
     * read-modify-write on a single file; last writer must not drop peer modules' rows.
     */
    private static final ConcurrentHashMap<Path, Object> SHAPE_LOCKS = new ConcurrentHashMap<>();

    private PreflightMemo() {}

    public static Path memoFile(Path entryDir) {
        return entryDir.resolve("target").resolve(".jk").resolve("preflight").resolve(DIRTY_FILE);
    }

    public static Path graphMemoFile(Path entryDir) {
        return entryDir.resolve("target").resolve(".jk").resolve("preflight").resolve(GRAPH_FILE);
    }

    public static Path shapeMemoFile(Path entryDir) {
        return entryDir.resolve("target").resolve(".jk").resolve("preflight").resolve(SHAPE_FILE);
    }

    // Dirty set (layer C)

    /** A memo hit: the dirty set plus the validated per-module fingerprints (current as of load). */
    public record DirtyMemo(Set<Path> dirty, Map<Path, String> fingerprints) {}

    public static Optional<DirtyMemo> tryLoadDirty(Path entryDir, BuildGraph.Result graph, boolean skipTests) {
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
            Map<Path, String> fps = new LinkedHashMap<>();
            Set<String> seen = new LinkedHashSet<>();
            for (BuildGraph.BuildUnit u : units) {
                Path dir = u.dir().toAbsolutePath().normalize();
                String rel = relKey(root, dir);
                MemoRow row = rows.get(rel);
                if (row == null) return Optional.empty();
                if (!row.fp().equals(fingerprintModule(dir, skipTests))) return Optional.empty();
                // A clean claim is a promise that the module's output tree holds the outputs; a
                // hand-deleted target invalidates it even though no source changed. Workspace
                // members write under <workspace>/target/<rel>/ — checking <member>/target here
                // silently killed the memo for every workspace.
                if (!row.dirty() && !Files.isDirectory(cc.jumpkick.layout.BuildLayout.moduleTargetDir(root, dir))) {
                    return Optional.empty();
                }
                seen.add(rel);
                fps.put(dir, row.fp());
                if (row.dirty()) dirty.add(dir);
            }
            if (!seen.equals(rows.keySet())) return Optional.empty();
            return Optional.of(new DirtyMemo(dirty, fps));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Per-module fingerprints captured now. Callers snapshot BEFORE forecasting or building and
     * hand the snapshot to {@link #storeDirty}: a store must never fingerprint post-build, or a
     * mid-build edit is recorded as clean and never rebuilt.
     */
    public static Map<Path, String> snapshotFingerprints(BuildGraph.Result graph, boolean skipTests) {
        Map<Path, String> fps = new LinkedHashMap<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            Path dir = u.dir().toAbsolutePath().normalize();
            fps.put(dir, fingerprintModule(dir, skipTests));
        }
        return fps;
    }

    public static void storeDirty(
            Path entryDir,
            BuildGraph.Result graph,
            boolean skipTests,
            Set<Path> dirty,
            Map<Path, String> fingerprints) {
        try {
            Path root = entryDir.toAbsolutePath().normalize();
            Path file = memoFile(entryDir);
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("schema=").append(SCHEMA).append('\n');
            sb.append("cacheKeyVersion=")
                    .append(BuildIdentity.cacheKeyVersion())
                    .append('\n');
            sb.append("skipTests=").append(skipTests ? "1" : "0").append('\n');
            sb.append("fpMode=").append(fingerprintMode()).append('\n');
            Set<Path> dirtyNorm = new LinkedHashSet<>();
            for (Path d : dirty) dirtyNorm.add(d.toAbsolutePath().normalize());
            for (BuildGraph.BuildUnit u : graph.topoOrder()) {
                Path dir = u.dir().toAbsolutePath().normalize();
                String fp = fingerprints.get(dir);
                if (fp == null) return; // graph drifted from the snapshot — don't write
                sb.append(relKey(root, dir))
                        .append('\t')
                        .append(fp)
                        .append('\t')
                        .append(dirtyNorm.contains(dir) ? "1" : "0")
                        .append('\n');
            }
            AtomicWrites.replace(file, sb.toString());
        } catch (Exception ignored) {
            // fail-open
        }
    }

    // Graph structure (layer A) + rebuild without WorkspaceLoader

    /**
     * Store graph snapshot: ordered units (rel, coord, origin) + prereq edges. Structure key is
     * membership + per-unit toml/lock digests (not edges — edges are implied by manifests).
     */
    public static void storeGraph(Path entryDir, BuildGraph.Result graph) {
        if (graph == null || graph.hasErrors() || graph.topoOrder().isEmpty()) return;
        try {
            Path root = entryDir.toAbsolutePath().normalize();
            Path file = graphMemoFile(entryDir);
            Files.createDirectories(file.getParent());
            List<Path> unitDirs = new ArrayList<>();
            for (BuildGraph.BuildUnit u : graph.topoOrder()) {
                unitDirs.add(u.dir().toAbsolutePath().normalize());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("schema=").append(SCHEMA).append('\n');
            sb.append("cacheKeyVersion=")
                    .append(BuildIdentity.cacheKeyVersion())
                    .append('\n');
            sb.append("structure=")
                    .append(structureFingerprint(entryDir, unitDirs))
                    .append('\n');
            for (BuildGraph.BuildUnit u : graph.topoOrder()) {
                Path dir = u.dir().toAbsolutePath().normalize();
                sb.append("unit\t")
                        .append(relKey(root, dir))
                        .append('\t')
                        .append(u.coord())
                        .append('\t')
                        .append(u.origin().name())
                        .append('\n');
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
     * Rebuild {@link BuildGraph.Result} from the graph memo without {@code WorkspaceLoader}.
     * Validates structure fingerprint against current toml/lock files, then re-parses each unit's
     * manifest. Miss → empty.
     */
    public static Optional<BuildGraph.Result> tryLoadGraph(Path entryDir) {
        Path file = graphMemoFile(entryDir);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().startsWith("schema=" + SCHEMA)) return Optional.empty();
            String gotVersion = null;
            String gotStruct = null;
            List<UnitLine> units = new ArrayList<>();
            Map<String, Set<String>> edgeRels = new LinkedHashMap<>();
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("schema=")) continue;
                if (line.startsWith("cacheKeyVersion=")) {
                    gotVersion = line.substring("cacheKeyVersion=".length());
                    continue;
                }
                if (line.startsWith("structure=")) {
                    gotStruct = line.substring("structure=".length());
                    continue;
                }
                if (line.startsWith("unit\t")) {
                    String[] p = line.split("\t", 4);
                    if (p.length < 3) return Optional.empty();
                    String origin = p.length >= 4 ? p[3] : BuildGraph.Origin.MODULE.name();
                    units.add(new UnitLine(p[1], p[2], origin));
                    edgeRels.putIfAbsent(p[1], new LinkedHashSet<>());
                    continue;
                }
                if (line.startsWith("edge\t")) {
                    String[] p = line.split("\t", 3);
                    if (p.length != 3) return Optional.empty();
                    edgeRels.computeIfAbsent(p[1], k -> new LinkedHashSet<>()).add(p[2]);
                }
            }
            if (!BuildIdentity.cacheKeyVersion().equals(gotVersion) || gotStruct == null || units.isEmpty()) {
                return Optional.empty();
            }

            Path root = entryDir.toAbsolutePath().normalize();
            List<Path> unitDirs = new ArrayList<>();
            for (UnitLine u : units) {
                Path dir = absFromRel(root, u.rel());
                if (!Files.isRegularFile(dir.resolve("jk.toml"))) return Optional.empty();
                unitDirs.add(dir);
            }
            if (!gotStruct.equals(structureFingerprint(entryDir, unitDirs))) return Optional.empty();

            // Rebuild units by re-parsing manifests (no WorkspaceLoader membership walk).
            List<BuildGraph.BuildUnit> topo = new ArrayList<>();
            Map<Path, Set<Path>> edges = new LinkedHashMap<>();
            Map<String, Path> dirByRel = new LinkedHashMap<>();
            for (int i = 0; i < units.size(); i++) {
                UnitLine ul = units.get(i);
                Path dir = unitDirs.get(i);
                dirByRel.put(ul.rel(), dir);
                JkBuild manifest = JkBuildParser.parse(dir.resolve("jk.toml"));
                String coord =
                        manifest.project().group() + ":" + manifest.project().name();
                if (!coord.equals(ul.coord())) return Optional.empty(); // identity drift
                BuildGraph.Origin origin;
                try {
                    origin = BuildGraph.Origin.valueOf(ul.origin());
                } catch (IllegalArgumentException e) {
                    origin = BuildGraph.Origin.MODULE;
                }
                topo.add(new BuildGraph.BuildUnit(dir, manifest, coord, origin));
                edges.put(dir, new LinkedHashSet<>());
            }
            for (var e : edgeRels.entrySet()) {
                Path from = dirByRel.get(e.getKey());
                if (from == null) return Optional.empty();
                for (String toRel : e.getValue()) {
                    Path to = dirByRel.get(toRel);
                    if (to == null) return Optional.empty();
                    edges.get(from).add(to);
                }
            }
            return Optional.of(new BuildGraph.Result(List.copyOf(topo), Map.copyOf(edges), List.of()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** True when the on-disk graph memo's structure key matches {@code graph}'s unit set. */
    public static boolean graphStructureMatches(Path entryDir, BuildGraph.Result graph) {
        Path file = graphMemoFile(entryDir);
        if (!Files.isRegularFile(file) || graph == null || graph.hasErrors()) return false;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().startsWith("schema=" + SCHEMA)) return false;
            List<Path> unitDirs = new ArrayList<>();
            for (BuildGraph.BuildUnit u : graph.topoOrder()) {
                unitDirs.add(u.dir().toAbsolutePath().normalize());
            }
            String want = structureFingerprint(entryDir, unitDirs);
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
     * Structure key: entry {@code jk.toml}/{@code jk-lock.toml} (workspace membership) + ordered unit
     * dirs with each module's toml/lock digests. Entry root is always included so dropping a module
     * from {@code [workspace].modules} invalidates even when the unit folder still exists. Edges are
     * not hashed (derived from manifests when tomls are unchanged).
     */
    static String structureFingerprint(Path entryDir, List<Path> unitDirs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            Path root = entryDir.toAbsolutePath().normalize();
            // Always pin the entry manifest (workspace module list lives here). Whether the root
            // itself is a buildable unit depends on it having sources, not on any toml — pin that
            // too, or a root that grows src/ keeps hitting a graph memo without a root unit.
            feed(md, "entry");
            feed(md, "rootSources=" + (CompileSupport.hasSources(root) ? "1" : "0"));
            feedFile(md, root.resolve("jk.toml"));
            Path rootLock =
                    cc.jumpkick.lock.LockPaths.lockFile(root).toAbsolutePath().normalize();
            feedFile(md, rootLock);
            for (Path dir : unitDirs) {
                Path d = dir.toAbsolutePath().normalize();
                feed(md, relKey(root, d));
                feedFile(md, d.resolve("jk.toml"));
                // Every workspace member resolves to the single root lock — already digested
                // above; re-reading a monorepo-sized lock once per module scaled the key cost by
                // modules × lock size. A marker keeps the structural position; a module
                // with a genuinely distinct lock (standalone unit) still digests its own.
                Path lock =
                        cc.jumpkick.lock.LockPaths.lockFile(d).toAbsolutePath().normalize();
                if (lock.equals(rootLock)) {
                    feed(md, "lock=root");
                } else {
                    feedFile(md, lock);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "err-" + System.nanoTime();
        }
    }

    /** Convenience: structure fingerprint from a resolved graph. */
    static String structureFingerprint(Path entryDir, BuildGraph.Result graph) {
        List<Path> unitDirs = new ArrayList<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            unitDirs.add(u.dir().toAbsolutePath().normalize());
        }
        return structureFingerprint(entryDir, unitDirs);
    }

    // BuildPlan shape (layer B)

    /**
     * Static plan outline for one module: total weight, serial test-step weight, and step
     * names/phases. Used to skip plan assembly on ETA-only paths and to skip
     * {@link cc.jumpkick.run.BuildPlan#estimatedTotalWeight} on prepare. Never trusted
     * under force/rebuild.
     */
    public record BuildPlanShape(int weight, int testWeight, List<StepShape> steps) {
        public record StepShape(String name, String phase) {}
    }

    /**
     * Shape key: toml + lock + skipTests + engine version — not sources (static plan outline).
     */
    public static String shapeFingerprint(Path moduleDir, boolean skipTests) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            feed(md, "shape");
            feed(md, "skip=" + (skipTests ? "1" : "0"));
            feed(md, BuildIdentity.cacheKeyVersion());
            feedFile(md, moduleDir.resolve("jk.toml"));
            feedFile(md, cc.jumpkick.lock.LockPaths.lockFile(moduleDir));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "err-" + System.nanoTime();
        }
    }

    public static Optional<BuildPlanShape> tryLoadShape(Path entryDir, Path moduleDir, boolean skipTests) {
        Path file = shapeMemoFile(entryDir);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            Path root = entryDir.toAbsolutePath().normalize();
            String rel = relKey(root, moduleDir.toAbsolutePath().normalize());
            String wantFp = shapeFingerprint(moduleDir, skipTests);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().startsWith("schema=" + SCHEMA)) return Optional.empty();
            String gotVersion = null;
            for (String line : lines) {
                if (line.startsWith("cacheKeyVersion=")) {
                    gotVersion = line.substring("cacheKeyVersion=".length());
                }
            }
            if (!BuildIdentity.cacheKeyVersion().equals(gotVersion)) return Optional.empty();

            // shape\trel\tfp\tweight\ttestWeight\tname:phase,...
            // Multiple rows per rel may exist (skipTests true vs false); match fingerprint.
            for (String line : lines) {
                if (!line.startsWith("shape\t")) continue;
                String[] p = line.split("\t", 6);
                if (p.length != 6) continue;
                if (!rel.equals(p[1])) continue;
                if (!wantFp.equals(p[2])) continue; // other skipTests/fp variant — keep scanning
                return Optional.of(
                        new BuildPlanShape(Integer.parseInt(p[3]), Integer.parseInt(p[4]), parseStepField(p[5])));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static List<BuildPlanShape.StepShape> parseStepField(String stepsField) {
        List<BuildPlanShape.StepShape> steps = new ArrayList<>();
        if (stepsField == null || stepsField.isBlank()) return List.of();
        for (String tok : stepsField.split(",")) {
            int c = tok.indexOf(':');
            if (c < 0) steps.add(new BuildPlanShape.StepShape(tok, ""));
            else steps.add(new BuildPlanShape.StepShape(tok.substring(0, c), tok.substring(c + 1)));
        }
        return List.copyOf(steps);
    }

    /**
     * Unique row key: module rel + shape fingerprint. Fingerprint embeds skipTests, so
     * alternating {@code --skip-tests} keeps both variants instead of thrashing.
     */
    private static String shapeRowKey(String rel, String fingerprint) {
        return rel + "\0" + fingerprint;
    }

    /** Upsert one module's plan shape into the shape memo. Best-effort. */
    public static void storeShape(Path entryDir, Path moduleDir, boolean skipTests, BuildPlanShape shape) {
        if (shape == null) return;
        Path root = entryDir.toAbsolutePath().normalize();
        Object lock = SHAPE_LOCKS.computeIfAbsent(root, k -> new Object());
        synchronized (lock) {
            try {
                Path file = shapeMemoFile(entryDir);
                Files.createDirectories(file.getParent());
                String rel = relKey(root, moduleDir.toAbsolutePath().normalize());
                String fp = shapeFingerprint(moduleDir, skipTests);
                StringBuilder steps = new StringBuilder();
                for (int i = 0; i < shape.steps().size(); i++) {
                    if (i > 0) steps.append(',');
                    BuildPlanShape.StepShape s = shape.steps().get(i);
                    steps.append(s.name()).append(':').append(s.phase() == null ? "" : s.phase());
                }
                String newLine =
                        "shape\t" + rel + "\t" + fp + "\t" + shape.weight() + "\t" + shape.testWeight() + "\t" + steps;

                Map<String, String> byKey = new LinkedHashMap<>();
                String gotVersion = BuildIdentity.cacheKeyVersion();
                if (Files.isRegularFile(file)) {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        if (line.startsWith("schema=") || line.startsWith("cacheKeyVersion=")) continue;
                        if (line.startsWith("shape\t")) {
                            String[] p = line.split("\t", 6);
                            if (p.length >= 3) byKey.put(shapeRowKey(p[1], p[2]), line);
                        }
                    }
                }
                byKey.put(shapeRowKey(rel, fp), newLine);
                // Bounded per-module rowsfingerprints embed skipTests, so
                // a live module keeps a couple of valid rows — but every jk.toml/lock edit mints
                // a NEW fingerprint and stale rows can never hit again. Rotate out the oldest
                // beyond a small cap instead of growing the memo forever.
                final int maxRowsPerModule = 4;
                List<String> sameRel = new ArrayList<>();
                for (String key : byKey.keySet()) {
                    if (key.startsWith(rel + "\u0000")) sameRel.add(key);
                }
                for (int i = 0; sameRel.size() - i > maxRowsPerModule; i++) {
                    byKey.remove(sameRel.get(i)); // insertion order — oldest first
                }

                StringBuilder sb = new StringBuilder();
                sb.append("schema=").append(SCHEMA).append('\n');
                sb.append("cacheKeyVersion=").append(gotVersion).append('\n');
                for (String line : byKey.values()) sb.append(line).append('\n');
                AtomicWrites.replace(file, sb.toString());
            } catch (Exception ignored) {
                // fail-open
            }
        }
    }

    /** Build a {@link BuildPlanShape} from an assembled plan (weights + step outline). */
    public static BuildPlanShape shapeOf(cc.jumpkick.run.BuildPlan plan, int weight) {
        List<BuildPlanShape.StepShape> steps = new ArrayList<>();
        int testWeight = 0;
        for (var s : plan.steps()) {
            String phase = s.group().orElse("");
            steps.add(new BuildPlanShape.StepShape(s.name(), phase));
            if ("run-tests".equals(s.name())) {
                try {
                    testWeight += s.estimateWeight();
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }
        return new BuildPlanShape(weight, testWeight, List.copyOf(steps));
    }

    // Module dirty fingerprint (sources)

    /**
     * Every regular file under dirs the build consumes feeds the digest: main
     * sources, main resources, default + named test suites and suite resources. Derived from
     * {@link cc.jumpkick.layout.ModuleLayout#fingerprintDirs}, not a fixed literal list.
     */
    static String fingerprintModule(Path moduleDir, boolean skipTests) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            feed(md, "skip=" + (skipTests ? "1" : "0"));
            feed(md, "mode=" + fingerprintMode());
            feedFile(md, moduleDir.resolve("jk.toml"));
            feedFile(md, cc.jumpkick.lock.LockPaths.lockFile(moduleDir));
            boolean mtimeMode = useMtimeMode();
            List<Path> roots = cc.jumpkick.layout.ModuleLayout.fingerprintDirs(moduleDir, skipTests);
            for (Path r : roots) {
                if (!Files.isDirectory(r)) continue;
                Files.walkFileTree(r, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()) {
                            feed(md, moduleDir.relativize(file).toString().replace('\\', '/'));
                            if (mtimeMode) {
                                feed(md, Long.toString(attrs.size()));
                                feed(md, Long.toString(attrs.lastModifiedTime().toMillis()));
                            } else {
                                // Stream, don't slurp: this is the DEFAULT path (mtime mode is
                                // opt-in), it runs over every file under the module including
                                // resources, and the engine's heap budget is 256 MB SerialGC — a
                                // single large resource was a transient allocation of its full
                                // size (JK-1482).
                                try (var in = Files.newInputStream(file)) {
                                    byte[] buf = HASH_BUFFER.get();
                                    int n;
                                    while ((n = in.read(buf)) > 0) {
                                        md.update(buf, 0, n);
                                    }
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

    /** Reused per hashing thread so streaming a tree does not allocate a buffer per file. */
    private static final ThreadLocal<byte[]> HASH_BUFFER = ThreadLocal.withInitial(() -> new byte[64 * 1024]);

    static String fingerprintMode() {
        return useMtimeMode() ? "mtime" : "content";
    }

    static boolean useMtimeMode() {
        String v = System.getenv("JK_PREFLIGHT_MEMO_MTIME");
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true"));
    }

    private static Path absFromRel(Path root, String rel) {
        if (".".equals(rel) || rel.isEmpty()) return root;
        return root.resolve(rel).normalize();
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

    private record UnitLine(String rel, String coord, String origin) {}
}
