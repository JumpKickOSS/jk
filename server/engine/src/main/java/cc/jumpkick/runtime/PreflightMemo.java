// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.ModuleLayoutPlugins;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginModule;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Machine-local preflight memos: dirty-set, graph structure, and plan shape. Written under
 * {@code <entry>/target/.jk/preflight/} and dual-written to {@code ~/.jk/cache/projects/<id>/preflight/}
 * so {@code jk clean} does not erase input fingerprints. Never git-committed; miss or corrupt →
 * full recompute (fail-open); an input that will not read is an {@link Uncertain} fingerprint,
 * which schedules the module and names the file.
 *
 * <p>Schema 3: module fingerprints cover every file under {@code src}/{@code test}/
 * suite resource dirs (resources included); dirty rows are stored with fingerprints captured at
 * preflight time, never post-build.
 */
public final class PreflightMemo {

    static final int SCHEMA = 1;
    private static final String DIRTY_FILE = "dirty-memo.txt";
    private static final String GRAPH_FILE = "graph-memo.txt";
    private static final String SHAPE_FILE = "shape-memo.txt";

    /**
     * Serialize shape-memo upserts per entry directory. Parallel prepare races
     * read-modify-write on a single file; last writer must not drop peer modules' rows. Striped,
     * not keyed: a clear-on-overflow map could invalidate a monitor a peer was holding —
     * reintroducing exactly the dropped-rows race this exists to stop.
     */
    private static final Object[] SHAPE_LOCKS = new Object[64];

    static {
        for (int i = 0; i < SHAPE_LOCKS.length; i++) SHAPE_LOCKS[i] = new Object();
    }

    private PreflightMemo() {}

    /** A preflight fingerprint: the digest of what was read, or the input that could not be. */
    public sealed interface Fingerprint permits Known, Uncertain {}

    /** The digest of a module's inputs, or of a structure or shape key. */
    public record Known(String hex) implements Fingerprint {}

    /**
     * The preflight could not read {@code input}, so no memo can vouch for what depends on it.
     * The module is scheduled and {@code jk explain} says why — rather than a random digest that
     * forced the same rebuild while looking like an input change.
     */
    public record Uncertain(Path input, String cause) implements Fingerprint {
        public String reason() {
            return "the preflight could not read " + input + " (" + cause + ")";
        }
    }

    /** The one file read the fingerprints depend on; carries the path so an Uncertain can name it. */
    private static final class UnreadableInput extends IOException {
        private final Path input;

        UnreadableInput(Path input, IOException cause) {
            super(cause.getMessage(), cause);
            this.input = input;
        }
    }

    private static Uncertain uncertain(Path fallback, Exception e) {
        if (e instanceof UnreadableInput u) return new Uncertain(u.input, describe(u.getCause()));
        return new Uncertain(fallback, describe(e));
    }

    private static String describe(@Nullable Throwable t) {
        if (t == null) return "unknown";
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
    }

    /**
     * What the preflight could fingerprint and what it could not, keyed by normalized module dir.
     * A module in {@link #uncertain} has no entry in {@link #fingerprints}.
     */
    public record Snapshot(Map<Path, String> fingerprints, Map<Path, Uncertain> uncertain) {
        public Snapshot {
            fingerprints = fingerprints == null ? Map.of() : Map.copyOf(fingerprints);
            uncertain = uncertain == null ? Map.of() : Map.copyOf(uncertain);
        }
    }

    public static Path memoFile(Path entryDir) {
        return entryDir.resolve(BuildLayout.TARGET)
                .resolve(".jk")
                .resolve("preflight")
                .resolve(DIRTY_FILE);
    }

    public static Path graphMemoFile(Path entryDir) {
        return entryDir.resolve(BuildLayout.TARGET)
                .resolve(".jk")
                .resolve("preflight")
                .resolve(GRAPH_FILE);
    }

    public static Path shapeMemoFile(Path entryDir) {
        return entryDir.resolve(BuildLayout.TARGET)
                .resolve(".jk")
                .resolve("preflight")
                .resolve(SHAPE_FILE);
    }

    /**
     * Durable dirty-memo under the product cache so {@code jk clean} (which wipes {@code target/})
     * does not erase input fingerprints. Prefer this on load when present and valid.
     */
    public static Path durableMemoFile(Path entryDir) {
        return durablePreflightDir(entryDir).resolve(DIRTY_FILE);
    }

