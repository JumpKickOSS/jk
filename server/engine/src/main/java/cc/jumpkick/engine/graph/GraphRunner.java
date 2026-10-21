// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.engine.plugin.HeapLadder;
import cc.jumpkick.engine.plugin.HeapNotes;
import cc.jumpkick.engine.plugin.HeapScope;
import cc.jumpkick.engine.plugin.LearnedHeaps;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerFate;
import cc.jumpkick.engine.plugin.WorkerLeases;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.runtime.base.PluginLaunch;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphProgress;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Runs one {@code jk graph} operation: brings every module's main and test facts index current
 * (only changed class files are read), then forks the graph worker with their paths. Operations on
 * one workspace run one at a time, since each may rewrite the stored graph.
 */
public final class GraphRunner {

    private GraphRunner() {}

    /** The marker the graph worker writes its protocol lines under. */
    static final String PREFIX = "##JKGRAPH:";

    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    /** The workspace a directory belongs to: its workspace root, else the directory. */
    public static Path rootOf(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        try {
            return WorkspaceLocator.findRoot(abs).orElse(abs);
        } catch (IOException e) {
            return abs;
        }
    }

    /** Where a workspace's graph lives. */
    public static Path outputDir(Path root) {
        return BuildLayout.moduleTargetDir(root).resolve("graph");
    }

    public static GraphAck run(GraphRequest req, Consumer<GraphProgress> progress)
            throws IOException, InterruptedException {
        Path root = rootOf(Path.of(req.dir()));
        ReentrantLock lock = LOCKS.computeIfAbsent(root, k -> new ReentrantLock());
        lock.lock();
        try {
            List<String> indexes = req.noRefresh() ? List.of() : classIndexes(root);
            GraphScripts.Tools scripts = req.noRefresh() ? null : GraphScripts.of(root);
            Path spec = spec(root, req, indexes, scripts);
            try {
                return forkWithHeap(root, spec, progress);
            } finally {
                Files.deleteIfExists(spec);
            }
        } finally {
            lock.unlock();
        }
    }

    /** One attempt's answer, exit code and the tail of what the worker printed besides the protocol. */
    private record Attempt(@Nullable GraphAck ack, int exit, String tail) {}

    /**
     * Forks at this workspace's learned heap; a worker that runs out of heap is forked again up the
     * {@link HeapLadder}, and the heap that fit is remembered for next time.
     */
    private static GraphAck forkWithHeap(Path root, Path spec, Consumer<GraphProgress> progress)
            throws IOException, InterruptedException {
        HeapScope.Key key = new HeapScope.Key(
                root, "graph", HeapScope.PLUGIN, Runtime.version().feature());
        LearnedHeaps heaps = LearnedHeaps.engine();
        Path jar = PluginJar.GRAPH.locate(JkStores.storeCas());
        List<String> command = scoped(key, () -> PluginLaunch.javaCommand(jar, List.of(), spec));
        List<Long> ranOut = new ArrayList<>();
        while (true) {
            List<String> attemptCommand = command;
            Attempt a = scoped(key, () -> fork(attemptCommand, progress));
            if (a.ack() != null) {
                if (!ranOut.isEmpty()) heaps.good(key, WorkerLeases.parseXmx(attemptCommand));
                return a.ack();
            }
            long heap = WorkerLeases.parseXmx(attemptCommand);
            if (!WorkerFate.heapExhausted(a.exit(), a.tail()) || heap <= 0) {
                return GraphAck.error("graph worker exited " + a.exit() + " without an answer"
                        + (a.tail().isEmpty() ? "" : ": " + a.tail()));
            }
            heaps.note(key, heap);
            ranOut.add(heap);
            Long bigger = HeapLadder.next(ranOut);
            if (bigger == null) {
                return GraphAck.error(
                        "graph worker " + HeapLadder.ranOut(ranOut) + "; raise it with [jvm] args = [\"-Xmx...\"]");
            }
            HeapNotes.note(HeapNotes.line(bigger, heap, false));
            command = WorkerLeases.rewriteHeap(attemptCommand, bigger);
        }
    }

