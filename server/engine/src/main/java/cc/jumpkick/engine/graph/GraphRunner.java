// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.builds.GraphRefreshSlot;
import cc.jumpkick.cache.GraphCacheKeys;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.GraphConfig;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleSelection;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.engine.plugin.HeapFlags;
import cc.jumpkick.engine.plugin.HeapLadder;
import cc.jumpkick.engine.plugin.HeapNotes;
import cc.jumpkick.engine.plugin.HeapScope;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.LearnedHeaps;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WarmWorkers;
import cc.jumpkick.engine.plugin.WorkerAotCache;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.engine.plugin.WorkerFate;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.GraphLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.runtime.ModuleInputProvenance;
import cc.jumpkick.runtime.PlannerTest;
import cc.jumpkick.runtime.base.PluginLaunch;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphProgress;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Runs one {@code jk graph} operation: brings every module's main, test and guard facts index current
 * (only changed class files are read), then hands the graph worker their paths. The worker stays
 * warm between operations ({@link WarmWorkers}) with the graph in its memory. Operations on
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

    /** Where this checkout keeps the linked graph and its file stats: build output, gone with {@code jk clean}. */
    public static Path outputDir(Path root) {
        return GraphLayout.dir(root);
    }

    /**
     * Whether this checkout has a graph, which builds then keep current: the first graph question
     * makes one, and {@code jk clean} is how a checkout stops until the next.
     */
    public static boolean hasGraph(Path root) {
        return GraphLayout.built(root);
    }

    /**
     * One graph operation. A question answered from compiled facts (callers, a rename, …) compiles
     * what is stale or never compiled first; building the graph ({@code jk graph build}, the first
     * graph {@code jk new} writes) reads the classes as they are.
     */
    public static GraphAck run(GraphRequest req, Consumer<GraphProgress> progress)
            throws IOException, InterruptedException {
        return run(req, progress, readsCompiledFacts(String.valueOf(req.op())));
    }

    /**
     * Whether operation {@code op} answers from compiled facts, so its classes must be the code's
     * as it stands: every question but {@code rules}, which answers from the guard rules alone.
     * {@code build} asks nothing.
     */
    static boolean readsCompiledFacts(String op) {
        return !"build".equals(op) && !"rules".equals(op);
    }

    /**
     * As {@link #run(GraphRequest, Consumer)}; {@code compile} false reads the classes as they
     * are, which is what building the graph and the refresh after a build want (see {@link
     * #refreshExisting}).
     */
    private static GraphAck run(GraphRequest req, Consumer<GraphProgress> progress, boolean compile)
            throws IOException, InterruptedException {
        Path root = rootOf(Path.of(req.dir()));
        ReentrantLock lock = LOCKS.computeIfAbsent(root, k -> new ReentrantLock());
        lock.lock();
        try {
            List<String> prefixes;
            try {
                prefixes = modulePrefixes(root, req.modules());
            } catch (IOException | RuntimeException refused) {
                return GraphAck.error(String.valueOf(refused.getMessage()));
            }
            Compiled compiled = req.noRefresh() || !compile ? Compiled.NOTHING : compileStale(root);
            Path cache = JkDirs.cache();
            // A rename reads every class set's index, and -r maps every set again.
            boolean index = "rename".equals(req.op()) || req.rebuild();
            ClassIndexes indexes = req.noRefresh() ? ClassIndexes.NONE : classIndexes(root, cache, index);
            List<String> modules = req.noRefresh() ? List.of() : moduleRows(root);
            GraphScripts.Tools scripts = req.noRefresh() ? null : GraphScripts.of(root);
            GraphAck ack = ask(root, req, cache, indexes, modules, prefixes, scripts, progress);
            if (!ack.ok() && GraphCacheKeys.GONE.equals(ack.error()) && !index) {
                indexes = classIndexes(root, cache, true);
                ack = ask(root, req, cache, indexes, modules, prefixes, scripts, progress);
            }
            ack = withNote(ack, indexes.leftOut(), indexes.edited());
            String skipped = compiled.line();
            if (skipped != null) ack = withLine(ack, skipped);
            // The worker names the sets the answer may be missing calls from: a rename, only those
            // of the modules that can reach the method.
            String uncompiled = compiled.uncompiledLine(ack.uncompiled());
            return uncompiled == null ? ack : withLine(ack, uncompiled);
        } finally {
            lock.unlock();
        }
    }

    /** One answer from the graph worker over {@code indexes}. */
    private static GraphAck ask(
            Path root,
            GraphRequest req,
            Path cache,
            ClassIndexes indexes,
            List<String> modules,
            List<String> prefixes,
            GraphScripts.@Nullable Tools scripts,
            Consumer<GraphProgress> progress)
            throws IOException, InterruptedException {
        Path spec = spec(root, req, cache, indexes, modules, prefixes, scripts);
        try {
            return forkWithHeap(root, spec, progress);
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    /**
     * Brings stale classes up to their sources before a question reads them: what a graph question
     * runs when a module's main sources moved since its classes, or it has none, and, with {@code
     * tests}, when a module's tests never compiled. {@code null} means nothing to do or done;
     * otherwise one line, why not ({@code the code does not compile (…)}, a build running).
     */
    public interface Compiler {
        @Nullable
        String compile(Path root, List<String> modules, boolean tests) throws InterruptedException;
    }

    /** The engine's {@link GraphCompiles}; none in a process with no jobs to submit (a test, a trainer). */
    private static volatile @Nullable Compiler compiler;

    public static void compiler(@Nullable Compiler c) {
        compiler = c;
    }

    /**
     * What the compile step did: why it did not run ({@code null} when it ran or had nothing to do),
     * and whether a module it was for had classes from an earlier build to answer from.
     */
    record Compiled(@Nullable String skipped, boolean earlierClasses) {
        static final Compiled NOTHING = new Compiled(null, false);

        /** The line an answer from the last build's classes carries, else {@code null}. */
        @Nullable
        String line() {
            return skipped == null || !earlierClasses
                    ? null
                    : "compile skipped: " + skipped + "; answering from the last build's classes";
        }

        /**
         * The line naming {@code uncompiled}, the modules still without main classes and the test
         * sets without test classes, else {@code null}; it carries why the compile did not run
         * unless {@link #line} already does.
         */
        @Nullable
        String uncompiledLine(List<String> uncompiled) {
            if (uncompiled.isEmpty()) return null;
            String why = skipped == null || earlierClasses ? "" : "compile skipped: " + skipped + "; ";
            return why + unseen(uncompiled);
        }
    }

    /** What an answer says of {@code uncompiled}, the modules and test sets with no compiled classes. */
    public static String unseen(List<String> uncompiled) {
        boolean one = uncompiled.size() == 1;
        return String.join(", ", uncompiled) + (one ? " has" : " have")
                + " no compiled classes, so the calls made there are missing: search " + (one ? "it" : "them")
                + " for calls too";
    }

    /**
     * Compile the modules whose main sources were edited since their classes, or that have main
     * sources and were never compiled, so the calls and overrides an answer reads are the code's as
     * it stands; then compile the default test suite of the modules whose default suite never
     * compiled, in a job of their own, so test code that does not compile costs no main class. An
     * unchanged tree costs the check that finds nothing stale. An edited test is not compiled here:
     * it keeps the edited-files note. Another suite is named, not compiled ({@link #uncompiledSuites}).
     */
    static Compiled compileStale(Path root) throws InterruptedException {
        Compiler c = compiler;
        if (c == null) return Compiled.NOTHING;
        Map<Path, JkBuild> all = modules(root);
        List<String> stale = new ArrayList<>();
        List<String> tests = new ArrayList<>();
        boolean earlierClasses = false;
        for (Map.Entry<Path, JkBuild> module : all.entrySet()) {
            BuildLayout layout = BuildLayout.of(root, module.getKey(), module.getValue());
            String arg =
                    moduleArg(root, module.getKey(), module.getValue().project().name());
            if (uncompiledSuites(module.getKey(), layout).contains(TestSuites.DEFAULT)) {
                tests.add(arg);
            }
            if (!hasClasses(layout.classesDir())) {
                if (hasMainSources(module.getKey())) stale.add(arg);
                continue;
            }
            Set<String> edited = ModuleInputProvenance.staleSources(root, module.getKey(), module.getValue());
            boolean compact = ModuleLayout.isCompact(module.getKey());
            if (edited == null || edited.stream().anyMatch(rel -> mainSource(rel, compact))) {
                stale.add(arg);
                earlierClasses = true;
            }
        }
        // One module is the whole project: no filter, so its name need not match how -m spells it.
        boolean whole = all.size() == 1;
        if (!stale.isEmpty()) {
            String why = c.compile(root, whole ? List.of() : stale, false);
            if (why != null) return new Compiled(why, earlierClasses);
        }
        if (tests.isEmpty()) return Compiled.NOTHING;
        return new Compiled(c.compile(root, whole ? List.of() : tests, true), false);
    }

    /** Whether the module at {@code dir} has a JVM source among its main sources: one a compile makes classes of. */
    static boolean hasMainSources(Path dir) {
        boolean compact = ModuleLayout.isCompact(dir);
        Path src = compact ? dir.resolve("src") : dir.resolve("src").resolve("main");
        try {
            return PathUtil.anyRegularFile(src, d -> false, GraphRunner::jvmSource);
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("graph: cannot list the sources under " + src, unreadable);
            return false;
        }
    }

    /**
     * Whether a compile wrote a class into {@code classes}: a compile that failed leaves the
     * directory, empty, so the directory alone does not say the sources were ever compiled.
     */
    static boolean hasClasses(Path classes) {
        try {
            return PathUtil.anyRegularFile(
                    classes, d -> false, f -> f.getFileName().toString().endsWith(".class"));
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("graph: cannot list the classes under " + classes, unreadable);
            return false;
        }
    }

    /**
     * The test suites of the module at {@code dir} that have sources and no classes the graph reads:
     * each suite its test classes were not compiled for (a test compile compiles the default suite
     * only, {@code jk guard} the integration suite with it), and the guard suite until {@code jk
     * guard} compiles it into its own classes. Only the default suite is compiled for a question.
     */
    static List<String> uncompiledSuites(Path dir, BuildLayout layout) {
        try {
            boolean compact = ModuleLayout.isCompact(dir);
            Path testClasses = layout.testClassesDir();
            Set<String> compiled = hasClasses(testClasses) ? PlannerTest.compiledSuites(testClasses) : Set.of();
            List<String> out = new ArrayList<>();
            for (String suite : TestSuites.discover(dir, compact)) {
                if (!compiled.contains(suite)) out.add(suite);
            }
            if (TestSuites.hasGuardSuite(dir, compact) && !hasClasses(layout.guardClassesDir())) {
                out.add(TestSuites.GUARD);
            }
            return out;
        } catch (RuntimeException unreadable) {
            Log.debug("graph: cannot list the test sources under " + dir, unreadable);
            return List.of();
        }
    }

    /** How an answer names a module's test suite: the directory of its sources, so it can be searched. */
    static String testSet(String rel, boolean compact, String suite) {
        return (rel.isEmpty() ? "" : rel + "/") + (compact ? suite : "src/" + suite);
    }

    private static boolean jvmSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".groovy");
    }

    /**
     * How a compile names {@code dir}: by its path, since two modules may share a name
     * (plugins/quarkus, server/guard/packs/quarkus) and a name that matches both refuses the whole
     * compile. The root module has no path and goes by its name.
     */
    static String moduleArg(Path root, Path dir, String name) {
        String rel = root.relativize(dir).toString().replace('\\', '/');
        return rel.isEmpty() ? name : rel;
    }

    /** Whether a module-relative path is a main source, as the graph sorts them into source sets. */
    static boolean mainSource(String rel, boolean compact) {
        String r = rel.replace('\\', '/');
        return compact ? r.startsWith("src/") || r.startsWith("resources/") : r.startsWith("src/main/");
    }

    /** {@code ack} with {@code line} added to its note. */
    static GraphAck withLine(GraphAck ack, String line) {
        if (!ack.ok()) return ack;
        String note = ack.note() == null ? line : ack.note() + "\n" + line;
        return new GraphAck(
                null,
                ack.text(),
                ack.nodes(),
                ack.edges(),
                ack.files(),
                ack.extracted(),
                ack.classes(),
                ack.millis(),
                ack.dir(),
                note,
                ack.uncompiled());
    }

    /**
     * Refresh the workspace's graph when it has one, after a build that succeeded: the graph an
     * agent reads next is the tree the build just compiled, not the one before it. A workspace with no graph gets none: the first graph question makes
     * one. Best-effort: a refresh that fails is logged, and the next graph operation refreshes anyway.
     *
     * <p>It compiles nothing, even over sources that moved since the classes: a compile it submitted
     * would run on the engine's own settings, not the build's (the default cache rather than the
     * build's {@code --cache-dir}, none of its variant, offline or JVM settings), for modules that
     * build chose not to compile. The graph notes the edited files, and the next graph question
     * compiles them under its own request.
     */
    public static void refreshExisting(Path dir) {
        Path root = rootOf(dir);
        if (!hasGraph(root)) return;
        GraphRequest build = new GraphRequest(
                root.toString(),
                "build",
                List.of(),
                null,
                0,
                0,
                List.of(),
                false,
                0,
                null,
                false,
                false,
                null,
                null,
                null,
                List.of());
        try {
            GraphAck ack = run(build, progress -> {}, false);
            if (!ack.ok()) Log.warn("jk: the code graph was not refreshed after the build: " + ack.error());
        } catch (IOException | RuntimeException failed) {
            Log.warn("jk: the code graph was not refreshed after the build", failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Workspaces with a refresh waiting to start: a build that finishes meanwhile needs no second. */
    private static final Set<Path> PENDING = ConcurrentHashMap.newKeySet();

    /**
     * {@link #refreshExisting} on its own thread, so the build that asked is reported, and its
     * record written, without waiting for it: an agent's {@code run} answered the moment the graph
     * was current, and a refresh longer than the record's settle window made that answer "no run
     * record" for a build that passed. A graph question asked meanwhile takes the workspace's lock
     * after the refresh, so it still answers for the tree the build compiled.
     */
    public static void refreshAfterBuild(Path dir) {
        Path root = rootOf(dir);
        if (!hasGraph(root) || !PENDING.add(root)) return;
        // The refresh writes under target/ after the build returns: hold the workspace's refresh
        // slot from here, before the build's job finishes, so a jk clean that follows waits for it.
        holdSlot(root);
        SessionContext.startVirtual("jk-graph-refresh", () -> {
            PENDING.remove(root);
            try {
                refreshExisting(root);
            } finally {
                releaseSlot(root);
            }
        });
    }

    /** Refreshes in flight per workspace, and the refresh slot they share while any runs. */
    private static final Map<Path, SlotHold> SLOTS = new HashMap<>();

    private static final class SlotHold {
        int refreshes;
        FileLocks.@Nullable Hold hold;
    }

    /** One more refresh of {@code root} under way: the first takes the slot, the rest share it. */
    static synchronized void holdSlot(Path root) {
        SlotHold held = SLOTS.computeIfAbsent(root, k -> new SlotHold());
        if (held.refreshes++ == 0 && FileLocks.tryHold(GraphRefreshSlot.lockFile(root)) instanceof FileLocks.Hold h) {
            held.hold = h;
        }
    }

    /** A refresh of {@code root} is done: the last one out lets the slot go. */
    static synchronized void releaseSlot(Path root) {
        SlotHold held = SLOTS.get(root);
        if (held == null || --held.refreshes > 0) return;
        SLOTS.remove(root);
        if (held.hold != null) {
            try {
                held.hold.close();
            } catch (IOException ignored) {
                // the slot goes with the process at worst
            }
        }
    }

    /** What the lease ledger and {@code jk engine status} call the graph worker. */
    static final String WORKER_NAME = "graph worker";

    /** One attempt's answer, exit code and the tail of what the worker printed besides the protocol. */
    private record Attempt(@Nullable GraphAck ack, int exit, String tail) {}

    /**
     * Runs on a warm graph worker at this workspace's learned heap, mapping the worker's startup
     * cache when there is one. A worker that runs out of heap is started again up the {@link
     * HeapLadder}, and the heap that fit is remembered for next time.
     */
    private static GraphAck forkWithHeap(Path root, Path spec, Consumer<GraphProgress> progress)
            throws IOException, InterruptedException {
        HeapScope.Key key = new HeapScope.Key(
                root, "graph", HeapScope.PLUGIN, Runtime.version().feature());
        LearnedHeaps heaps = LearnedHeaps.engine();
        Path jar = PluginJar.GRAPH.locate(JkStores.storeCas());
        Path hostJava = JavaHomes.runningJavaHome();
        String workerCp = WorkerLaunchClasspath.resolve(jar);
        // Resident between questions: a collector that gives the heap back while it idles, and a
        // startup cache recorded under that same collector.
        List<String> aot = WorkerAotCache.flags(
                "graph",
                hostJava,
                workerCp,
                JvmOptions.RESIDENT_GC,
                (aotOutput, scratch) -> GraphTraining.command(hostJava, workerCp, aotOutput, scratch));
        List<String> command = JvmOptions.residentCollector(scoped(key, () -> PluginLaunch.serveCommand(jar, aot)));
        List<Long> ranOut = new ArrayList<>();
        while (true) {
            List<String> attemptCommand = command;
            Attempt a = attempt(
                    new WarmWorkers.Launch(WORKER_NAME, attemptCommand, WorkerEnv.strict(), PREFIX, key),
                    spec,
                    progress);
            if (a.ack() != null) {
                if (!ranOut.isEmpty()) heaps.good(key, HeapFlags.parseXmx(attemptCommand));
                return a.ack();
            }
            long heap = HeapFlags.parseXmx(attemptCommand);
            if (!WorkerFate.heapExhausted(a.exit(), a.tail()) || heap <= 0) {
                return GraphAck.error("graph worker exited " + a.exit() + " without an answer"
                        + (a.tail().isEmpty() ? "" : ": " + a.tail()));
            }
            heaps.note(key, heap);
            ranOut.add(heap);
            Long bigger = HeapLadder.next(ranOut);
            if (bigger == null) {
                return GraphAck.error("graph worker " + HeapLadder.ranOut(ranOut) + "; raise -Xmx in [jvm] args");
            }
            HeapNotes.note(HeapNotes.line(bigger, heap, false));
            command = HeapFlags.rewriteHeap(attemptCommand, bigger);
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

    private static Attempt attempt(WarmWorkers.Launch launch, Path spec, Consumer<GraphProgress> progress)
            throws IOException, InterruptedException {
        AtomicReference<GraphAck> answer = new AtomicReference<>();
        StringBuilder tail = new StringBuilder();
        WarmWorkers.Outcome outcome = WarmWorkers.run(
                launch,
                spec,
                new WarmWorkers.Listener() {
                    @Override
                    public void protocol(String json) {
                        switch (String.valueOf(Jsonl.str(json, PluginClient.TYPE))) {
                            case "progress" ->
                                progress.accept(new GraphProgress(
                                        Jsonl.intValue(json, "done", 0),
                                        Jsonl.intValue(json, "total", 0),
                                        String.valueOf(Jsonl.str(json, "label"))));
                            case "answer" -> answer.set(ack(json));
                            case "error" -> answer.set(GraphAck.error(String.valueOf(Jsonl.str(json, "msg"))));
                            default -> {}
                        }
                    }

                    @Override
                    public void output(String line) {
                        tail.append(line).append('\n');
                        if (tail.length() > 8_192) tail.delete(0, tail.length() - 8_192);
                    }
                },
                () -> false);
        return new Attempt(answer.get(), outcome.exit(), tail.toString().strip());
    }

    private static GraphAck ack(String json) {
        String text = Jsonl.str(json, "text");
        String dir = Jsonl.str(json, "dir");
        return new GraphAck(
                null,
                text == null ? "" : text,
                Jsonl.intValue(json, "nodes", 0),
                Jsonl.intValue(json, "edges", 0),
                Jsonl.intValue(json, "files", 0),
                Jsonl.intValue(json, "extracted", 0),
                Jsonl.intValue(json, "classes", 0),
                Jsonl.longValue(json, "millis", 0),
                dir == null ? "" : dir,
                null,
                Jsonl.strArray(json, "uncompiled"));
    }

    private static Path spec(
            Path root,
            GraphRequest r,
            Path cache,
            ClassIndexes indexes,
            List<String> modules,
            List<String> prefixes,
            GraphScripts.@Nullable Tools scripts)
            throws IOException {
        List<String> rules = "rules".equals(r.op()) && !r.args().isEmpty()
                ? GraphRules.rows(root, relative(root, r.args().getFirst()), allModulePrefixes(root))
                : List.of();
        boolean apis = "read".equals(r.op()) || "find".equals(r.op());
        return specWriter(root, r, indexes.rows(), modules, scripts)
                .configString("cache", cache.toString())
                .configList(
                        "uncompiled",
                        indexes.uncompiled().stream().map(Uncompiled::row).toList())
                .configList("rules", rules)
                .configList("libraries", apis ? libraryRows(root) : List.of())
                .configString("apiCache", apis ? JkStores.resolve("graph-api").toString() : null)
                .configList("modulePrefixes", prefixes)
                .configList("aliases", aliasRows(graphConfig(root)))
                .configList("moduleEdges", "rename".equals(r.op()) ? GraphModuleEdges.rows(root) : List.of())
                .writeTempSpec();
    }

    /** The spec of one operation on {@code root}: the worker's whole input. */
    static SpecWriter specWriter(
            Path root,
            GraphRequest r,
            List<String> indexes,
            List<String> modules,
            GraphScripts.@Nullable Tools scripts) {
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
                .configBool("undirected", r.undirected())
                .configInt("maxHops", r.maxHops())
                .configString("relation", r.relation())
                .configBool("rebuild", r.rebuild())
                .configBool("noRefresh", r.noRefresh())
                .configList("classIndexes", indexes)
                .configList("modules", modules)
                .configString("scope", r.scope())
                .configString("sourceSet", r.sourceSet())
                .configList("options", r.options());
        if (scripts != null) {
            w.configString("node", scripts.node().toString())
                    .configString("oxc", scripts.oxc().toString());
        }
        return w;
    }

    /**
     * The facts-index rows the graph reads ({@code index \t modulePrefix \t sourceSet \t digest \t
     * classes \t stale \t compileKeys}, one per module's class set ({@link #classSets}), {@code index} empty
     * for a set left unindexed because the cache holds its facts, {@code digest} the set's cache key
     * when a compile stamped it, {@code stale} the workspace-relative sources edited since the build
     * and {@code compileKeys} the action keys the compiles stamped on the classes, each joined by
     * {@link #STALE_SEPARATOR}), the modules whose
     * classes it does not read, the edited sources whose own classes it skips, and the modules with
     * main sources and no main classes and the test suites ({@link #testSet}) with no classes.
     */
    record ClassIndexes(List<String> rows, List<String> leftOut, List<String> edited, List<Uncompiled> uncompiled) {
        static final ClassIndexes NONE = new ClassIndexes(List.of(), List.of(), List.of(), List.of());

        /** How answers name the {@link #uncompiled} sets. */
        List<String> uncompiledNames() {
            return uncompiled.stream().map(Uncompiled::name).toList();
        }
    }

    /**
     * A module's source set with sources and no compiled classes the graph reads: the module at
     * {@code prefix} ({@code ""} for the root, else ending in {@code /}), the directory under it its
     * sources sit in, ending in {@code /}, and the name an answer gives it.
     */
    record Uncompiled(String prefix, String dir, String name) {
        /** {@code prefix \t dir \t name}, as the graph worker reads it. */
        String row() {
            return prefix + "\t" + dir + "\t" + name;
        }
    }

    /** Between the entries of a row's stale and compile-key fields: a character no path or key holds. */
    static final String STALE_SEPARATOR = "\u001f";

    /**
     * The class facts of every module whose classes its last build wrote, each index brought up to
     * date first and the module checked again after, so a build writing the classes meanwhile
     * leaves the module out rather than half in. A source edited since that build
     * ({@link ModuleInputProvenance#staleSources}) contributes what it says; every other class of
     * the module keeps its compiled facts, so one edit does not take its neighbours' calls with it.
     * A call, an override or a line read off a class older than its own source would be a false
     * answer, so the answer names what it skipped, and the modules and test sets with sources it has
     * no classes of. A set whose compiled facts the cache at {@code cache} holds under its compile keys
     * is not indexed, unless {@code index}: the graph worker reads those facts from the cache.
     */
    static ClassIndexes classIndexes(Path root, Path cache, boolean index) {
        List<String> out = new ArrayList<>();
        List<String> leftOut = new ArrayList<>();
        List<String> edited = new ArrayList<>();
        List<Uncompiled> uncompiled = new ArrayList<>();
        for (Map.Entry<Path, JkBuild> module : modules(root).entrySet()) {
            BuildLayout layout = BuildLayout.of(root, module.getKey(), module.getValue());
            String rel = root.relativize(module.getKey()).toString().replace('\\', '/');
            String prefix = rel.isEmpty() ? "" : rel + "/";
            String name = rel.isEmpty() ? String.valueOf(module.getKey().getFileName()) : rel;
            boolean compact = ModuleLayout.isCompact(module.getKey());
            if (!hasClasses(layout.classesDir()) && hasMainSources(module.getKey())) {
                uncompiled.add(new Uncompiled(prefix, compact ? "src/" : "src/main/", name));
            }
            for (String suite : uncompiledSuites(module.getKey(), layout)) {
                String dir = compact ? suite + "/" : "src/" + suite + "/";
                uncompiled.add(new Uncompiled(prefix, dir, testSet(rel, compact, suite)));
            }
            Map<String, Path> sets = classSets(layout);
            if (sets.values().stream().noneMatch(Files::isDirectory)) continue;
            Set<String> stale = ModuleInputProvenance.staleSources(root, module.getKey(), module.getValue());
            if (stale == null) {
                leftOut.add(name);
                continue;
            }
            List<String> staleRel = stale.stream().map(s -> prefix + s).toList();
            String staleField = String.join(STALE_SEPARATOR, staleRel);
            List<String> rows = new ArrayList<>();
            try {
                for (Map.Entry<String, Path> classSet : sets.entrySet()) {
                    String set = classSet.getKey();
                    Path classes = classSet.getValue();
                    if (!Files.isDirectory(classes)) continue;
                    List<String> keys = compileKeys(classes);
                    String key = GraphCacheKeys.classes(prefix, set, keys);
                    String tail = staleField + "\t" + String.join(STALE_SEPARATOR, keys);
                    OptionalInt held =
                            key == null || index ? OptionalInt.empty() : GraphCacheKeys.heldClasses(cache, key);
                    if (key != null && held.isPresent()) {
                        rows.add("\t" + prefix + "\t" + set + "\t" + key + "\t" + held.getAsInt() + "\t" + tail);
                        continue;
                    }
                    FactsIndexing.Ensured e =
                            FactsIndexing.ensure(classes, FactsIndexing.indexPath(layout.buildDir(), set));
                    rows.add(e.index() + "\t" + prefix + "\t" + set + "\t" + (key == null ? e.bodyDigest() : key) + "\t"
                            + e.classes() + "\t" + tail);
                }
            } catch (IOException | RuntimeException unreadable) {
                leftOut.add(name);
                continue;
            }
            if (!stale.equals(ModuleInputProvenance.staleSources(root, module.getKey(), module.getValue()))) {
                leftOut.add(name);
                continue;
            }
            out.addAll(rows);
            edited.addAll(staleRel);
        }
        return new ClassIndexes(out, leftOut, edited, uncompiled);
    }

    /**
     * A module's class sets by the name a row gives them: its main classes, its test classes (the
     * default suite and those compiled with it) and its guard suite's.
     */
    static Map<String, Path> classSets(BuildLayout layout) {
        Map<String, Path> out = new LinkedHashMap<>();
        out.put("main", layout.classesDir());
        out.put("test", layout.testClassesDir());
        out.put(TestSuites.GUARD, layout.guardClassesDir());
        return out;
    }

    /** The action keys of the compiles whose output {@code classes} is, from the stamps they left in it. */
    static List<String> compileKeys(Path classes) {
        List<String> out = new ArrayList<>();
        for (String stamp : List.of(BuildStamps.JAVA, BuildStamps.KOTLIN, BuildStamps.GROOVY)) {
            FreshnessStamp.stampedKey(classes, stamp).ifPresent(out::add);
        }
        return out;
    }

    /** {@code ack} with the modules and edited sources whose compiled facts were left out named in its note. */
    static GraphAck withNote(GraphAck ack, List<String> leftOut, List<String> edited) {
        if (!ack.ok() || (leftOut.isEmpty() && edited.isEmpty())) return ack;
        List<String> lines = new ArrayList<>();
        if (!leftOut.isEmpty()) {
            lines.add("compiled facts left out for " + String.join(", ", leftOut)
                    + " (classes older than the sources, or being built): calls and overrides there are missing, so a"
                    + " callers answer is not complete, and lines come from the sources; jk build brings them back");
        }
        if (!edited.isEmpty()) {
            List<String> shown = edited.subList(0, Math.min(edited.size(), 5));
            lines.add(edited.size() + (edited.size() == 1 ? " source edited" : " sources edited")
                    + " since the build (" + String.join(", ", shown) + (edited.size() > shown.size() ? ", …" : "")
                    + "): the calls they make are missing from a callers answer and their lines come from the"
                    + " sources, so search those files for calls too; every other file keeps its compiled facts;"
                    + " jk build brings them back");
        }
        return withLine(ack, String.join("\n", lines));
    }

    /**
     * The directories, relative to {@code root} and ending in {@code /} ({@code ""} for the root),
     * of the modules {@code selector} names; none for no selector.
     *
     * @throws IllegalArgumentException when the selector names no module
     */
    static List<String> modulePrefixes(Path root, @Nullable String selector) throws IOException {
        if (selector == null || selector.isBlank()) return List.of();
        Path manifest = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(manifest)) throw new IllegalArgumentException("-m needs a jk.toml at " + root);
        ModuleSelection.Result picked = ModuleSelection.resolve(root, JkBuildParser.parse(manifest), selector);
        String refused = picked.errorMessage();
        if (refused != null) throw new IllegalArgumentException(refused);
        List<String> out = new ArrayList<>();
        for (Path dir : picked.moduleDirs()) {
            String rel =
                    root.relativize(dir.toAbsolutePath().normalize()).toString().replace('\\', '/');
            out.add(rel.isEmpty() ? "" : rel + "/");
        }
        out.sort(null);
        return out;
    }

    /** {@code path} from the workspace root with {@code /} separators; a relative path is already that. */
    static String relative(Path root, String path) {
        Path p = Path.of(path);
        String rel = p.isAbsolute()
                ? root.relativize(p.normalize()).toString()
                : p.normalize().toString();
        return rel.replace('\\', '/');
    }

    /** Every module's directory under the root, ending in {@code /}; {@code ""} for the root. */
    static List<String> allModulePrefixes(Path root) {
        List<String> out = new ArrayList<>();
        for (Path dir : modules(root).keySet()) {
            String rel = root.relativize(dir).toString().replace('\\', '/');
            out.add(rel.isEmpty() ? "" : rel + "/");
        }
        return out;
    }

    /**
     * {@code coord \t jar} for every jar of the workspace lock the store holds, every scope: what
     * {@code read} and {@code find} answer a type the workspace does not declare from.
     */
    static List<String> libraryRows(Path root) {
        Path lock = root.resolve(ManifestNames.LOCK);
        if (!Files.isRegularFile(lock)) return List.of();
        List<String> out = new ArrayList<>();
        try {
            Lockfile locked = LockfileReader.read(lock);
            for (ClasspathResolver.Entry e :
                    new ClasspathResolver(JkStores.storeCas()).entriesOnDisk(locked, EnumSet.allOf(Scope.class))) {
                Path jar = e.jar();
                if (jar != null && jar.getFileName().toString().endsWith(".jar")) {
                    out.add(e.artifact().displayCoord() + "\t" + jar);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("graph: no locked jars for " + root, unreadable);
        }
        return out;
    }

    /** The root manifest's {@code [graph]} table; empty without one or when it does not parse. */
    static GraphConfig graphConfig(Path root) {
        try {
            return JkBuildParser.graphConfig(ManifestPaths.manifestIn(root));
        } catch (RuntimeException unparsable) {
            Log.debug("graph: no [graph] settings for " + root, unparsable);
            return GraphConfig.EMPTY;
        }
    }

    /** {@code term \t word \t word …} for every alias. */
    static List<String> aliasRows(GraphConfig config) {
        List<String> out = new ArrayList<>();
        config.aliases().forEach((term, words) -> {
            if (!words.isEmpty()) out.add(term + "\t" + String.join("\t", words));
        });
        out.sort(null);
        return out;
    }

    /** {@code prefix \t compact} for every module: its directory under the root and its layout. */
    static List<String> moduleRows(Path root) {
        List<String> out = new ArrayList<>();
        for (Path dir : modules(root).keySet()) {
            String rel = root.relativize(dir).toString().replace('\\', '/');
            out.add((rel.isEmpty() ? "" : rel + "/") + "\t" + ModuleLayout.isCompact(dir));
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
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (IOException | RuntimeException unparsable) {
            Log.debug("graph: no module list for " + root, unparsable);
        }
        return out;
    }
}
