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
import java.util.Optional;
import java.util.Set;

/**
 * Machine-local preflight memo (JK-1100+): dirty-set, graph structure, and pipeline shape caches
 * under {@code <entry>/target/.jk/preflight/}. Never git-committed; miss or corrupt → full recompute
 * (fail-open).
 *
 * <p>Schema 2: content-hash source fingerprints for dirty memo (JK-1108). Graph rebuild without
 * {@code WorkspaceLoader} (JK-1112). Pipeline shape weights (JK-1113).
 */
public final class PreflightMemo {

    static final String SCHEMA = "2";
    private static final String DIRTY_FILE = "dirty-memo.txt";
    private static final String GRAPH_FILE = "graph-memo.txt";
    private static final String SHAPE_FILE = "shape-memo.txt";

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

    // -------------------------------------------------------------------------
    // Dirty set (layer C)
    // -------------------------------------------------------------------------

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
                if (!row.fp().equals(fingerprintModule(dir, skipTests))) return Optional.empty();
                seen.add(rel);
                if (row.dirty()) dirty.add(dir);
            }
            if (!seen.equals(rows.keySet())) return Optional.empty();
            return Optional.of(dirty);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

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
                sb.append(rel)
                        .append('\t')
                        .append(fingerprintModule(dir, skipTests))
                        .append('\t')
                        .append(dirtyNorm.contains(dir) ? "1" : "0")
                        .append('\n');
            }
            AtomicWrites.replace(file, sb.toString());
        } catch (Exception ignored) {
            // fail-open
        }
    }

    // -------------------------------------------------------------------------
    // Graph structure (layer A) + rebuild without WorkspaceLoader (JK-1112)
    // -------------------------------------------------------------------------

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
            sb.append("cacheKeyVersion=").append(BuildIdentity.cacheKeyVersion()).append('\n');
            sb.append("structure=").append(structureFingerprint(entryDir, unitDirs)).append('\n');
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
     * Rebuild {@link BuildGraph.Result} from the graph memo without {@code WorkspaceLoader} (JK-1112).
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
                String coord = manifest.project().group() + ":" + manifest.project().name();
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
     * Structure key: entry {@code jk.toml}/{@code jk.lock} (workspace membership) + ordered unit
     * dirs with each module's toml/lock digests. Entry root is always included so dropping a module
     * from {@code [workspace].modules} invalidates even when the unit folder still exists. Edges are
     * not hashed (derived from manifests when tomls are unchanged).
     */
    static String structureFingerprint(Path entryDir, List<Path> unitDirs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            Path root = entryDir.toAbsolutePath().normalize();
            // Always pin the entry manifest (workspace module list lives here).
            feed(md, "entry");
            feedFile(md, root.resolve("jk.toml"));
            feedFile(md, root.resolve("jk.lock"));
            for (Path dir : unitDirs) {
                Path d = dir.toAbsolutePath().normalize();
                feed(md, relKey(root, d));
                feedFile(md, d.resolve("jk.toml"));
                feedFile(md, d.resolve("jk.lock"));
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

    // -------------------------------------------------------------------------
    // Pipeline shape (layer B) — JK-1113
    // -------------------------------------------------------------------------

    /**
     * Static pipeline outline for one module: total weight, serial test-step weight, and step
     * names/phases. Used to skip pipeline assembly on ETA-only paths (JK-1114) and to skip
     * {@link cc.jumpkick.run.Pipeline#estimatedTotalWeight()} on prepare (JK-1113). Never trusted
     * under force/rebuild.
     */
    public record PipelineShape(int weight, int testWeight, List<StepShape> steps) {
        public record StepShape(String name, String phase) {}

        /** Backward-compat constructor when test weight is unknown. */
        public PipelineShape(int weight, List<StepShape> steps) {
            this(weight, 0, steps);
        }
    }

    /**
     * Shape key: toml + lock + skipTests + engine version — not sources (static pipeline outline).
     */
    public static String shapeFingerprint(Path moduleDir, boolean skipTests) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            feed(md, "shape");
            feed(md, "skip=" + (skipTests ? "1" : "0"));
            feed(md, BuildIdentity.cacheKeyVersion());
            feedFile(md, moduleDir.resolve("jk.toml"));
            feedFile(md, moduleDir.resolve("jk.lock"));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "err-" + System.nanoTime();
        }
    }

    public static Optional<PipelineShape> tryLoadShape(Path entryDir, Path moduleDir, boolean skipTests) {
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

            // New: shape\trel\tfp\tweight\ttestWeight\tname:phase,...
            // Old: shape\trel\tfp\tweight\tname:phase,...  (testWeight defaults 0)
            for (String line : lines) {
                if (!line.startsWith("shape\t")) continue;
                String[] p = line.split("\t", 6);
                if (p.length < 4) continue;
                if (!rel.equals(p[1])) continue;
                if (!wantFp.equals(p[2])) return Optional.empty();
                int weight = Integer.parseInt(p[3]);
                int testWeight = 0;
                String stepsField = "";
                if (p.length >= 6) {
                    testWeight = Integer.parseInt(p[4]);
                    stepsField = p[5];
                } else if (p.length == 5) {
                    // Ambiguous: either old steps or new testWeight with empty steps.
                    if (p[4].chars().allMatch(Character::isDigit)) {
                        testWeight = Integer.parseInt(p[4]);
                    } else {
                        stepsField = p[4];
                    }
                }
                List<PipelineShape.StepShape> steps = parseStepField(stepsField);
                return Optional.of(new PipelineShape(weight, testWeight, steps));
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static List<PipelineShape.StepShape> parseStepField(String stepsField) {
        List<PipelineShape.StepShape> steps = new ArrayList<>();
        if (stepsField == null || stepsField.isBlank()) return List.of();
        for (String tok : stepsField.split(",")) {
            int c = tok.indexOf(':');
            if (c < 0) steps.add(new PipelineShape.StepShape(tok, ""));
            else steps.add(new PipelineShape.StepShape(tok.substring(0, c), tok.substring(c + 1)));
        }
        return List.copyOf(steps);
    }

    /** Upsert one module's pipeline shape into the shape memo. Best-effort. */
    public static void storeShape(
            Path entryDir, Path moduleDir, boolean skipTests, PipelineShape shape) {
        if (shape == null) return;
        try {
            Path root = entryDir.toAbsolutePath().normalize();
            Path file = shapeMemoFile(entryDir);
            Files.createDirectories(file.getParent());
            String rel = relKey(root, moduleDir.toAbsolutePath().normalize());
            String fp = shapeFingerprint(moduleDir, skipTests);
            StringBuilder steps = new StringBuilder();
            for (int i = 0; i < shape.steps().size(); i++) {
                if (i > 0) steps.append(',');
                PipelineShape.StepShape s = shape.steps().get(i);
                steps.append(s.name()).append(':').append(s.phase() == null ? "" : s.phase());
            }
            String newLine = "shape\t"
                    + rel
                    + "\t"
                    + fp
                    + "\t"
                    + shape.weight()
                    + "\t"
                    + shape.testWeight()
                    + "\t"
                    + steps;

            Map<String, String> byRel = new LinkedHashMap<>();
            String gotVersion = BuildIdentity.cacheKeyVersion();
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.startsWith("schema=") || line.startsWith("cacheKeyVersion=")) continue;
                    if (line.startsWith("shape\t")) {
                        String[] p = line.split("\t", 3);
                        if (p.length >= 2) byRel.put(p[1], line);
                    }
                }
            }
            byRel.put(rel, newLine);

            StringBuilder sb = new StringBuilder();
            sb.append("schema=").append(SCHEMA).append('\n');
            sb.append("cacheKeyVersion=").append(gotVersion).append('\n');
            for (String line : byRel.values()) sb.append(line).append('\n');
            AtomicWrites.replace(file, sb.toString());
        } catch (Exception ignored) {
            // fail-open
        }
    }

    /** Build a {@link PipelineShape} from an assembled pipeline (weights + step outline). */
    public static PipelineShape shapeOf(cc.jumpkick.run.Pipeline pipeline, int weight) {
        List<PipelineShape.StepShape> steps = new ArrayList<>();
        int testWeight = 0;
        for (var s : pipeline.steps()) {
            String phase = s.phase()
                    .map(p -> p.name().toLowerCase(java.util.Locale.ROOT))
                    .orElse("");
            steps.add(new PipelineShape.StepShape(s.name(), phase));
            if ("run-tests".equals(s.name())) {
                try {
                    testWeight += s.estimateWeight();
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }
        return new PipelineShape(weight, testWeight, List.copyOf(steps));
    }

    // -------------------------------------------------------------------------
    // Module dirty fingerprint (sources)
    // -------------------------------------------------------------------------

    static String fingerprintModule(Path moduleDir, boolean skipTests) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            feed(md, "skip=" + (skipTests ? "1" : "0"));
            feed(md, "mode=" + fingerprintMode());
            feedFile(md, moduleDir.resolve("jk.toml"));
            feedFile(md, moduleDir.resolve("jk.lock"));
            boolean mtimeMode = useMtimeMode();
            List<Path> roots = List.of(moduleDir.resolve("src"), moduleDir.resolve("test"));
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

    static boolean useMtimeMode() {
        String v = System.getenv("JK_PREFLIGHT_MEMO_MTIME");
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true"));
    }

    // -------------------------------------------------------------------------

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