    static Path durablePreflightDir(Path entryDir) {
        String key = workspaceKey(entryDir);
        return CacheTree.PROJECTS.under(JkDirs.cache()).resolve(key).resolve("preflight");
    }

    static String workspaceKey(Path entryDir) {
        Path p = entryDir.toAbsolutePath().normalize();
        try {
            if (Files.exists(p)) p = p.toRealPath();
        } catch (IOException ignored) {
            // keep normalized absolute path
        }
        return Hashing.sha256Hex(p.toString()).substring(0, 16);
    }

    /** Prefer durable memo (survives clean), else the in-tree target memo. */
    static @Nullable Path resolveDirtyMemoFile(Path entryDir) {
        Path durable = durableMemoFile(entryDir);
        if (Files.isRegularFile(durable)) return durable;
        Path local = memoFile(entryDir);
        return Files.isRegularFile(local) ? local : null;
    }

    // Dirty set (layer C)

    /**
     * A memo hit: input-dirty modules, validated fingerprints, and modules whose inputs still match
     * but required PACKAGE outputs are missing (restore from action cache — not a full rebuild).
     */
    public record DirtyMemo(Set<Path> dirty, Map<Path, String> fingerprints, Set<Path> restoreNeeded) {
        public DirtyMemo {
            dirty = dirty == null ? Set.of() : Set.copyOf(dirty);
            fingerprints = fingerprints == null ? Map.of() : Map.copyOf(fingerprints);
            restoreNeeded = restoreNeeded == null ? Set.of() : Set.copyOf(restoreNeeded);
        }

        /** Input-dirty only ({@code restoreNeeded} empty). */
        public DirtyMemo(Set<Path> dirty, Map<Path, String> fingerprints) {
            this(dirty, fingerprints, Set.of());
        }
    }

