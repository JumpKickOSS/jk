// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Communities;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphJson;
import cc.jumpkick.codegraph.GraphReport;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.extract.Extractor;
import cc.jumpkick.codegraph.extract.Extractors;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * One refresh of a workspace's graph. Walks the tree once, reuses every fragment whose file is
 * unchanged, extracts the rest in parallel, brings each module's class facts up to date (only
 * changed class files are read), links, clusters, and writes {@code graph.json}, {@code
 * GRAPH_REPORT.md} and the fragment store under {@code target/graph/}.
 */
public final class GraphBuild {

    private GraphBuild() {}

    /** The directory under a workspace root the graph is written to. */
    public static Path dir(Path root) {
        return BuildLayout.moduleTargetDir(root).resolve("graph");
    }

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

    /** What one refresh did. */
    public record Result(CodeGraph graph, int files, int extracted, int classes, Path dir, long millis) {}

    public static Result refresh(Path root, Progress progress) throws IOException {
        return refresh(root, progress, Clock.SYSTEM);
    }

    static Result refresh(Path root, Progress progress, Clock clock) throws IOException {
        long start = clock.nanos();
        Path base = root.toAbsolutePath().normalize();
        Path out = dir(base);
        List<GraphSources.Source> sources = GraphSources.walk(base);
        FragmentStore store = FragmentStore.open(out);
        long now = clock.millis();
        Map<String, Fragment> fragments = new HashMap<>();
        List<GraphSources.Source> stale = new ArrayList<>();
        for (GraphSources.Source s : sources) {
            Fragment f = store.byStat(s, now);
            if (f != null) fragments.put(s.rel(), f);
            else stale.add(s);
        }
        progress.planned(sources.size(), stale.size());
        for (GraphSources.Source s : sources) {
            if (fragments.containsKey(s.rel())) progress.file(s.rel(), false);
        }
        AtomicInteger extracted = new AtomicInteger();
        Map<String, Fragment> fresh = new ConcurrentHashMap<>();
        stale.parallelStream().forEach(s -> {
            Extracted x = extract(s, store);
            if (x.read()) extracted.incrementAndGet();
            if (!x.fragment().nodes().isEmpty()) fresh.put(s.rel(), x.fragment());
            progress.file(s.rel(), x.read());
        });
        fragments.putAll(fresh);
        progress.phase("map compiled classes");
        List<Fragment> all = new ArrayList<>();
        for (GraphSources.Source s : sources) {
            Fragment f = fragments.get(s.rel());
            if (f != null) all.add(f);
        }
        int classes = classFragments(base, sources, all);
        progress.phase("link and cluster");
        CodeGraph graph = Linker.link(all);
        Communities.assign(graph);
        String commit = GitHead.read(base);
        Files.createDirectories(out);
        AtomicWrites.replace(out.resolve("graph.json"), GraphJson.write(graph, "jk " + JkVersion.VERSION, commit));
        AtomicWrites.replace(out.resolve("GRAPH_REPORT.md"), GraphReport.write(graph, commit));
        store.save(sources);
        return new Result(graph, sources.size(), extracted.get(), classes, out, (clock.nanos() - start) / 1_000_000);
    }

    /** A stale file's fragment; {@code read} false when its bytes matched the stored hash after all. */
    private record Extracted(Fragment fragment, boolean read) {}

    /** Hashes one file and extracts it unless its bytes are unchanged; an unreadable file yields nothing. */
    private static Extracted extract(GraphSources.Source s, FragmentStore store) {
        Extractor e = Extractors.forPath(s.rel());
        if (e == null) return new Extracted(Fragment.EMPTY, false);
        try {
            byte[] bytes = Files.readAllBytes(s.path());
            String sha = Hashing.sha256Hex(bytes);
            Fragment had = store.bySha(s.rel(), sha);
            Fragment f = had != null ? had : e.extract(s.rel(), new String(bytes, StandardCharsets.UTF_8));
            store.put(s, sha, f);
            return new Extracted(f, had == null);
        } catch (IOException | RuntimeException failed) {
            Log.debug("graph: cannot extract " + s.rel(), failed);
            return new Extracted(Fragment.EMPTY, false);
        }
    }

    /**
     * Appends each module's main and test class facts to {@code all}; returns the class count. A
     * module without compiled classes contributes nothing: its source scan still stands.
     */
    private static int classFragments(Path root, List<GraphSources.Source> sources, List<Fragment> all) {
        Map<String, List<String>> byName = new HashMap<>();
        for (GraphSources.Source s : sources) {
            if (Extractors.isJvmSource(s.rel())) {
                byName.computeIfAbsent(s.rel().substring(s.rel().lastIndexOf('/') + 1), k -> new ArrayList<>())
                        .add(s.rel());
            }
        }
        int classes = 0;
        for (Map.Entry<Path, JkBuild> module : modules(root).entrySet()) {
            BuildLayout layout = BuildLayout.of(root, module.getKey(), module.getValue());
            String prefix = prefix(root, module.getKey());
            JvmFacts.SourceLookup lookup = (pkg, file) -> find(byName, prefix, pkg, file);
            for (String set : List.of("main", "test")) {
                Path classesDir = set.equals("main") ? layout.classesDir() : layout.testClassesDir();
                if (!Files.isDirectory(classesDir)) continue;
                try {
                    FactsIndexing.Ensured ensured =
                            FactsIndexing.ensure(classesDir, FactsIndexing.indexPath(layout.buildDir(), set));
                    FactsIndex index = FactsIndexing.load(ensured);
                    classes += index.classes().size();
                    all.add(JvmFacts.map(index, lookup));
                } catch (IOException | RuntimeException unreadable) {
                    Log.debug("graph: class facts unavailable for " + classesDir, unreadable);
                }
            }
        }
        return classes;
    }

    /** The workspace's modules by directory, the root itself first; a tree without a manifest has none. */
    static Map<Path, JkBuild> modules(Path root) {
        Map<Path, JkBuild> out = new LinkedHashMap<>();
        try {
            Path manifest = ManifestPaths.manifestIn(root);
            if (!Files.isRegularFile(manifest)) return out;
            JkBuild build = JkBuildParser.parse(manifest);
            out.put(root, build);
            for (Map.Entry<Path, JkBuild> m :
                    WorkspaceLoader.loadModules(root, build).entrySet()) {
                out.putIfAbsent(m.getKey().toAbsolutePath().normalize(), m.getValue());
            }
        } catch (IOException | RuntimeException unparsable) {
            Log.debug("graph: no module list for " + root, unparsable);
        }
        return out;
    }

    private static String prefix(Path root, Path module) {
        String rel =
                root.relativize(module.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return rel.isEmpty() ? "" : rel + "/";
    }

    /** The source a class came from: under the module, in its package directory if one matches. */
    static @Nullable String find(Map<String, List<String>> byName, String prefix, String pkg, String file) {
        List<String> candidates = byName.getOrDefault(file, List.of()).stream()
                .filter(r -> r.startsWith(prefix))
                .toList();
        String suffix = pkg.isEmpty() ? "/" + file : "/" + pkg + "/" + file;
        for (String c : candidates) {
            if (("/" + c).endsWith(suffix)) return c;
        }
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }
}
