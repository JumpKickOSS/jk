// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@code jk-graph} worker: one graph operation per run, so the graph's memory is the worker's
 * and never the engine's. Served, it keeps the last workspace's graph between runs ({@link
 * GraphMemory}). Reads the operation from the spec's config, streams {@code progress} events while
 * it refreshes, and ends with one {@code answer} (or {@code error}) event.
 */
public final class GraphWorker implements Plugin {

    /** The protocol-line marker this worker writes. */
    public static final String PREFIX = "##JKGRAPH:";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-graph", PREFIX);
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        if (args.isEmpty()) {
            System.err.println("jk-graph: expected spec file path");
            return Exit.USAGE;
        }
        PluginConfig c = PluginSpec.read(Path.of(args.get(0))).config();
        try {
            GraphService.Answer answer = GraphService.run(workspace(c), request(c), new Progress(out));
            GraphBuild.Result b = answer.build();
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("t", "answer");
            event.put("text", answer.text());
            event.put("nodes", b.graph().size());
            event.put("edges", b.graph().edges().size());
            event.put("communities", b.graph().communities().size());
            event.put("files", b.files());
            event.put("extracted", b.extracted());
            event.put("classes", b.classes());
            event.put("millis", b.millis());
            event.put("dir", b.dir().toString());
            if (answer.note() != null) event.put("note", answer.note());
            out.emit(MiniJson.write(event));
            return 0;
        } catch (IllegalArgumentException refused) {
            out.emit(MiniJson.write(Map.of("t", "error", "msg", Errors.text(refused))));
            return Exit.USAGE;
        }
    }

    static GraphService.Request request(PluginConfig c) {
        return new GraphService.Request(
                c.string("op"),
                c.stringList("args"),
                c.stringOpt("mode").orElse("bfs"),
                (int) c.intValue("depth", 3),
                (int) c.intValue("budget", 2000),
                c.stringList("contexts"),
                c.bool("all", false),
                c.bool("undirected", false),
                (int) c.intValue("maxHops", 8),
                (int) c.intValue("topN", 10),
                c.stringOpt("relation").orElse(null),
                c.bool("rebuild", false),
                c.bool("noRefresh", false),
                c.stringOpt("scope").orElse(null),
                c.stringList("modulePrefixes"),
                c.stringOpt("sourceSet").orElse(null),
                options(c.stringList("options")));
    }

    /** {@code key=value} settings as a map; a later key wins. */
    static Map<String, String> options(List<String> rows) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String row : rows) {
            int eq = row.indexOf('=');
            if (eq > 0) out.put(row.substring(0, eq), row.substring(eq + 1));
        }
        return out;
    }

    /**
     * {@code index \t modulePrefix \t digest \t classes \t stale}, one per class set; {@code stale}
     * is the workspace-relative sources edited since the build, joined by {@code U+001F}.
     */
    static List<GraphBuild.ClassIndex> indexes(PluginConfig c) {
        List<GraphBuild.ClassIndex> out = new ArrayList<>();
        for (String row : c.stringList("classIndexes")) {
            String[] f = row.split("\t", -1);
            if (f.length != 5) continue;
            Set<String> stale = f[4].isEmpty() ? Set.of() : Set.of(f[4].split("\u001f"));
            out.add(new GraphBuild.ClassIndex(Path.of(f[0]), f[1], f[2], Integer.parseInt(f[3]), stale));
        }
        return out;
    }

    static GraphService.Workspace workspace(PluginConfig c) {
        return new GraphService.Workspace(
                Path.of(c.string("root")),
                Path.of(c.string("out")),
                indexes(c),
                modules(c),
                new ScriptReader(
                        c.stringOpt("node").map(Path::of).orElse(null),
                        c.stringOpt("oxc").map(Path::of).orElse(null)),
                c.bool("graphify", false),
                aliases(c.stringList("aliases")),
                c.stringList("rules"),
                LibraryApi.jars(c.stringList("libraries")),
                c.stringOpt("apiCache").map(Path::of).orElse(null));
    }

    /** {@code term \t word \t word …}, one per term. */
    static Map<String, List<String>> aliases(List<String> rows) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String row : rows) {
            String[] f = row.split("\t", -1);
            if (f.length > 1) out.put(f[0], List.of(f).subList(1, f.length));
        }
        return out;
    }

    /** {@code prefix \t compact}, one per module. */
    static Modules modules(PluginConfig c) {
        List<Modules.Module> out = new ArrayList<>();
        for (String row : c.stringList("modules")) {
            String[] f = row.split("\t", -1);
            if (f.length == 2) out.add(new Modules.Module(f[0], Boolean.parseBoolean(f[1])));
        }
        return new Modules(out);
    }

    /** About one progress event per percent of the files. */
    private static final class Progress implements GraphBuild.Progress {
        private final ProtocolWriter out;
        private final AtomicInteger done = new AtomicInteger();
        private volatile int total;
        private volatile int step = 1;
        private volatile String label = "read files";

        Progress(ProtocolWriter out) {
            this.out = out;
        }

        @Override
        public void planned(int files, int stale) {
            total = files;
            step = Math.max(1, files / 100);
            send(0);
        }

        @Override
        public void file(String rel, boolean extracted) {
            int d = done.incrementAndGet();
            if (d % step == 0 || d == total) send(d);
        }

        @Override
        public void phase(String text) {
            label = text;
            send(done.get());
        }

        private void send(int d) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("t", "progress");
            event.put("done", d);
            event.put("total", total);
            event.put("label", label);
            out.emit(MiniJson.write(event));
        }
    }
}
