// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import cc.jumpkick.cli.engine.ClientUpdate;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.ide.IdeEngineClient;
import cc.jumpkick.cli.run.DebugAttach;
import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.diagnostic.CompilerLocus;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.ide.BspConnectionFile;
import cc.jumpkick.ide.IdeSourceRoots;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.wire.protocol.IdeWireModel;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.protocol.RequestEnvironment;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * BSP 2.x JSON-RPC over Content-Length framing (stdio). Wire-only via {@link IdeEngineClient}.
 *
 * <p>Targets, sources (incl. resources), dependency modules (with sources jars when present),
 * dependency sources, output paths, scalac and javac options, compile/test/run by target URI,
 * workspace/reload, {@code $/cancelRequest}, and publishDiagnostics after every compile. A module
 * that compiles Scala is a Scala build target ({@link BspTargetOptions}), which is how Metals
 * imports a mixed Java/Scala module. Compile, test and run queue on a worker, one at a time in
 * request order, so the stdio loop keeps reading and a {@code $/cancelRequest} reaches a running or
 * queued job.
 */
public final class BspServer {

    private static final Pattern CONTENT_LENGTH =
            Pattern.compile("Content-Length:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private final IdeEngineClient ide;
    private final InputStream in;
    private final BspFrames frames;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jk-bsp-worker");
        t.setDaemon(true);
        return t;
    });

    /** Compile/test/run requests not yet answered, oldest first. Guarded by itself. */
    private final List<LongJob> jobs = new ArrayList<>();

    /**
     * Guards the cached model, held across the engine call that fills it. No frame is written
     * under it and it is never taken under the frame writer's lock, so the two cannot deadlock.
     */
    private final Object modelLock = new Object();

    private @Nullable IdeWireModel cachedModel;
    private @Nullable ProjectInfo cachedInfo;

    /** The files each build target last published diagnostics for; read and written on the worker only. */
    private final Map<String, Set<String>> diagnosed = new HashMap<>();

    /** The newer release jk updated itself to while this server ran; empty while it is current. */
    private final Supplier<Optional<String>> newerInstall;

    public BspServer(IdeEngineClient ide, InputStream in, OutputStream out) {
        this(ide, in, out, ClientUpdate::newerInstall);
    }

    BspServer(IdeEngineClient ide, InputStream in, OutputStream out, Supplier<Optional<String>> newerInstall) {
        this.ide = ide;
        this.in = new BufferedInputStream(in);
        this.frames = new BspFrames(out);
        this.newerInstall = newerInstall;
    }

    /**
     * Serve until the IDE exits or closes the stream, or until jk updates itself to a newer
     * release: this client cannot drive the newer engine, so the request that finds the update is
     * answered with an error saying so and the server ends, for the IDE to start the new one.
     */
    public void serve() throws IOException {
        try {
            while (true) {
                String msg = readMessage();
                if (msg == null) return;
                if (superseded(msg)) return;
                handle(msg);
            }
        } catch (IOException e) {
            if (!"bsp exit".equals(e.getMessage())) throw e;
        } finally {
            awaitJobs();
            worker.shutdownNow();
        }
    }

    /** True when jk updated itself: {@code json}'s request, if it is one, is answered with the reason. */
    private boolean superseded(String json) throws IOException {
        Optional<String> newer = newerInstall.get();
        if (newer.isEmpty()) return false;
        String reason = "JumpKick was updated to " + newer.get() + "; restart the build server to use it";
        System.err.println("jk bsp: " + reason);
        try {
            String id = requestId(MiniJson.parse(json));
            if (id != null) frames.error(id, -32000, reason);
        } catch (RuntimeException notJson) {
            // Nothing to answer.
        }
        return true;
    }

    private void handle(String json) throws IOException {
        Object message;
        try {
            message = MiniJson.parse(json);
        } catch (RuntimeException e) {
            return; // not a JSON-RPC message; no id to answer on either
        }
        String method = MiniJson.str(message, "method");
        String id = requestId(message);
        if (method == null) return;
        try {
            switch (method) {
                case "build/initialize" -> {
                    // Every build this server sends from here on journals as the IDE's own.
                    RequestEnvironment.declare("bsp", sessionLabel(message));
                    frames.respond(id, initializeResultJson(workspaceLanguages()));
                }
                case "build/initialized" -> {
                    /* notification */
                }
                case "workspace/buildTargets" -> frames.respond(id, buildTargetsJson());
                case "workspace/reload" -> {
                    invalidateModel();
                    frames.respond(id, "null");
                }
                case "buildTarget/sources" -> frames.respond(id, sourcesJson(json));
                case "buildTarget/dependencyModules" -> frames.respond(id, dependencyModulesJson(json));
                case "buildTarget/outputPaths" -> frames.respond(id, outputPathsJson(json));
                case "buildTarget/scalacOptions" ->
                    frames.respond(id, perTargetItems(json, BspTargetOptions::scalacOptionsItem));
                case "buildTarget/javacOptions" ->
                    frames.respond(id, perTargetItems(json, BspTargetOptions::javacOptionsItem));
                case "buildTarget/dependencySources" ->
                    frames.respond(
                            id, perTargetItems(json, (tid, m, i) -> BspTargetOptions.dependencySourcesItem(tid, m)));
                case "buildTarget/scalaMainClasses" ->
                    frames.respond(id, perTargetItems(json, BspTargetOptions::scalaMainClassesItem));
                case "buildTarget/scalaTestClasses" ->
                    frames.respond(id, perTargetItems(json, (tid, m, i) -> BspTargetOptions.scalaTestClassesItem(tid)));
                case "buildTarget/compile" -> scheduleLong(id, json, "compile");
                case "buildTarget/test" -> scheduleLong(id, json, "test");
                case "buildTarget/run" -> scheduleLong(id, json, "run");
                case "$/cancelRequest" -> cancel(message);
                case "build/shutdown" -> {
                    awaitJobs();
                    worker.shutdownNow();
                    frames.respond(id, "null");
                }
                case "build/exit" -> {
                    awaitJobs();
                    throw new IOException("bsp exit");
                }
                default -> {
                    if (id != null) frames.error(id, -32601, "Method not found: " + method);
                }
            }
        } catch (IOException e) {
            if ("bsp exit".equals(e.getMessage())) throw e;
            // Per-request isolation — one failed handler must not kill the BSP session.
            invalidateModel();
            if (id != null) {
                String msg =
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                frames.error(id, -32000, msg);
            }
        } catch (RuntimeException e) {
            invalidateModel();
            if (id != null) {
                String msg =
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                frames.error(id, -32000, msg);
            }
        }
    }

    /**
     * Queue a compile/test/run on the worker behind the requests before it. The reader never waits
     * for a job, so a {@code $/cancelRequest} framed behind this request is read while it runs.
     */
    private void scheduleLong(@Nullable String id, String requestJson, String kind) throws IOException {
        if (id == null) return;
        Path moduleDir = resolveTargetModule(requestJson);
        Path workDir = moduleDir != null ? moduleDir : ide.projectDir();
        Callable<String> body =
                switch (kind) {
                    case "compile" -> () -> compileJson(requestJson, moduleDir);
                    case "test" -> () -> testJson(requestJson, moduleDir);
                    default -> () -> runJson(requestJson, moduleDir);
                };
        LongJob job = new LongJob(id, workDir, body);
        synchronized (jobs) {
            job.future = worker.submit(job);
            jobs.add(job);
        }
    }

    /**
     * The JSON-RPC {@code $/cancelRequest} notification: {@code params.id} names one compile/test/run
     * request, running or still queued. A read request has already answered by the time its cancel
     * is read, and a notification without an id names nothing, so both are ignored.
     */
    private void cancel(@Nullable Object message) throws IOException {
        String target = requestId(MiniJson.get(message, "params"));
        if (target == null) return;
        for (LongJob job : outstanding()) {
            if (target.equals(job.id)) job.cancel();
        }
    }

    /** Block until every queued or running job has answered. */
    private void awaitJobs() {
        for (LongJob job : outstanding()) job.awaitDone();
    }

    private List<LongJob> outstanding() {
        synchronized (jobs) {
            return List.copyOf(jobs);
        }
    }

    /**
     * One compile/test/run request on the worker. Its reply is the job's own once it has started;
     * cancelled before that, it never starts and is answered as cancelled from the reader thread.
     */
    private final class LongJob implements Runnable {
        private final String id;
        private final Path workDir;
        private final Callable<String> body;
        private final CountDownLatch done = new CountDownLatch(1);
        private @Nullable Future<?> future;
        private boolean started;
        private boolean cancelled;

        LongJob(String id, Path workDir, Callable<String> body) {
            this.id = id;
            this.workDir = workDir;
            this.body = body;
        }

        @Override
        public void run() {
            synchronized (this) {
                if (cancelled) return;
                started = true;
            }
            try {
                frames.respond(id, body.call());
            } catch (Exception e) {
                invalidateModel();
                String msg =
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                try {
                    frames.error(id, -32000, msg);
                } catch (IOException ignored) {
                    // the stdio channel is gone; nobody is left to answer
                }
            } finally {
                finish();
            }
        }

        /** Cancel on the engine when running; otherwise refuse the request before it starts. */
        void cancel() throws IOException {
            boolean running;
            synchronized (this) {
                if (cancelled || done.getCount() == 0) return;
                cancelled = true;
                running = started;
            }
            if (running) {
                ide.cancel(workDir);
                if (future != null) future.cancel(true);
            } else {
                if (future != null) future.cancel(false);
                finish();
                frames.error(id, -32800, "request cancelled");
            }
        }

        void awaitDone() {
            try {
                done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void finish() {
            synchronized (jobs) {
                jobs.remove(this);
            }
            done.countDown();
        }
    }

    private void invalidateModel() {
        synchronized (modelLock) {
            cachedModel = null;
            cachedInfo = null;
            // The names the requests carry are read from these summaries; a reload re-reads jk.toml.
            ProjectInfos.forget();
        }
    }

    private IdeWireModel model() throws IOException {
        synchronized (modelLock) {
            if (cachedModel == null) cachedModel = ide.ideModel();
            return cachedModel;
        }
    }

    private ProjectInfo info() throws IOException {
        synchronized (modelLock) {
            if (cachedInfo == null) cachedInfo = ide.projectInfo();
            return cachedInfo;
        }
    }

    private String buildTargetsJson() throws IOException {
        ProjectInfo info = info();
        IdeWireModel model = model();
        String rootUri = pathUri(ide.projectDir());
        List<String> targets = new ArrayList<>();
        if (model != null && model.moduleDirs() != null && !model.moduleDirs().isEmpty()) {
            List<String> dirs = model.moduleDirs();
            List<String> names = model.names() != null ? model.names() : List.of();
            List<String> mains = model.mainClasses() != null ? model.mainClasses() : List.of();
            for (int i = 0; i < dirs.size(); i++) {
                String name = i < names.size()
                        ? names.get(i)
                        : Path.of(dirs.get(i)).getFileName().toString();
                String id = rootUri + "#" + name;
                boolean canRun = i < mains.size()
                        && mains.get(i) != null
                        && !mains.get(i).isBlank();
                targets.add(targetJson(id, name, pathUri(Path.of(dirs.get(i))), canRun, model, i));
                addSuiteTargets(targets, id, name, Path.of(dirs.get(i)));
            }
        } else {
            String display = info.coord() != null && !info.coord().isBlank() ? info.coord() : "root";
            boolean canRun = model != null
                    && model.mainClasses() != null
                    && !model.mainClasses().isEmpty()
                    && model.mainClasses().getFirst() != null
                    && !model.mainClasses().getFirst().isBlank();
            targets.add(targetJson(rootUri + "#root", display, rootUri, canRun, model, 0));
            addSuiteTargets(targets, rootUri + "#root", display, ide.projectDir());
        }
        return JsonFields.object().token("targets", arrayOf(targets)).finish();
    }

    /** What separates a module's target id from one of its suites: {@code <module id>::<suite>}. */
    static final String SUITE_SEPARATOR = "::";

    /** One test target per suite of {@code moduleDir} beside its default suite, directory or class pattern. */
    private static void addSuiteTargets(List<String> targets, String moduleId, String display, Path moduleDir) {
        for (String suite : IdeSourceRoots.suites(moduleDir)) {
            if (!TestSuites.DEFAULT.equals(suite)) targets.add(suiteTargetJson(moduleId, display, suite));
        }
    }

    /**
     * A test target for {@code suite} of the module {@code moduleId} names: no sources and no
     * languages of its own (the module's target owns them), so testing it is the one thing it does,
     * {@code jk test -s <suite>} for that module.
     */
    static String suiteTargetJson(String moduleId, String display, String suite) {
        return JsonFields.object()
                .token("id", uriJson(moduleId + SUITE_SEPARATOR + suite))
                .string("displayName", display + " · " + suite)
                .array("tags", List.of("test"))
                .array("languageIds", List.of())
                .token("dependencies", arrayOf(List.of(uriJson(moduleId))))
                .token(
                        "capabilities",
                        JsonFields.object()
                                .bool("canCompile", false)
                                .bool("canTest", true)
                                .bool("canRun", false)
                                .finish())
                .finish();
    }

    /** The suite a target id names after {@link #SUITE_SEPARATOR}, or {@code null} for a module's own target. */
    static @Nullable String suiteOf(String targetUri) {
        int hash = targetUri.indexOf('#');
        int at = targetUri.indexOf(SUITE_SEPARATOR, Math.max(0, hash));
        return hash < 0 || at < 0 ? null : targetUri.substring(at + SUITE_SEPARATOR.length());
    }

    /** Package-visible for contract tests. */
    static String targetJson(String id, String display, String baseDir) {
        return targetJson(id, display, baseDir, false, null, 0);
    }

    static String targetJson(String id, String display, String baseDir, boolean canRun) {
        return targetJson(id, display, baseDir, canRun, null, 0);
    }

    /**
     * One build target. Its {@code languageIds} are the languages module {@code i} compiles; a
     * module that compiles Scala carries the {@code scala} data Metals imports it by, any other
     * the {@code jvm} data naming its JDK. A module whose manifest names an {@code [application]}
     * main is tagged {@code application}, every other {@code library}.
     */
    static String targetJson(
            String id, String display, String baseDir, boolean canRun, @Nullable IdeWireModel model, int i) {
        List<String> languages = model == null ? List.of() : model.languagesOf(i);
        JsonFields target = JsonFields.object()
                .token("id", uriJson(id))
                .string("displayName", display)
                .string("baseDirectory", baseDir)
                .array("tags", List.of(canRun ? "application" : "library"))
                .array("languageIds", languages.isEmpty() ? LANGUAGE_IDS : languages)
                .token("dependencies", arrayOf(dependencyIds(id, model, i)))
                .token(
                        "capabilities",
                        JsonFields.object()
                                .bool("canCompile", true)
                                .bool("canTest", true)
                                .bool("canRun", canRun)
                                .finish());
        if (model != null) {
            String scala = BspTargetOptions.scalaTargetData(model, i);
            String jvm = scala == null ? BspTargetOptions.jvmTargetData(model, i) : null;
            if (scala != null)
                target.string("dataKind", BspTargetOptions.SCALA_DATA_KIND).token("data", scala);
            else if (jvm != null) target.string("dataKind", "jvm").token("data", jvm);
        }
        return target.finish();
    }

    /**
     * The targets of the workspace modules module {@code i} depends on, main or test scope alike,
     * as identifiers beside {@code id}: BSP's {@code dependencies} are the direct upstream targets.
     */
    private static List<String> dependencyIds(String id, @Nullable IdeWireModel model, int i) {
        if (model == null) return List.of();
        String rootUri = id.substring(0, Math.max(0, id.indexOf('#')));
        List<String> out = new ArrayList<>();
        for (String name : BspTargetOptions.siblingNames(model, i)) {
            String dep = uriJson(rootUri + "#" + name);
            if (!out.contains(dep)) out.add(dep);
        }
        return out;
    }

    /** The languages this server compiles when the workspace's own set is not known. */
    static final List<String> LANGUAGE_IDS = List.of("java", "kotlin", "groovy", "scala");

    /**
     * The languages the workspace compiles, as the union of its modules' sets; {@link
     * #LANGUAGE_IDS} when the model cannot be computed.
     */
    private List<String> workspaceLanguages() {
        try {
            IdeWireModel model = model();
            return model == null ? LANGUAGE_IDS : BspConnectionFile.languages(model);
        } catch (IOException | RuntimeException e) {
            return LANGUAGE_IDS;
        }
    }

    /** One item per requested target, built by {@code item} from the model and the module index. */
    private interface TargetItem {
        String build(String tid, IdeWireModel model, int moduleIndex);
    }

    private String perTargetItems(String requestJson, TargetItem item) throws IOException {
        IdeWireModel model = model();
        List<String> requested = extractTargetUris(requestJson);
        String rootUri = pathUri(ide.projectDir());
        List<String> items = new ArrayList<>();
        if (model == null)
            return JsonFields.object().token("items", arrayOf(items)).finish();
        if (model.moduleDirs() != null && !model.moduleDirs().isEmpty()) {
            List<String> dirs = model.moduleDirs();
            List<String> names = model.names() != null ? model.names() : List.of();
            for (int i = 0; i < dirs.size(); i++) {
                String name = i < names.size()
                        ? names.get(i)
                        : Path.of(dirs.get(i)).getFileName().toString();
                String tid = rootUri + "#" + name;
                if (!requested.isEmpty() && !requested.contains(tid)) continue;
                items.add(item.build(tid, model, i));
            }
        } else {
            String tid = rootUri + "#root";
            if (requested.isEmpty() || requested.contains(tid)) items.add(item.build(tid, model, 0));
        }
        return JsonFields.object().token("items", arrayOf(items)).finish();
    }

    /** Four hex digits minted once per server process: two windows of one IDE are two sessions. */
    private static final String SESSION_ID = String.format("%04x", new SecureRandom().nextInt(1 << 16));

    /**
     * The journal's session label for this server: the client's {@code displayName} from
     * {@code build/initialize} and the per-process id, e.g. {@code IntelliJ-BSP 7b2c}.
     */
    static String sessionLabel(@Nullable Object initializeMessage) {
        Object params = initializeMessage instanceof Map<?, ?> m ? m.get("params") : null;
        Object name = params instanceof Map<?, ?> p ? p.get("displayName") : null;
        String client = name instanceof String s && !s.isBlank() ? s.trim() : "bsp-client";
        return client + " " + SESSION_ID;
    }

    /** The {@code build/initialize} result: server identity and the three provider capabilities. */
    static String initializeResultJson(List<String> languages) {
        String provider = JsonFields.object().array("languageIds", languages).finish();
        return JsonFields.object()
                .string("displayName", "jk")
                .string("version", JkVersion.VERSION)
                .string("bspVersion", "2.1.0")
                .token(
                        "capabilities",
                        JsonFields.object()
                                .token("compileProvider", provider)
                                .token("testProvider", provider)
                                .token("runProvider", provider)
                                .bool("canReload", true)
                                .finish())
                .finish();
    }

    /** A per-target result item opened with its {@code target} identifier, for the fields that follow. */
    static JsonFields itemFor(String tid) {
        return JsonFields.object().token("target", uriJson(tid));
    }

    /** {@code {"uri":…}} — the BSP identifier envelope every target and document carries. */
    static String uriJson(String uri) {
        return JsonFields.object().string("uri", uri).finish();
    }

    /** A JSON array of already-encoded objects. */
    private static String arrayOf(List<String> encodedObjects) {
        return "[" + String.join(",", encodedObjects) + "]";
    }

    private String sourcesJson(String requestJson) throws IOException {
        IdeWireModel model = model();
        List<String> requested = extractTargetUris(requestJson);
        List<String> items = new ArrayList<>();
        String rootUri = pathUri(ide.projectDir());
        if (model != null && model.moduleDirs() != null) {
            List<String> dirs = model.moduleDirs();
            List<String> names = model.names() != null ? model.names() : List.of();
            for (int i = 0; i < dirs.size(); i++) {
                String name = i < names.size()
                        ? names.get(i)
                        : Path.of(dirs.get(i)).getFileName().toString();
                String tid = rootUri + "#" + name;
                if (!requested.isEmpty() && !requested.contains(tid)) continue;
                Path mod = Path.of(dirs.get(i));
                items.add(sourcesItem(tid, mod, model, i));
            }
        } else {
            String tid = rootUri + "#root";
            if (requested.isEmpty() || requested.contains(tid)) {
                items.add(sourcesItem(tid, ide.projectDir(), model, 0));
            }
        }
        return JsonFields.object().token("items", arrayOf(items)).finish();
    }

    private static String sourcesItem(String tid, Path mod, IdeWireModel model, int moduleIndex) {
        List<String> srcs = new ArrayList<>();
        // same roots as jk ide (all TestSuites + main + resources).
        for (IdeSourceRoots.Root root : IdeSourceRoots.of(mod)) {
            Path s = mod.resolve(root.relative());
            if (Files.isDirectory(s)) srcs.add(sourceItemJson(pathUri(s), false));
        }
        // Generated sources from the engine model when present.
        if (model != null) {
            addGenRoot(srcs, model.genSrcDirs(), moduleIndex);
            addGenRoot(srcs, model.genTestSrcDirs(), moduleIndex);
        }
        return JsonFields.object()
                .token("target", uriJson(tid))
                .token("sources", arrayOf(srcs))
                .finish();
    }

    /** BSP's {@code SourceItemKind} and {@code OutputPathItemKind} for a directory; 1 is a file. */
    static final int DIRECTORY = 2;

    private static String sourceItemJson(String uri, boolean generated) {
        return JsonFields.object()
                .string("uri", uri)
                .number("kind", DIRECTORY)
                .bool("generated", generated)
                .finish();
    }

    private static void addGenRoot(List<String> srcs, List<String> dirs, int index) {
        if (dirs == null || index < 0 || index >= dirs.size()) return;
        String d = dirs.get(index);
        if (d == null || d.isBlank()) return;
        Path p = Path.of(d);
        if (!Files.isDirectory(p)) return;
        srcs.add(sourceItemJson(pathUri(p), true));
    }

    private String dependencyModulesJson(String requestJson) throws IOException {
        IdeWireModel model = model();
        List<String> requested = extractTargetUris(requestJson);
        String rootUri = pathUri(ide.projectDir());
        List<String> depMods = libJarModules(model);
        List<String> items = new ArrayList<>();
        if (model != null && model.moduleDirs() != null && !model.moduleDirs().isEmpty()) {
            List<String> dirs = model.moduleDirs();
            List<String> names = model.names() != null ? model.names() : List.of();
            for (int i = 0; i < dirs.size(); i++) {
                String name = i < names.size()
                        ? names.get(i)
                        : Path.of(dirs.get(i)).getFileName().toString();
                String tid = rootUri + "#" + name;
                if (!requested.isEmpty() && !requested.contains(tid)) continue;
                // Per-target: same resolved jars for v1 (engine model is workspace-wide).
                items.add(JsonFields.object()
                        .token("target", uriJson(tid))
                        .token("modules", arrayOf(depMods))
                        .finish());
            }
        } else {
            String tid = rootUri + "#root";
            if (requested.isEmpty() || requested.contains(tid)) {
                items.add(JsonFields.object()
                        .token("target", uriJson(tid))
                        .token("modules", arrayOf(depMods))
                        .finish());
            }
        }
        return JsonFields.object().token("items", arrayOf(items)).finish();
    }

    private static List<String> libJarModules(IdeWireModel model) {
        List<String> modules = new ArrayList<>();
        if (model == null || model.libJars() == null) return modules;
        List<String> jars = model.libJars();
        List<String> names = model.libNames() != null ? model.libNames() : List.of();
        List<String> sources = model.libSources() != null ? model.libSources() : List.of();
        for (int i = 0; i < jars.size(); i++) {
            String jar = jars.get(i);
            if (jar == null || jar.isBlank()) continue;
            String path = jar.contains("|") ? jar.substring(jar.lastIndexOf('|') + 1) : jar;
            Path p = Path.of(path);
            String name =
                    i < names.size() && names.get(i) != null && !names.get(i).isBlank()
                            ? names.get(i)
                            : p.getFileName().toString();
            List<String> artifacts = new ArrayList<>();
            artifacts.add(artifactJson(pathUri(p), ""));
            if (i < sources.size()) {
                String src = sources.get(i);
                if (src != null && !src.isBlank()) {
                    artifacts.add(artifactJson(pathUri(Path.of(src)), "sources"));
                }
            }
            modules.add(JsonFields.object()
                    .string("name", name)
                    .string("version", "")
                    .string("dataKind", "maven")
                    .token(
                            "data",
                            JsonFields.object()
                                    .token("artifacts", arrayOf(artifacts))
                                    .finish())
                    .finish());
        }
        return modules;
    }

    private static String artifactJson(String uri, String classifier) {
        return JsonFields.object()
                .string("uri", uri)
                .string("classifier", classifier)
                .finish();
    }

    private String outputPathsJson(String requestJson) throws IOException {
        IdeWireModel model = model();
        List<String> requested = extractTargetUris(requestJson);
        String rootUri = pathUri(ide.projectDir());
        List<String> items = new ArrayList<>();
        if (model != null && model.moduleDirs() != null && !model.moduleDirs().isEmpty()) {
            List<String> dirs = model.moduleDirs();
            List<String> names = model.names() != null ? model.names() : List.of();
            for (int i = 0; i < dirs.size(); i++) {
                String name = i < names.size()
                        ? names.get(i)
                        : Path.of(dirs.get(i)).getFileName().toString();
                String tid = rootUri + "#" + name;
                if (!requested.isEmpty() && !requested.contains(tid)) continue;
                items.add(outputPathsItem(tid, model, i));
            }
        } else {
            String tid = rootUri + "#root";
            if (requested.isEmpty() || requested.contains(tid)) {
                items.add(outputPathsItem(tid, model, 0));
            }
        }
        return JsonFields.object().token("items", arrayOf(items)).finish();
    }

    private static String outputPathsItem(String tid, IdeWireModel model, int i) {
        List<String> outs = new ArrayList<>();
        if (model != null) {
            addOutput(outs, model.classesDirs(), i);
            addOutput(outs, model.testClassesDirs(), i);
        }
        return JsonFields.object()
                .token("target", uriJson(tid))
                .token("outputPaths", arrayOf(outs))
                .finish();
    }

    private static void addOutput(List<String> outs, List<String> dirs, int index) {
        if (dirs == null || index < 0 || index >= dirs.size()) return;
        String d = dirs.get(index);
        if (d == null || d.isBlank()) return;
        outs.add(JsonFields.object()
                .string("uri", pathUri(Path.of(d)))
                .number("kind", DIRECTORY)
                .finish());
    }

    private String compileJson(String requestJson, @Nullable Path moduleDir) throws IOException {
        var outcome = ide.buildModule(moduleDir, null);
        // Unconditional: a green compile can carry warnings, and clears what the last one reported.
        publishDiagnostics(requestJson, moduleDir, outcome.errors(), outcome.warnings());
        return statusResult(outcome, "compile failed");
    }

    /**
     * BSP {@code buildTarget/test} — run {@code jk test} for the selected module. Optional jk
     * extension in {@code params.data}
     *
     * <pre>
     * "data": {
     * "allSuites": false,
     * "suites": ["test","integration"],
     * "includeTags": ["smoke"],
     * "excludeTags": ["slow"],
     * "classes": ["com.acme.FooTest"],
     * "debug": { "port": 0, "suspend": true }
     * }
     * </pre>
     *
     * Omitted data → default suite only (same as bare {@code jk test}). {@code debug} ({@code true}
     * for the defaults, an object, or a {@code --debug-jvm} spec string) starts the test JVM with a
     * JDWP listener; the address is announced as a {@code build/logMessage} before the launch and
     * echoed in the result's {@code data} under {@code dataKind} {@value #DEBUG_DATA_KIND}.
     */
    private String testJson(String requestJson, @Nullable Path moduleDir) throws IOException {
        var selection = BspRequestParams.withTargetSuite(
                BspRequestParams.parseTestSelectionData(requestJson), extractTargetUris(requestJson));
        DebugJvm debug = armDebug(requestJson);
        var outcome = ide.testModule(moduleDir, null, selection, debug);
        return statusResult(outcome, "test failed", debug);
    }

    /**
     * BSP {@code buildTarget/run}. The same {@code data.debug} extension as {@link #testJson}; the
     * result has no {@code data} in the protocol, so the address travels as the log message only.
     */
    private String runJson(String requestJson, @Nullable Path moduleDir) throws IOException {
        DebugJvm debug = armDebug(requestJson);
        // The launched app's output travels as build/logMessage notifications — the parent's
        // stdout is the frame channel and must never carry raw program bytes.
        var outcome = ide.runModule(moduleDir, null, line -> logMessage(4, null, line), debug);
        return statusResult(outcome, "run failed", null);
    }

    /** {@code dataKind} of a test result whose JVM listened for a debugger. */
    static final String DEBUG_DATA_KIND = "jk-debug";

    /**
     * The debug request of a test/run, with its port settled and announced — a client attaching
     * to a suspended JVM needs the address before the result, which only comes after the run.
     */
    private @Nullable DebugJvm armDebug(String requestJson) throws IOException {
        DebugJvm debug = BspRequestParams.parseDebugData(requestJson);
        if (debug == null) return null;
        DebugJvm bound = DebugAttach.bind(debug);
        logMessage(3, originId(requestJson), DebugAttach.announcement(bound));
        return bound;
    }

    /** {@code build/logMessage}: 3 = info, 4 = log; {@code originId} echoes the request's when it had one. */
    private void logMessage(int type, @Nullable String originId, String message) {
        JsonFields params = JsonFields.object().number("type", type);
        if (originId != null) params.string("originId", originId);
        try {
            frames.notify("build/logMessage", params.string("message", message).finish());
        } catch (IOException clientGone) {
            // The editor hung up mid-run; keep draining so the app can finish.
        }
    }

    private static @Nullable String originId(String requestJson) {
        try {
            Object parsed = MiniJson.parse(requestJson);
            Object params = MiniJson.get(parsed, "params");
            return MiniJson.str(params == null ? parsed : params, "originId");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Each file's diagnostics for the compiled target, parsed with {@link CompilerLocus}, the one
     * header vocabulary the CLI, MCP, journal and engine share. Every notification replaces what
     * the client held for that file and target ({@code reset}), and a file this target diagnosed
     * last time that has nothing now is sent an empty list, which clears it.
     */
    private void publishDiagnostics(
            String requestJson, @Nullable Path moduleDir, List<String> errors, List<String> warnings)
            throws IOException {
        List<String> uris = extractTargetUris(requestJson);
        String targetUri = uris.isEmpty() ? pathUri(ide.projectDir()) + "#root" : uris.getFirst();
        Map<String, List<String>> byFile = new LinkedHashMap<>();
        BspDiagnostics.collectDiagnostics(byFile, errors, 1, moduleDir, ide.projectDir());
        BspDiagnostics.collectDiagnostics(byFile, warnings, 2, moduleDir, ide.projectDir());
        Set<String> now = Set.copyOf(byFile.keySet());
        for (String fixed : diagnosed.getOrDefault(targetUri, Set.of())) byFile.putIfAbsent(fixed, List.of());
        diagnosed.put(targetUri, now);
        for (Map.Entry<String, List<String>> e : byFile.entrySet()) {
            frames.notify(
                    "build/publishDiagnostics",
                    JsonFields.object()
                            .token("textDocument", uriJson(e.getKey()))
                            .token("buildTarget", uriJson(targetUri))
                            .token("diagnostics", arrayOf(e.getValue()))
                            .bool("reset", true)
                            .finish());
        }
    }

    static String statusResult(IdeEngineClient.BuildOutcome outcome, String defaultFail) {
        return statusResult(outcome, defaultFail, null);
    }

    /** As {@link #statusResult(IdeEngineClient.BuildOutcome, String)}, with the debug address in {@code data} when one was listened on. */
    static String statusResult(IdeEngineClient.BuildOutcome outcome, String defaultFail, @Nullable DebugJvm debug) {
        int statusCode = outcome.success() ? 1 : 2; // BSP: 1=OK, 2=ERROR
        JsonFields json = JsonFields.object().number("statusCode", statusCode);
        if (debug != null) {
            json.string("dataKind", DEBUG_DATA_KIND)
                    .token(
                            "data",
                            JsonFields.object()
                                    .string("address", debug.address())
                                    .string("host", debug.host())
                                    .number("port", debug.port())
                                    .bool("suspend", debug.suspend())
                                    .finish());
        }
        if (!outcome.success()) {
            String msg = outcome.errors() != null && !outcome.errors().isEmpty()
                    ? String.join("; ", outcome.errors())
                    : defaultFail;
            json.string("message", msg);
        }
        return json.finish();
    }

    /**
     * Map compile/test request targets to a module directory. First target URI wins; {@code #name}
     * suffix selects a workspace module; missing/empty → project root (full workspace / single
     * project).
     */
    private @Nullable Path resolveTargetModule(String requestJson) throws IOException {
        List<String> uris = extractTargetUris(requestJson);
        if (uris.isEmpty()) return null; // whole project / workspace
        // Multiple distinct targets: build the whole workspace rather than silently honoring
        // only the first — a superset that keeps every requested target correct.
        if (uris.stream().distinct().count() > 1) return null;
        String uri = uris.getFirst();
        int hash = uri.indexOf('#');
        if (hash < 0) return null;
        String name = uri.substring(hash + 1);
        int suite = name.indexOf(SUITE_SEPARATOR);
        if (suite >= 0) name = name.substring(0, suite);
        if (name.isBlank() || name.equals("root")) return null;
        IdeWireModel model = model();
        if (model == null || model.moduleDirs() == null) return null;
        List<String> dirs = model.moduleDirs();
        List<String> names = model.names() != null ? model.names() : List.of();
        for (int i = 0; i < dirs.size(); i++) {
            String n = i < names.size()
                    ? names.get(i)
                    : Path.of(dirs.get(i)).getFileName().toString();
            if (n.equals(name) || Path.of(dirs.get(i)).getFileName().toString().equals(name)) {
                return Path.of(dirs.get(i));
            }
        }
        // Fallback: treat fragment as relative path under project
        Path candidate = ide.projectDir().resolve(name);
        if (Files.isDirectory(candidate)) return candidate;
        return null;
    }

    /** Collect target URIs from a BSP params object (targets array or single target). */
    static List<String> extractTargetUris(String json) {
        try {
            Object parsed = MiniJson.parse(json);
            if (!(parsed instanceof Map<?, ?> outer)) return List.of();
            Map<?, ?> params = outer.get("params") instanceof Map<?, ?> inner ? inner : outer;
            Object targets = params.get("targets");
            List<?> list = targets instanceof List<?> l
                    ? l
                    : params.get("target") instanceof Object single ? List.of(single) : List.of();
            List<String> out = new ArrayList<>();
            for (Object t : list) {
                if (t instanceof Map<?, ?> m && m.get("uri") instanceof String u && !u.isBlank()) {
                    out.add(u);
                }
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Largest frame body accepted; a client announcing more is refused rather than buffered. */
    private static final int MAX_CONTENT_LENGTH = 64 * 1024 * 1024;

    /**
     * The next frame's body decoded as UTF-8, or {@code null} at end of stream or on a frame with no
     * {@code Content-Length}. The length is a byte count, so the body is read as bytes.
     */
    private @Nullable String readMessage() throws IOException {
        int contentLength = -1;
        while (true) {
            String line = readHeaderLine();
            if (line == null) return null;
            if (line.isEmpty()) break;
            Matcher m = CONTENT_LENGTH.matcher(line);
            if (m.find()) {
                try {
                    contentLength = Integer.parseInt(m.group(1));
                } catch (NumberFormatException tooLarge) {
                    throw new IOException("invalid Content-Length: " + m.group(1), tooLarge);
                }
            }
        }
        if (contentLength > MAX_CONTENT_LENGTH) {
            throw new IOException("Content-Length " + contentLength + " exceeds " + MAX_CONTENT_LENGTH);
        }
        if (contentLength < 0) return null;
        byte[] body = in.readNBytes(contentLength);
        return body.length == contentLength ? new String(body, StandardCharsets.UTF_8) : null;
    }

    /** One header line without its CRLF (a bare LF is tolerated), or {@code null} at end of stream. */
    private @Nullable String readHeaderLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) return null;
            if (b == '\n') break;
            if (b != '\r') line.write(b);
        }
        return line.toString(StandardCharsets.US_ASCII);
    }

    static String pathUri(Path p) {
        return WorkingDirectory.resolve(p).toUri().toString();
    }

    /**
     * The request id, re-rendered as the JSON literal the response has to echo — a string id comes
     * back quoted, a numeric one bare — or {@code null} for a notification, which takes no reply.
     */
    private static @Nullable String requestId(@Nullable Object message) {
        Object id = MiniJson.get(message, "id");
        return id instanceof String || id instanceof Number ? MiniJson.write(id) : null;
    }
}