    private static <T> T scoped(HeapScope.Key key, Callable<T> body) throws IOException, InterruptedException {
        try {
            return HeapScope.call(key, body);
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static Attempt fork(List<String> command, Consumer<GraphProgress> progress)
            throws IOException, InterruptedException {
        AtomicReference<GraphAck> answer = new AtomicReference<>();
        StringBuilder tail = new StringBuilder();
        int exit = new PluginClient(PREFIX)
                .on(
                        "progress",
                        json -> progress.accept(new GraphProgress(
                                Jsonl.intValue(json, "done", 0),
                                Jsonl.intValue(json, "total", 0),
                                String.valueOf(Jsonl.str(json, "label")))))
                .on("answer", json -> answer.set(ack(json)))
                .on("error", json -> answer.set(GraphAck.error(String.valueOf(Jsonl.str(json, "msg")))))
                .passthrough(line -> {
                    synchronized (tail) {
                        tail.append(line).append('\n');
                        if (tail.length() > 8_192) tail.delete(0, tail.length() - 8_192);
                    }
                })
                .run(command);
        synchronized (tail) {
            return new Attempt(answer.get(), exit, tail.toString().strip());
        }
    }

    private static GraphAck ack(String json) {
        String text = Jsonl.str(json, "text");
        String dir = Jsonl.str(json, "dir");
        return new GraphAck(
                null,
                text == null ? "" : text,
                Jsonl.intValue(json, "nodes", 0),
                Jsonl.intValue(json, "edges", 0),
                Jsonl.intValue(json, "communities", 0),
                Jsonl.intValue(json, "files", 0),
                Jsonl.intValue(json, "extracted", 0),
                Jsonl.intValue(json, "classes", 0),
                Jsonl.longValue(json, "millis", 0),
                dir == null ? "" : dir);
    }

    private static Path spec(Path root, GraphRequest r, List<String> indexes, GraphScripts.@Nullable Tools scripts)
            throws IOException {
        SpecWriter w = new SpecWriter()
                .op(PluginProtocol.OP_COMMAND, "graph", "jk-graph")
                .configString("root", root.toString())
                .configString("out", outputDir(root).toString())
                .configString("op", Objects.requireNonNull(r.op()))
                .configList("args", r.args())
                .configString("mode", r.mode() == null ? "bfs" : r.mode())
                .configInt("depth", r.depth())
                .configInt("budget", r.budget())
                .configList("contexts", r.contexts())
                .configBool("all", r.all())
                .configBool("undirected", r.undirected())
                .configInt("maxHops", r.maxHops())
                .configInt("topN", r.topN())
                .configString("relation", r.relation())
                .configBool("rebuild", r.rebuild())
                .configBool("noRefresh", r.noRefresh())
                .configList("classIndexes", indexes);
        if (scripts != null) {
            w.configString("node", scripts.node().toString())
                    .configString("oxc", scripts.oxc().toString());
        }
        return w.writeTempSpec();
    }

    /**
     * {@code index \t modulePrefix \t digest \t classes} for every module's main and test classes,
     * each index brought up to date first. A module that has not compiled has none.
     */
    static List<String> classIndexes(Path root) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Path, JkBuild> module : modules(root).entrySet()) {
            BuildLayout layout = BuildLayout.of(root, module.getKey(), module.getValue());
            String rel = root.relativize(module.getKey()).toString().replace('\\', '/');
            String prefix = rel.isEmpty() ? "" : rel + "/";
            for (String set : List.of("main", "test")) {
                Path classes = set.equals("main") ? layout.classesDir() : layout.testClassesDir();
                if (!Files.isDirectory(classes)) continue;
                try {
                    FactsIndexing.Ensured e =
                            FactsIndexing.ensure(classes, FactsIndexing.indexPath(layout.buildDir(), set));
                    out.add(e.index() + "\t" + prefix + "\t" + e.bodyDigest() + "\t" + e.classes());
                } catch (IOException | RuntimeException unreadable) {
                    Log.debug("graph: no facts index for " + classes, unreadable);
                }
            }
        }
        return out;
    }

    /** The workspace's modules by directory, the root first; a tree without a manifest has none. */
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
}
