// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.GraphSnapshot;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.codegraph.StaleRef;
import cc.jumpkick.codegraph.extract.Extractor;
import cc.jumpkick.codegraph.extract.Extractors;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.StatIdentity;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * One refresh of a workspace's graph, in the graph worker. Walks the tree once and compares every
 * file's stat with the last build's; when nothing moved, the class indexes are the ones the last
 * graph was built from and the committed store is the one it was linked from, the stored snapshot
 * is the answer. Otherwise every changed file is hashed and, unless the committed store holds its
 * bytes, extracted again (in parallel), the class facts are mapped, and the graph is linked and
 * written: the committed store under {@code .jk/graph/} ({@link FragmentStore}), and this
 * checkout's {@code graph.bin} and {@code files.tsv} under {@code target/graph/}. A file whose
 * classes this checkout has not built, or built from other bytes, takes the compiled facts the
 * store holds for its bytes, so a fresh checkout answers from the facts of whoever built last.
 */
public final class GraphBuild {

    private GraphBuild() {}

    /** Receives a refresh's progress; every method may be called from several threads. */
    public interface Progress {
        Progress NONE = new Progress() {};

        /** The walk found {@code files} files, {@code stale} of which will be read. */
        default void planned(int files, int stale) {}

        /** One file settled: {@code extracted} false when its stored fragment was reused. */
        default void file(String rel, boolean extracted) {}

        /** A step with no per-file ticks started. */
        default void phase(String label) {}
    }

    /**
     * One module's class set: its facts index, the module's path prefix, the index's digest and
     * class count, and the workspace-relative sources edited since those classes were built, whose
     * own classes are skipped (their source scan answers for them).
     */
    public record ClassIndex(Path index, String modulePrefix, String digest, int classes, Set<String> stale) {
        public ClassIndex {
            stale = Set.copyOf(stale);
        }
    }

    /** What one refresh did; {@code changed} false when the stored snapshot was the answer. */
    public record Result(
            CodeGraph graph,
            int files,
            int extracted,
            int classes,
            Path dir,
            long millis,
            boolean changed,
            @Nullable String commit) {}

    public static Result refresh(
            Path root,
            Path out,
            Path shared,
            List<ClassIndex> indexes,
            Modules modules,
            ScriptReader scripts,
            boolean rebuild,
            Progress progress)
            throws IOException {
        return refresh(root, out, shared, indexes, modules, scripts, rebuild, progress, Clock.SYSTEM);
    }

