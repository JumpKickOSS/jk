// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.cache.GraphCacheKeys;
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
import cc.jumpkick.host.PathUtil;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * One refresh of a workspace's graph, in the graph worker. Walks the tree once and compares every
 * file's stat with the last build's; when nothing moved and the class indexes are the ones the last
 * graph was built from, the stored snapshot is the answer. Otherwise every changed file is hashed
 * and, unless the action cache holds its declarations ({@link FragmentCache}), extracted again (in
 * parallel); each class set's compiled facts come from the cache under its compile's keys, or are
 * mapped from its facts index and cached; and the graph is linked and written to this checkout's
 * {@code graph.bin} and {@code files.tsv} under {@code target/graph/}. Compiled facts come only
 * from this checkout's own classes: a file whose classes it has not built has none.
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
     * One module's class set: its facts index ({@code null} when the engine left it unindexed because
     * the cache holds the set's facts), the module's path prefix, its source set ({@code main} or
     * {@code test}), the digest a graph is linked from and the class count, the workspace-relative
     * sources edited since those classes were built, whose own classes are skipped (their source scan
     * answers for them), and the action keys of the compiles whose output the set is (none when no jk
     * compile stamped it).
     */
    public record ClassIndex(
            @Nullable Path index,
            String modulePrefix,
            String sourceSet,
            String digest,
            int classes,
            Set<String> stale,
            List<String> compileKeys) {
        public ClassIndex {
            stale = Set.copyOf(stale);
            compileKeys = List.copyOf(compileKeys);
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

    /** A class set the engine left unindexed for its cached facts is no longer in the cache. */
    static final class FactsGone extends IllegalStateException {
        FactsGone() {
            super(GraphCacheKeys.GONE);
        }
    }

    /**
     * Refreshes {@code root}'s graph into {@code out}; {@code cache} is the cache root whose action
     * index holds the fragments, {@code null} for none.
     */
    public static Result refresh(
            Path root,
            Path out,
            @Nullable Path cache,
            List<ClassIndex> indexes,
            Modules modules,
            ScriptReader scripts,
            boolean rebuild,
            Progress progress)
            throws IOException {
        return refresh(root, out, cache, indexes, modules, scripts, rebuild, progress, Clock.SYSTEM);
    }

    static Result refresh(
            Path root,
            Path out,
            @Nullable Path cache,
            List<ClassIndex> indexes,
            Modules modules,
            ScriptReader scripts,
            boolean rebuild,
            Progress progress,
            Clock clock)
            throws IOException {
        long start = clock.nanos();
        Path base = root.toAbsolutePath().normalize();
        dropTreeStore(base);
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
        String extractors = GraphCacheKeys.VERSION + (scripts.active() ? "+oxc" : "");
        String inputs = inputs(shas);
        GraphSnapshot.Key key = new GraphSnapshot.Key(extractors, sources.size(), classDigest, inputs, commit);
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
        FragmentCache fragmentCache = GraphMemory.fragments(out, cache);
        List<String> unread = new ArrayList<>();
        for (GraphSources.Source s : sources) {
            String sha = shas.get(s.rel());
            if (sha != null
                    && ScriptReader.handles(s.rel())
                    && (rebuild
                            || fragmentCache.declarations(
                                            GraphCacheKeys.declarations(s.rel(), sha, scripts.flavor(s.rel())), s.rel())
                                    == null)) {
                unread.add(s.rel());
            }
        }
        if (scripts.active() && !unread.isEmpty()) progress.phase("read scripts with oxc");
        Map<String, Fragment> oxc = scripts.read(base, unread);
        AtomicInteger extracted = new AtomicInteger();
        Map<String, Fragment> fragments = new ConcurrentHashMap<>();
        Set<String> declarationKeys = ConcurrentHashMap.newKeySet();
        sources.parallelStream().forEach(s -> {
            String sha = shas.get(s.rel());
            if (sha == null) return;
            String flavor = scripts.flavor(s.rel());
            String k = GraphCacheKeys.declarations(s.rel(), sha, flavor);
            Fragment stored = rebuild ? null : fragmentCache.declarations(k, s.rel());
            Fragment read = oxc.get(s.rel());
            Fragment f = stored != null ? stored : read != null ? read : extract(s, changed.get(s.rel()));
            if (stored == null) {
                if (read == null && !flavor.isEmpty()) {
                    flavor = "";
                    k = GraphCacheKeys.declarations(s.rel(), sha, flavor);
                }
                fragmentCache.putDeclarations(k, s.rel(), sha, flavor, f);
                extracted.incrementAndGet();
            }
            declarationKeys.add(k);
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
        Set<String> classKeys = new HashSet<>();
        mapClasses(sources, indexes, modules, fragmentCache, rebuild, classKeys, built);
        for (GraphSources.Source s : sources) {
            Fragment compiled = built.get(s.rel());
            if (compiled != null && shas.containsKey(s.rel())) all.add(compiled);
        }
        fragmentCache.retain(declarationKeys, classKeys);
        progress.phase("link");
        CodeGraph graph = Linker.link(all);
        graph.modules(modules);
        graph.stale(stale(base, graph.stale()));
        progress.phase("write graph");
        write(out, graph, key);
        GraphMemory.sawGraph(out, graph, key);
        stats.save(out, sources);
        GraphMemory.savedStats(out, stats);
        long millis = (clock.nanos() - start) / 1_000_000;
        return new Result(graph, sources.size(), extracted.get(), classes(indexes), out, millis, true, commit);
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

    /**
     * {@code .jk/graph/} under {@code root}: a tree store of the graph jk does not write, removed when
     * a refresh finds one so the checkout holds nothing of the graph's to commit.
     */
    static void dropTreeStore(Path root) {
        Path dir = root.resolve(".jk").resolve("graph");
        if (!Files.isDirectory(dir)) return;
        try {
            PathUtil.deleteRecursivelyOrThrow(dir);
        } catch (IOException | RuntimeException failed) {
            Log.debug("graph: cannot remove " + dir, failed);
        }
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
     * Puts each class set's compiled facts into {@code bySource}, one fragment per source file the
     * classes came from, leaving out the sources edited since the build. A set a compile stamped is
     * read from the cache under its compile keys, or mapped from its facts index and cached there
     * (unless {@code rebuild}, which maps every set again); its cache key goes into {@code used}. A
     * class maps only to a source of its own module and source set, the sources its compile keys
     * cover, so a cached mapping holds wherever the key does.
     *
     * @throws FactsGone when a set the engine left unindexed is no longer in the cache
     */
    private static void mapClasses(
            List<GraphSources.Source> sources,
            List<ClassIndex> indexes,
            Modules modules,
            FragmentCache cache,
            boolean rebuild,
            Set<String> used,
            Map<String, Fragment> bySource) {
        Map<String, List<String>> byName = new HashMap<>();
        for (GraphSources.Source s : sources) {
            if (Extractors.isJvmSource(s.rel())) {
                byName.computeIfAbsent(s.rel().substring(s.rel().lastIndexOf('/') + 1), k -> new ArrayList<>())
                        .add(s.rel());
            }
        }
        for (ClassIndex c : indexes) {
            String key = FragmentCache.classesKey(c);
            Map<String, Fragment> byFile = key == null || rebuild ? null : cache.classes(key);
            if (byFile == null) {
                Path index = c.index();
                if (index == null) throw new FactsGone();
                Predicate<String> own = own(modules, c.modulePrefix(), c.sourceSet());
                try {
                    var facts = FactsFormat.read(index);
                    byFile = split(JvmFacts.map(facts, (pkg, file) -> find(byName, own, pkg, file)));
                } catch (IOException | RuntimeException unreadable) {
                    Log.debug("graph: class facts unreadable at " + index, unreadable);
                    continue;
                }
                if (key != null) cache.putClasses(key, c, byFile);
            }
            if (key != null) used.add(key);
            byFile.forEach((rel, f) -> {
                if (!c.stale().contains(rel)) bySource.merge(rel, f, GraphBuild::concat);
            });
        }
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
            parts.add(c.modulePrefix() + "\t" + c.sourceSet() + "\t" + c.digest() + "\t"
                    + String.join(",", new TreeSet<>(c.stale())));
        for (Modules.Module m : modules.list()) parts.add("module\t" + m.prefix() + "\t" + m.compact());
        parts.sort(null);
        return Hashing.sha256Hex(String.join("\n", parts));
    }

    /** Whether a workspace-relative source is one of module {@code prefix}'s {@code sourceSet} sources. */
    static Predicate<String> own(Modules modules, String prefix, String sourceSet) {
        Modules.SourceSet set = "test".equals(sourceSet) ? Modules.SourceSet.TEST : Modules.SourceSet.MAIN;
        return rel -> {
            Modules.Module m = modules.of(rel);
            return (m == null ? "" : m.prefix()).equals(prefix) && modules.sourceSet(rel) == set;
        };
    }

    /**
     * The source a class came from, among those {@code own} accepts (its module's, of its source
     * set): in its package directory. A Java source must sit there; a Kotlin or Groovy one may sit
     * anywhere, so the lone such source of that name answers for it — never a Java file of another
     * package, whose facts it would take on.
     */
    static @Nullable String find(Map<String, List<String>> byName, Predicate<String> own, String pkg, String file) {
        List<String> candidates =
                byName.getOrDefault(file, List.of()).stream().filter(own).toList();
        String suffix = pkg.isEmpty() ? "/" + file : "/" + pkg + "/" + file;
        for (String c : candidates) {
            if (("/" + c).endsWith(suffix)) return c;
        }
        if (file.endsWith(".java")) return null;
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }
}