    public static Optional<DirtyMemo> tryLoadDirty(Path entryDir, BuildGraph.Result graph, boolean skipTests) {
        Path file = resolveDirtyMemoFile(entryDir);
        if (file == null || !Files.isRegularFile(file)) return Optional.empty();
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
            Set<Path> restoreNeeded = new LinkedHashSet<>();
            Map<Path, String> fps = new LinkedHashMap<>();
            Set<String> seen = new LinkedHashSet<>();
            for (BuildGraph.BuildUnit u : units) {
                Path dir = u.dir().toAbsolutePath().normalize();
                String rel = relKey(root, dir);
                MemoRow row = rows.get(rel);
                if (row == null) return Optional.empty();
                Fingerprint now = fingerprintModule(dir, skipTests);
                if (!(now instanceof Known known) || !row.fp().equals(known.hex())) return Optional.empty();
                seen.add(rel);
                fps.put(dir, row.fp());
                if (row.dirty()) {
                    dirty.add(dir);
                } else if (ModuleOutputRestore.packageOutputsMissing(root, dir, u.manifest())) {
                    // Inputs still match — missing jars/classes need action-cache restore, not
                    // a memo miss that forces a full TaskForecaster rebuild wall.
                    restoreNeeded.add(dir);
                }
            }
            if (!seen.equals(rows.keySet())) return Optional.empty();
            return Optional.of(new DirtyMemo(dirty, fps, restoreNeeded));
        } catch (IOException e) {
            // The memo itself would not read: not a miss to hide. Every module takes the walk.
            Log.warn(
                    "jk: preflight memo unreadable — every module is checked against the action cache instead",
                    "memo",
                    file,
                    "cause",
                    describe(e));
            return Optional.empty();
        } catch (RuntimeException e) {
            Log.debug("tryLoadDirty: memo miss", e);
            return Optional.empty();
        }
    }

    /**
     * Per-module fingerprints captured now. Callers snapshot BEFORE forecasting or building and
     * hand {@link Snapshot#fingerprints} to {@link #storeDirty}: a store must never fingerprint
     * post-build, or a mid-build edit is recorded as clean and never rebuilt. A module whose
     * inputs would not read lands in {@link Snapshot#uncertain} instead.
     */
    public static Snapshot snapshotFingerprints(BuildGraph.Result graph, boolean skipTests) {
        Map<Path, String> fps = new LinkedHashMap<>();
        Map<Path, Uncertain> uncertain = new LinkedHashMap<>();
        Map<Path, String> saltByRoot = new LinkedHashMap<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            Path dir = u.dir().toAbsolutePath().normalize();
            Path root = WorkspaceScan.findRoot(dir).orElse(dir).toAbsolutePath().normalize();
            String salt = saltByRoot.computeIfAbsent(root, PreflightMemo::guardSalt);
            switch (fingerprintModule(dir, skipTests, salt)) {
                case Known k -> fps.put(dir, k.hex());
                case Uncertain u2 -> uncertain.put(dir, u2);
            }
        }
        return new Snapshot(fps, uncertain);
    }

    /**
     * The guard rule and baseline files as a module input, when the workspace has guards: a rule
     * edit must re-plan every module so its lane re-evaluates against the cached facts — a module
     * skipped as "up to date" never reaches its lane. Empty for a workspace without guards, so the
     * fingerprint is what it always was.
     */
    static String guardSalt(Path root) {
        if (!PlannerGuards.enabledAt(root)) return "";
        try {
            MessageDigest md = Hashing.newSha256();
            feedFile(md, GuardsPresence.rulesFile(root));
            feedFile(md, GuardsPresence.baselineFile(root));
            return "guards=" + Hashing.hex(md.digest());
        } catch (IOException e) {
            return "guards=unreadable";
        }
    }

    public static void storeDirty(
            Path entryDir,
            BuildGraph.Result graph,
            boolean skipTests,
            Set<Path> dirty,
            Map<Path, String> fingerprints) {
        try {
            Path root = entryDir.toAbsolutePath().normalize();
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
                if (fp == null) {
                    // The graph drifted from the snapshot, or an input the preflight could not
                    // read left this module without a fingerprint: a memo cannot vouch for it.
                    Log.debug("storeDirty: not stored, no fingerprint for a unit", "unit", dir);
                    return;
                }
                sb.append(relKey(root, dir))
                        .append('\t')
                        .append(fp)
                        .append('\t')
                        .append(dirtyNorm.contains(dir) ? "1" : "0")
                        .append('\n');
            }
            String body = sb.toString();
            // Dual-write: durable cache survives jk clean; in-tree copy stays for local inspection.
            Path durable = durableMemoFile(entryDir);
            Files.createDirectories(durable.getParent());
            AtomicWrites.replace(durable, body);
            writeInTree(memoFile(entryDir), entryDir, body);
        } catch (Exception e) {
            // fail-open
            Log.debug("storeDirty: fail-open", e);
        }
    }

    /**
     * In-tree copy, written only while {@code target/} is alive. Memo stores run after the
     * plan's terminal event reaches the client, so a fast follow-up {@code jk clean [--force]}
     * can wipe target in the gap — recreating {@code target/.jk} here resurrected the dir the
     * clean just removed AND left a memo claiming outputs that no longer exist. The
     * durable copy is authoritative; the in-tree copy is inspection-only.
     */
    private static void writeInTree(Path file, Path entryDir, String body) throws IOException {
        if (!Files.isDirectory(entryDir.resolve(BuildLayout.TARGET))) return;
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, body);
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
            // Same clean-race guard as the dirty memo: never resurrect target/.
            if (!Files.isDirectory(entryDir.resolve(BuildLayout.TARGET))) return;
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
            if (!(structureFingerprint(entryDir, unitDirs) instanceof Known structure)) return;
            sb.append("structure=").append(structure.hex()).append('\n');
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
        } catch (Exception e) {
            // fail-open
            Log.debug("storeGraph: fail-open", e);
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
                if (!Files.isRegularFile(dir.resolve(ManifestPaths.MANIFEST))) return Optional.empty();
                unitDirs.add(dir);
            }
            if (!(structureFingerprint(entryDir, unitDirs) instanceof Known structure)
                    || !gotStruct.equals(structure.hex())) return Optional.empty();

            // Rebuild units by re-parsing manifests (no WorkspaceLoader membership walk).
            List<BuildGraph.BuildUnit> topo = new ArrayList<>();
            Map<Path, Set<Path>> edges = new LinkedHashMap<>();
            Map<String, Path> dirByRel = new LinkedHashMap<>();
            for (int i = 0; i < units.size(); i++) {
                UnitLine ul = units.get(i);
                Path dir = unitDirs.get(i);
                dirByRel.put(ul.rel(), dir);
                JkBuild manifest = JkBuildParser.parse(dir.resolve(ManifestPaths.MANIFEST));
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
                    edges.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
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
            if (!(structureFingerprint(entryDir, unitDirs) instanceof Known structure)) return false;
            String want = structure.hex();
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
    static Fingerprint structureFingerprint(Path entryDir, List<Path> unitDirs) {
        try {
            MessageDigest md = Hashing.newSha256();
            Path root = entryDir.toAbsolutePath().normalize();
            // Always pin the entry manifest (workspace module list lives here). Whether the root
            // itself is a buildable unit depends on it having sources, not on any toml — pin that
            // too, or a root that grows src/ keeps hitting a graph memo without a root unit.
            feed(md, "entry");
            feed(md, "rootSources=" + (CompileSupport.hasSources(root) ? "1" : "0"));
            feedFile(md, root.resolve(ManifestPaths.MANIFEST));
            Path rootLock = LockPaths.lockFile(root).toAbsolutePath().normalize();
            feedFile(md, rootLock);
            for (Path dir : unitDirs) {
                Path d = dir.toAbsolutePath().normalize();
                feed(md, relKey(root, d));
                feedFile(md, d.resolve(ManifestPaths.MANIFEST));
                // Every workspace member resolves to the single root lock — already digested
                // above; re-reading a monorepo-sized lock once per module scaled the key cost by
                // modules × lock size. A marker keeps the structural position; a module
                // with a genuinely distinct lock (standalone unit) still digests its own.
                Path lock = LockPaths.lockFile(d).toAbsolutePath().normalize();
                if (lock.equals(rootLock)) {
                    feed(md, "lock=root");
                } else {
                    feedFile(md, lock);
                }
            }
            return new Known(Hashing.hex(md.digest()));
        } catch (Exception e) {
            return uncertain(entryDir, e);
        }
    }

    /** Convenience: structure fingerprint from a resolved graph. */
    static Fingerprint structureFingerprint(Path entryDir, BuildGraph.Result graph) {
        List<Path> unitDirs = new ArrayList<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            unitDirs.add(u.dir().toAbsolutePath().normalize());
        }
        return structureFingerprint(entryDir, unitDirs);
    }

    // BuildPlan shape (layer B)

    /**
     * Static plan outline for one module: total weight, serial test-step weight, and step
     * names/phases. Shape fingerprint ignores sources — weights are not freshness-aware.
     *
     * <p><b>Dirty prepare never uses shape-memo for bar weight</b> ({@code forceRebuild} always
     * re-runs {@link cc.jumpkick.run.BuildPlan#estimatedTotalWeight} with over-reserve tails).
     * Memo remains optional for clean / ETA-only outline hits and for storing the live outline
     * after a dirty prepare.
     */
    public record BuildPlanShape(int weight, int testWeight, List<StepShape> steps) {
        public record StepShape(String name, String phase) {}
    }

    /**
     * Shape key: toml + lock + skipTests + engine version — not sources (static plan outline).
     */
    public static Fingerprint shapeFingerprint(Path moduleDir, boolean skipTests) {
        try {
            MessageDigest md = Hashing.newSha256();
            feed(md, "shape");
            feed(md, "skip=" + (skipTests ? "1" : "0"));
            feed(md, BuildIdentity.cacheKeyVersion());
            feedFile(md, moduleDir.resolve(ManifestPaths.MANIFEST));
            feedFile(md, LockPaths.lockFile(moduleDir));
            return new Known(Hashing.hex(md.digest()));
        } catch (Exception e) {
            return uncertain(moduleDir, e);
        }
    }

    public static Optional<BuildPlanShape> tryLoadShape(Path entryDir, Path moduleDir, boolean skipTests) {
        Path file = shapeMemoFile(entryDir);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            Path root = entryDir.toAbsolutePath().normalize();
            String rel = relKey(root, moduleDir.toAbsolutePath().normalize());
            if (!(shapeFingerprint(moduleDir, skipTests) instanceof Known want)) return Optional.empty();
            String wantFp = want.hex();
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
        Object lock = SHAPE_LOCKS[Math.floorMod(root.hashCode(), SHAPE_LOCKS.length)];
        synchronized (lock) {
            try {
                Path file = shapeMemoFile(entryDir);
                Files.createDirectories(file.getParent());
                String rel = relKey(root, moduleDir.toAbsolutePath().normalize());
                if (!(shapeFingerprint(moduleDir, skipTests) instanceof Known known)) return;
                String fp = known.hex();
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
            } catch (Exception e) {
                // fail-open
                Log.debug("storeShape: fail-open", e);
            }
        }
    }

    /** Build a {@link BuildPlanShape} from an assembled plan (weights + step outline). */
    public static BuildPlanShape shapeOf(BuildPlan plan, int weight) {
        List<BuildPlanShape.StepShape> steps = new ArrayList<>();
        int testWeight = 0;
        for (var s : plan.steps()) {
            String phase = s.group().orElse("");
            steps.add(new BuildPlanShape.StepShape(s.name(), phase));
            if (TaskNames.RUN_TESTS.equals(s.name())) {
                try {
                    testWeight += s.estimateWeight();
                } catch (Exception e) {
                    // best-effort
                    Log.debug("shapeOf: best-effort", e);
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
    static Fingerprint fingerprintModule(Path moduleDir, boolean skipTests) {
        return fingerprintModule(moduleDir, skipTests, "");
    }

    static Fingerprint fingerprintModule(Path moduleDir, boolean skipTests, String guardSalt) {
        try {
            InputTrees.coverModule(moduleDir);
            MessageDigest md = Hashing.newSha256();
            feed(md, "skip=" + (skipTests ? "1" : "0"));
            feed(md, "mode=" + fingerprintMode());
            if (!guardSalt.isEmpty()) feed(md, guardSalt);
            feedFile(md, moduleDir.resolve(ManifestPaths.MANIFEST));
            feedFile(md, LockPaths.lockFile(moduleDir));
            // Plugin workers keep jk-plugin.toml at the module root (copied onto the jar root).
            if (PluginModule.isWorker(moduleDir)) {
                feedFile(md, moduleDir.resolve(ManifestPaths.PLUGIN_MANIFEST));
            }
            boolean mtimeMode = useMtimeMode();
            List<Path> roots = new ArrayList<>(ModuleLayout.fingerprintDirs(moduleDir, skipTests));
            for (var root : ModuleLayoutPlugins.pluginContributedRoots(moduleDir)) {
                Path p = moduleDir.resolve(root.relative());
                if (Files.isDirectory(p)) roots.add(p.toAbsolutePath().normalize());
            }
            for (Path r : roots) {
                if (!Files.isDirectory(r)) continue;
                var snap = InputTrees.of(r);
                if (snap.overflow()) {
                    // A file that vanished mid-walk is skipped by the helper; a directory that
                    // cannot be opened surfaces as an uncertain fingerprint, which is the honest
                    // answer — a fingerprint over a tree with a hole in it would look stable.
                    PathUtil.forEachRegularFile(
                            r,
                            (file, attrs) -> feedFingerprint(
                                    md,
                                    moduleDir,
                                    file,
                                    attrs.size(),
                                    attrs.lastModifiedTime().toMillis(),
                                    mtimeMode));
                    continue;
                }
                for (var ref : snap.files()) {
                    feedFingerprint(md, moduleDir, ref.path(), ref.size(), ref.mtimeMillis(), mtimeMode);
                }
            }
            return new Known(Hashing.hex(md.digest()));
        } catch (Exception e) {
            return uncertain(moduleDir, e);
        }
    }

    /**
     * One byte contract for both walk shapes: the digest must not depend on whether the tree came
     * from a snapshot or a live walk, or a module crossing the retain boundary between runs would
     * rebuild for no input change.
     */
    private static void feedFingerprint(
            MessageDigest md, Path moduleDir, Path file, long size, long mtimeMillis, boolean mtimeMode) {
        feed(md, moduleDir.relativize(file).toString().replace('\\', '/'));
        if (mtimeMode) {
            feed(md, Long.toString(size));
            feed(md, Long.toString(mtimeMillis));
            return;
        }
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

    /** Reused per hashing thread so streaming a tree does not allocate a buffer per file. */
    private static final ThreadLocal<byte[]> HASH_BUFFER = ThreadLocal.withInitial(() -> new byte[64 * 1024]);

    static String fingerprintMode() {
        return useMtimeMode() ? "mtime" : "content";
    }

    static boolean useMtimeMode() {
        return EnvValues.bool(System::getenv, "JK_PREFLIGHT_MEMO_MTIME").orElse(false);
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
        try {
            md.update(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UnreadableInput(file, e);
        }
        md.update((byte) 0);
    }

    private static void feed(MessageDigest md, String s) {
        md.update(s.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
    }

    private record MemoRow(String fp, boolean dirty) {}

    private record UnitLine(String rel, String coord, String origin) {}
}