    static Result refresh(
            Path root,
            Path out,
            Path shared,
            List<ClassIndex> indexes,
            Modules modules,
            ScriptReader scripts,
            boolean rebuild,
            Progress progress,
            Clock clock)
            throws IOException {
        long start = clock.nanos();
        Path base = root.toAbsolutePath().normalize();
        List<GraphSources.Source> sources = GraphSources.walk(base);
        // A copy: the stats in memory change only once the graph they describe is written, so a
        // refresh that fails part-way never lets the next one serve the graph from before it.
        StatIndex stats = rebuild ? StatIndex.empty() : GraphMemory.stats(out).copy();
        long now = clock.millis();
        Map<String, String> shas = new ConcurrentHashMap<>();
        List<GraphSources.Source> stale = new ArrayList<>();
        for (GraphSources.Source s : sources) {
            String sha = stats.unchanged(s, now);
            if (sha != null) shas.put(s.rel(), sha);
            else stale.add(s);
        }
        progress.planned(sources.size(), stale.size());
        Map<String, byte[]> changed = new ConcurrentHashMap<>();
        // A file that moved while it was read is extracted from the bytes read, but its hash is
        // not filed under the stat taken before the read: the next refresh reads it again.
        Set<String> moved = ConcurrentHashMap.newKeySet();
        // A file that would not read contributes nothing, and its earlier fragment goes with it.
        Set<String> unreadable = ConcurrentHashMap.newKeySet();
        stale.parallelStream().forEach(s -> {
            try {
                byte[] bytes = Files.readAllBytes(s.path());
                String sha = Hashing.sha256Hex(bytes);
                shas.put(s.rel(), sha);
                if (!sha.equals(stats.sha(s.rel()))) changed.put(s.rel(), bytes);
                if (!s.identity().equals(StatIdentity.of(s.path()))) moved.add(s.rel());
            } catch (IOException failed) {
                unreadable.add(s.rel());
                Log.debug("graph: cannot read " + s.rel(), failed);
            }
        });
        unreadable.forEach(stats::remove);
        for (GraphSources.Source s : sources) {
            String sha = shas.get(s.rel());
            if (sha != null && !moved.contains(s.rel())) stats.put(s, sha);
        }
        String classDigest = classDigest(indexes, modules);
        String commit = GitHead.read(base);
        String extractors = Extractors.VERSION + (scripts.active() ? "+oxc" : "");
        String inputs = inputs(shas);
        GraphSnapshot.Key key = new GraphSnapshot.Key(
                extractors, sources.size(), linkedFrom(classDigest, FragmentStore.stamp(shared)), inputs, commit);
        boolean sameFiles = changed.isEmpty() && unreadable.isEmpty() && stats.size() == sources.size();
        if (!rebuild && sameFiles) {
            CodeGraph stored = snapshot(out, key);
            if (stored != null) {
                if (!stale.isEmpty()) {
                    stats.save(out, sources);
                    GraphMemory.savedStats(out, stats);
                }
                sources.forEach(s -> progress.file(s.rel(), false));
                long millis = (clock.nanos() - start) / 1_000_000;
                return new Result(stored, sources.size(), 0, classes(indexes), out, millis, false, commit);
            }
        }
        FragmentStore store = rebuild ? FragmentStore.empty() : GraphMemory.store(out, shared);
        List<String> unread = new ArrayList<>();
        for (GraphSources.Source s : sources) {
            String sha = shas.get(s.rel());
            if (sha != null && ScriptReader.handles(s.rel()) && store.get(key(s.rel(), sha, scripts)) == null) {
                unread.add(s.rel());
            }
        }
        if (scripts.active() && !unread.isEmpty()) progress.phase("read scripts with oxc");
        Map<String, Fragment> oxc = scripts.read(base, unread);
        AtomicInteger extracted = new AtomicInteger();
        Map<String, Fragment> fragments = new ConcurrentHashMap<>();
        Map<String, String> keys = new ConcurrentHashMap<>();
        sources.parallelStream().forEach(s -> {
            String sha = shas.get(s.rel());
            if (sha == null) return;
            String k = key(s.rel(), sha, scripts);
            Fragment stored = store.get(k);
            Fragment read = oxc.get(s.rel());
            Fragment f = stored != null ? stored : read != null ? read : extract(s, changed.get(s.rel()));
            if (stored == null) {
                if (read == null) k = FragmentStore.key(s.rel(), sha, "");
                store.put(k, f);
                extracted.incrementAndGet();
            }
            keys.put(s.rel(), k);
            if (!f.nodes().isEmpty()) fragments.put(s.rel(), f);
            progress.file(s.rel(), stored == null);
        });
        List<Fragment> all = new ArrayList<>();
        for (GraphSources.Source s : sources) {
            Fragment f = fragments.get(s.rel());
            if (f != null) all.add(f);
        }
        progress.phase("map compiled classes");
        Map<String, Fragment> built = new HashMap<>();
        int classes = mapClasses(sources, indexes, built);
        List<FragmentStore.Row> rows = new ArrayList<>();
        for (GraphSources.Source s : sources) {
            String k = keys.get(s.rel());
            String sha = shas.get(s.rel());
            if (k == null || sha == null) continue;
            Fragment compiled = built.get(s.rel());
            if (compiled == null) compiled = store.classes(s.rel(), sha);
            if (compiled != null) all.add(compiled);
            rows.add(new FragmentStore.Row(s.rel(), k, compiled));
        }
        progress.phase("link");
        CodeGraph graph = Linker.link(all);
        graph.modules(modules);
        graph.stale(stale(base, graph.stale()));
        progress.phase("write graph");
        store.save(shared, rows, modules);
        GraphMemory.savedStore(out, shared, store);
        GraphSnapshot.Key written = new GraphSnapshot.Key(
                extractors, sources.size(), linkedFrom(classDigest, FragmentStore.stamp(shared)), inputs, commit);
        write(out, graph, written);
        GraphMemory.sawGraph(out, graph, written);
        stats.save(out, sources);
        GraphMemory.savedStats(out, stats);
        long millis = (clock.nanos() - start) / 1_000_000;
        return new Result(graph, sources.size(), extracted.get(), classes, out, millis, true, commit);
    }

    /**
     * The linker's candidates without links to files that exist outside the graph (images,
     * licenses, directories), sorted by file and line and cut to {@link StaleRef#LIMIT}.
     */
    static List<StaleRef> stale(Path root, List<StaleRef> candidates) {
        return candidates.stream()
                .filter(r -> !r.kind().equals(StaleRef.FILE) || !Files.exists(root.resolve(r.target())))
                .distinct()
                .sorted(Comparator.comparing(StaleRef::file).thenComparingInt(StaleRef::line))
                .limit(StaleRef.LIMIT)
                .toList();
    }

    /** The store key of a file's fragment: an oxc read is kept apart from a scanner read of the same bytes. */
    private static String key(String rel, String sha, ScriptReader scripts) {
        return FragmentStore.key(rel, sha, scripts.flavor(rel));
    }

    /**
     * The digest of every file a refresh read, by path and content hash, in path order: what a
     * stored graph must have been built from to answer for the tree as it is.
     */
    static String inputs(Map<String, String> shas) {
        StringBuilder all = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(shas).entrySet()) {
            all.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        return Hashing.sha256Hex(all.toString());
    }

    /** What a graph was linked from: the class indexes and modules, and the committed store's files as they stood. */
    private static String linkedFrom(String classDigest, String storeStamp) {
        return Hashing.sha256Hex(classDigest + "\n" + storeStamp);
    }

    /** The stored graph when its key matches, else {@code null}: from memory while it is current. */
    private static @Nullable CodeGraph snapshot(Path out, GraphSnapshot.Key key) {
        GraphMemory.Graph held = GraphMemory.graph(out);
        if (held != null) return sameKey(held.key(), key) ? held.graph() : null;
        Path bin = out.resolve(GraphOutputs.SNAPSHOT);
        if (!Files.isRegularFile(bin)) return null;
        try (InputStream in = Files.newInputStream(bin)) {
            GraphSnapshot.Loaded loaded = GraphSnapshot.read(in);
            if (loaded == null) return null;
            GraphMemory.sawGraph(out, loaded.graph(), loaded.key());
            return sameKey(loaded.key(), key) ? loaded.graph() : null;
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("graph: snapshot unreadable at " + bin, unreadable);
            return null;
        }
    }

    private static boolean sameKey(GraphSnapshot.Key had, GraphSnapshot.Key key) {
        return had.extractors().equals(key.extractors())
                && had.files() == key.files()
                && had.classes().equals(key.classes())
                && had.inputs().equals(key.inputs());
    }

    private static void write(Path out, CodeGraph graph, GraphSnapshot.Key key) throws IOException {
        Files.createDirectories(out);
        Path tmp = AtomicWrites.staging(out.resolve(GraphOutputs.SNAPSHOT));
        try (OutputStream o = Files.newOutputStream(tmp)) {
            GraphSnapshot.write(graph, key, o);
        }
        AtomicWrites.moveInto(tmp, out.resolve(GraphOutputs.SNAPSHOT));
    }

    /** Extracts one file from {@code bytes}, or from disk when the caller did not read it. */
    private static Fragment extract(GraphSources.Source s, byte @Nullable [] bytes) {
        Extractor e = Extractors.forPath(s.rel());
        if (e == null) return Fragment.EMPTY;
        try {
            byte[] content = bytes != null ? bytes : Files.readAllBytes(s.path());
            return e.extract(s.rel(), new String(content, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failed) {
            Log.debug("graph: cannot extract " + s.rel(), failed);
            return Fragment.EMPTY;
        }
    }

    /**
     * Puts each class index's mapping into {@code bySource}, one fragment per source file the
     * classes came from; returns the class count.
     */
    private static int mapClasses(
            List<GraphSources.Source> sources, List<ClassIndex> indexes, Map<String, Fragment> bySource) {
        Map<String, List<String>> byName = new HashMap<>();
        for (GraphSources.Source s : sources) {
            if (Extractors.isJvmSource(s.rel())) {
                byName.computeIfAbsent(s.rel().substring(s.rel().lastIndexOf('/') + 1), k -> new ArrayList<>())
                        .add(s.rel());
            }
        }
        int classes = 0;
        for (ClassIndex c : indexes) {
            try {
                var index = FactsFormat.read(c.index());
                classes += index.classes().size();
                Fragment mapped = JvmFacts.map(index, (pkg, file) -> {
                    String rel = find(byName, c.modulePrefix(), pkg, file);
                    return rel == null || c.stale().contains(rel) ? null : rel;
                });
                split(mapped).forEach((rel, f) -> bySource.merge(rel, f, GraphBuild::concat));
            } catch (IOException | RuntimeException unreadable) {
                Log.debug("graph: class facts unreadable at " + c.index(), unreadable);
            }
        }
        return classes;
    }

    /**
     * {@code f} cut into one fragment per source file: a node and an edge by the file they name, a
     * mention by the file of the node it comes from.
     */
    static Map<String, Fragment> split(Fragment f) {
        Map<String, String> fileOf = new HashMap<>();
        Map<String, List<GraphNode>> nodes = new LinkedHashMap<>();
        Map<String, List<GraphEdge>> edges = new HashMap<>();
        Map<String, List<Mention>> mentions = new HashMap<>();
        for (GraphNode n : f.nodes()) {
            fileOf.put(n.id(), n.sourceFile());
            nodes.computeIfAbsent(n.sourceFile(), k -> new ArrayList<>()).add(n);
        }
        for (GraphEdge e : f.edges()) {
            edges.computeIfAbsent(e.sourceFile(), k -> new ArrayList<>()).add(e);
        }
        for (Mention m : f.mentions()) {
            String file = fileOf.get(m.source());
            if (file == null) {
                Log.debug("graph: a compiled mention from " + m.source() + " names no file; left out");
                continue;
            }
            mentions.computeIfAbsent(file, k -> new ArrayList<>()).add(m);
        }
        Set<String> files = new TreeSet<>(nodes.keySet());
        files.addAll(edges.keySet());
        files.addAll(mentions.keySet());
        Map<String, Fragment> out = new LinkedHashMap<>();
        for (String file : files) {
            out.put(
                    file,
                    new Fragment(
                            nodes.getOrDefault(file, List.of()),
                            edges.getOrDefault(file, List.of()),
                            mentions.getOrDefault(file, List.of())));
        }
        return out;
    }

    private static Fragment concat(Fragment a, Fragment b) {
        List<GraphNode> nodes = new ArrayList<>(a.nodes());
        nodes.addAll(b.nodes());
        List<GraphEdge> edges = new ArrayList<>(a.edges());
        edges.addAll(b.edges());
        List<Mention> mentions = new ArrayList<>(a.mentions());
        mentions.addAll(b.mentions());
        return new Fragment(nodes, edges, mentions);
    }

    private static int classes(List<ClassIndex> indexes) {
        return indexes.stream().mapToInt(ClassIndex::classes).sum();
    }

    /** The class indexes and the module table a graph was built from: either changing rebuilds it. */
    private static String classDigest(List<ClassIndex> indexes, Modules modules) {
        List<String> parts = new ArrayList<>();
        for (ClassIndex c : indexes)
            parts.add(c.modulePrefix() + "\t" + c.index().getFileName() + "\t" + c.digest() + "\t"
                    + String.join(",", new TreeSet<>(c.stale())));
        for (Modules.Module m : modules.list()) parts.add("module\t" + m.prefix() + "\t" + m.compact());
        parts.sort(null);
        return Hashing.sha256Hex(String.join("\n", parts));
    }

    /**
     * The source a class came from: under the module, in its package directory. A Java source must
     * sit there; a Kotlin or Groovy one may sit anywhere, so the lone source of that name under the
     * module answers for it — never a Java file of another package, whose facts it would take on.
     */
    static @Nullable String find(Map<String, List<String>> byName, String prefix, String pkg, String file) {
        List<String> candidates = byName.getOrDefault(file, List.of()).stream()
                .filter(r -> r.startsWith(prefix))
                .toList();
        String suffix = pkg.isEmpty() ? "/" + file : "/" + pkg + "/" + file;
        for (String c : candidates) {
            if (("/" + c).endsWith(suffix)) return c;
        }
        if (file.endsWith(".java")) return null;
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }
}
