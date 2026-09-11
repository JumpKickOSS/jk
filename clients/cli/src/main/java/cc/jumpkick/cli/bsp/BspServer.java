// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import cc.jumpkick.cli.ide.IdeEngineClient;
import cc.jumpkick.cli.run.DebugAttach;
import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.diagnostic.CompilerLocus;
import cc.jumpkick.ide.IdeSourceRoots;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.wire.protocol.IdeWireModel;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * BSP 2.x JSON-RPC over Content-Length framing (stdio). Wire-only via {@link IdeEngineClient}.
 *
 * <p>Targets, sources (incl. resources), dependency modules (with sources jars when present),
 * output paths, compile/test/run by target URI, workspace/reload, build/cancel, and
 * publishDiagnostics on compile failures. Long-running compile/test/run run on a worker so
 * {@code build/cancel} can be accepted mid-flight.
 */
public final class BspServer {

    private static final Pattern CONTENT_LENGTH =
            Pattern.compile("Content-Length:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private final IdeEngineClient ide;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jk-bsp-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<@Nullable Future<?>> activeJob = new AtomicReference<>();
    private final AtomicReference<@Nullable Path> activeDir = new AtomicReference<>();

    private @Nullable IdeWireModel cachedModel;
    private @Nullable ProjectInfo cachedInfo;

    public BspServer(IdeEngineClient ide, InputStream in, OutputStream out) {
        this.ide = ide;
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    public void serve() throws IOException {
        try {
            while (true) {
                String msg = readMessage();
                if (msg == null) return;
                handle(msg);
            }
        } catch (IOException e) {
            if (!"bsp exit".equals(e.getMessage())) throw e;
        } finally {
            awaitActiveJob();
            worker.shutdownNow();
        }
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
                case "build/initialize" -> respond(id, initializeResultJson());
                case "build/initialized" -> {
                    /* notification */
                }
                case "workspace/buildTargets" -> respond(id, buildTargetsJson());
                case "workspace/reload" -> {
                    invalidateModel();
                    respond(id, "null");
                }
                case "buildTarget/sources" -> respond(id, sourcesJson(json));
                case "buildTarget/dependencyModules" -> respond(id, dependencyModulesJson(json));
                case "buildTarget/outputPaths" -> respond(id, outputPathsJson(json));
                case "buildTarget/compile" -> scheduleLong(id, json, "compile");
                case "buildTarget/test" -> scheduleLong(id, json, "test");
                case "buildTarget/run" -> scheduleLong(id, json, "run");
                case "build/cancel" -> {
                    handleCancel(json);
                    if (id != null) respond(id, "null");
                }
                case "build/shutdown" -> {
                    awaitActiveJob();
                    worker.shutdownNow();
                    respond(id, "null");
                }
                case "build/exit" -> {
                    awaitActiveJob();
                    throw new IOException("bsp exit");
                }
                default -> {
                    if (id != null) error(id, -32601, "Method not found: " + method);
                }
            }
        } catch (IOException e) {
            if ("bsp exit".equals(e.getMessage())) throw e;
            // Per-request isolation — one failed handler must not kill the BSP session.
            invalidateModel();
            if (id != null) {
                String msg =
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                error(id, -32000, msg);
            }
        } catch (RuntimeException e) {
            invalidateModel();
            if (id != null) {
                String msg =
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                error(id, -32000, msg);
            }
        }
    }

    /**
     * Compile / test / run on the worker so the stdio loop can still accept {@code build/cancel}.
     * {@link #awaitActiveJob} is called on shutdown so clients still receive the response.
     */
    private void scheduleLong(@Nullable String id, String requestJson, String kind) {
        if (id == null) return;
        Path moduleDir;
        try {
            moduleDir = resolveTargetModule(requestJson);
        } catch (IOException e) {
            try {
                error(
                        id,
                        -32000,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } catch (IOException ignored) {
                // best-effort
            }
            return;
        }
        Path workDir = moduleDir != null ? moduleDir : ide.projectDir();
        awaitActiveJob();
        activeDir.set(workDir);

        Future<?> job = worker.submit(() -> {
            try {
                String result =
                        switch (kind) {
                            case "compile" -> compileJson(requestJson, moduleDir);
                            case "test" -> testJson(requestJson, moduleDir);
                            case "run" -> runJson(requestJson, moduleDir);
                            default ->
                                JsonFields.object()
                                        .number("statusCode", 2)
                                        .string("message", "unknown op " + kind)
                                        .finish();
                        };
                respond(id, result);
            } catch (Exception e) {
                try {
                    invalidateModel();
                    String msg = e.getMessage() != null
                            ? e.getMessage()
                            : e.getClass().getSimpleName();
                    error(id, -32000, msg);
                } catch (IOException ignored) {
                    // best-effort
                }
            } finally {
                activeDir.compareAndSet(workDir, null);
            }
        });
        activeJob.set(job);
    }

    private void handleCancel(String requestJson) {
        Path dir = activeDir.get();
        ide.cancel(dir);
        Future<?> job = activeJob.getAndSet(null);
        if (job != null) job.cancel(true);
    }

    /** Block until the in-flight compile/test/run finishes (or was cancelled). */
    private void awaitActiveJob() {
        Future<?> job = activeJob.getAndSet(null);
        if (job == null) return;
        try {
            job.get();
        } catch (Exception ignored) {
            // response already written or cancelled
        }
    }

    private void invalidateModel() {
        cachedModel = null;
        cachedInfo = null;
    }

    private IdeWireModel model() throws IOException {
        if (cachedModel == null) cachedModel = ide.ideModel();
        return cachedModel;
    }

    private ProjectInfo info() throws IOException {
        if (cachedInfo == null) cachedInfo = ide.projectInfo();
        return cachedInfo;
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
                targets.add(targetJson(id, name, pathUri(Path.of(dirs.get(i))), canRun));
            }
        } else {
            String display = info.coord() != null && !info.coord().isBlank() ? info.coord() : "root";
            boolean canRun = model != null
                    && model.mainClasses() != null
                    && !model.mainClasses().isEmpty()
                    && model.mainClasses().getFirst() != null
                    && !model.mainClasses().getFirst().isBlank();
            targets.add(targetJson(rootUri + "#root", display, rootUri, canRun));
        }
        return JsonFields.object().token("targets", arrayOf(targets)).finish();
    }

    /** Package-visible for contract tests. */
    static String targetJson(String id, String display, String baseDir) {
        return targetJson(id, display, baseDir, false);
    }

    static String targetJson(String id, String display, String baseDir, boolean canRun) {
        return JsonFields.object()
                .token("id", uriJson(id))
                .string("displayName", display)
                .string("baseDirectory", baseDir)
                .array("tags", List.of("library"))
                .array("languageIds", LANGUAGE_IDS)
                .array("dependencies", List.of())
                .token(
                        "capabilities",
                        JsonFields.object()
                                .bool("canCompile", true)
                                .bool("canTest", true)
                                .bool("canRun", canRun)
                                .finish())
                .finish();
    }

    private static final List<String> LANGUAGE_IDS = List.of("java", "kotlin", "groovy");

    /** The {@code build/initialize} result: server identity and the three provider capabilities. */
    static String initializeResultJson() {
        String provider = JsonFields.object().array("languageIds", LANGUAGE_IDS).finish();
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

    /** {@code {"uri":…}} — the BSP identifier envelope every target and document carries. */
    private static String uriJson(String uri) {
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
            // BSP SourceItemKind: 1 = file/normal source, 2 = test (see BSP protocol).
            int kind = root.test() ? 2 : 1;
            Path s = mod.resolve(root.relative());
            if (Files.isDirectory(s)) {
                srcs.add(sourceItemJson(pathUri(s), kind, false));
            }
        }
        // Generated sources from the engine model when present.
        if (model != null) {
            addGenRoot(srcs, model.genSrcDirs(), moduleIndex, false);
            addGenRoot(srcs, model.genTestSrcDirs(), moduleIndex, true);
        }
        return JsonFields.object()
                .token("target", uriJson(tid))
                .token("sources", arrayOf(srcs))
                .finish();
    }

    private static String sourceItemJson(String uri, int kind, boolean generated) {
        return JsonFields.object()
                .string("uri", uri)
                .number("kind", kind)
                .bool("generated", generated)
                .finish();
    }

    private static void addGenRoot(List<String> srcs, List<String> dirs, int index, boolean test) {
        if (dirs == null || index < 0 || index >= dirs.size()) return;
        String d = dirs.get(index);
        if (d == null || d.isBlank()) return;
        Path p = Path.of(d);
        if (!Files.isDirectory(p)) return;
        int kind = test ? 2 : 1;
        srcs.add(sourceItemJson(pathUri(p), kind, true));
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
            addOutput(outs, model.classesDirs(), i, 1); // 1 = directory
            addOutput(outs, model.testClassesDirs(), i, 1);
        }
        return JsonFields.object()
                .token("target", uriJson(tid))
                .token("outputPaths", arrayOf(outs))
                .finish();
    }

    private static void addOutput(List<String> outs, List<String> dirs, int index, int kind) {
        if (dirs == null || index < 0 || index >= dirs.size()) return;
        String d = dirs.get(index);
        if (d == null || d.isBlank()) return;
        outs.add(JsonFields.object()
                .string("uri", pathUri(Path.of(d)))
                .number("kind", kind)
                .finish());
    }

    private String compileJson(String requestJson, @Nullable Path moduleDir) throws IOException {
        var outcome = ide.buildModule(moduleDir, null);
        // Unconditional: a green compile can still carry warnings the IDE should show.
        publishDiagnostics(requestJson, outcome.errors(), outcome.warnings());
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
        var selection = parseTestSelectionData(requestJson);
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
        DebugJvm debug = parseDebugData(requestJson);
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
            notify("build/logMessage", params.string("message", message).finish());
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
     * Parsed with {@link CompilerLocus} — the one header vocabulary the CLI, MCP, journal and
     * engine already share. A private pattern here misplaced every multi-line javac block,
     * groovyc's spaced header, and any Windows path to project root line 0.
     */
    private void publishDiagnostics(String requestJson, List<String> errors, List<String> warnings) throws IOException {
        boolean noErrors = errors == null || errors.isEmpty();
        boolean noWarnings = warnings == null || warnings.isEmpty();
        if (noErrors && noWarnings) return;
        List<String> uris = extractTargetUris(requestJson);
        String targetUri = uris.isEmpty() ? pathUri(ide.projectDir()) + "#root" : uris.getFirst();
        // Group diagnostics by file URI.
        Map<String, List<String>> byFile = new LinkedHashMap<>();
        collectDiagnostics(byFile, errors, 1, ide.projectDir());
        collectDiagnostics(byFile, warnings, 2, ide.projectDir());
        for (Map.Entry<String, List<String>> e : byFile.entrySet()) {
            notify(
                    "build/publishDiagnostics",
                    JsonFields.object()
                            .token("textDocument", uriJson(e.getKey()))
                            .token("buildTarget", uriJson(targetUri))
                            .token("diagnostics", arrayOf(e.getValue()))
                            .finish());
        }
    }

    /** BSP severity: 1 = error, 2 = warning. An unparseable block lands at project root line 0. */
    static void collectDiagnostics(Map<String, List<String>> byFile, List<String> raw, int severity, Path projectDir) {
        if (raw == null) return;
        for (String text : raw) {
            if (text == null || text.isBlank()) continue;
            CompilerLocus locus = CompilerLocus.parse(text);
            if (locus != null) {
                byFile.computeIfAbsent(pathUri(Path.of(locus.file())), k -> new ArrayList<>())
                        .add(diagnosticJson(
                                Math.max(0, locus.line() - 1), Math.max(0, locus.col() - 1), severity, text.strip()));
            } else {
                byFile.computeIfAbsent(pathUri(projectDir), k -> new ArrayList<>())
                        .add(diagnosticJson(0, 0, severity, text));
            }
        }
    }

    static String diagnosticJson(int line, int col, int severity, String message) {
        String position = JsonFields.object()
                .number("line", line)
                .number("character", col)
                .finish();
        return JsonFields.object()
                .token(
                        "range",
                        JsonFields.object()
                                .token("start", position)
                                .token("end", position)
                                .finish())
                .number("severity", severity)
                .string("message", message)
                .finish();
    }

    /**
     * Parse optional {@code data} object on a BSP test request into {@link
     * cc.jumpkick.config.TestSelection}. Missing/empty → DEFAULT.
     */
    static TestSelection parseTestSelectionData(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) {
            return TestSelection.DEFAULT;
        }
        // Structural parse — the old needle/brace-slicing degraded silently on
        // pretty-printed payloads ("suites": [...]) and non-object data values.
        try {
            Object parsed = MiniJson.parse(requestJson);
            if (!(parsed instanceof Map<?, ?> outer)) {
                return TestSelection.DEFAULT;
            }
            // Full request envelope or bare params object — unwrap either.
            Map<?, ?> params = outer.get("params") instanceof Map<?, ?> inner ? inner : outer;
            Map<?, ?> src = params.get("data") instanceof Map<?, ?> d ? d : params;
            boolean all = Boolean.TRUE.equals(src.get("allSuites"));
            return TestSelection.of(
                            stringList(src.get("suites")),
                            all,
                            stringList(src.get("includeTags")),
                            stringList(src.get("excludeTags")))
                    .withClasses(stringList(src.get("classes")));
        } catch (RuntimeException e) {
            return TestSelection.DEFAULT;
        }
    }

    /**
     * The {@code data.debug} field of a BSP test/run request as a {@link DebugJvm}, or null when
     * absent or false. Accepted shapes: {@code true} (the defaults), an object with optional
     * {@code host}, {@code port} and {@code suspend}, or the {@code --debug-jvm} spec as a string.
     * Unparseable payloads read as no debug request, like the selection parse beside it.
     */
    static @Nullable DebugJvm parseDebugData(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) return null;
        try {
            Object parsed = MiniJson.parse(requestJson);
            if (!(parsed instanceof Map<?, ?> outer)) return null;
            Map<?, ?> params = outer.get("params") instanceof Map<?, ?> inner ? inner : outer;
            Map<?, ?> src = params.get("data") instanceof Map<?, ?> d ? d : params;
            Object debug = src.get("debug");
            if (debug instanceof Boolean on) return on ? DebugJvm.DEFAULT : null;
            if (debug instanceof String spec) return DebugJvm.parse(spec);
            if (!(debug instanceof Map<?, ?> fields)) return null;
            String host = fields.get("host") instanceof String h && !h.isBlank() ? h : DebugJvm.DEFAULT_HOST;
            int port = fields.get("port") instanceof Number n ? n.intValue() : DebugJvm.DEFAULT_PORT;
            boolean suspend = !Boolean.FALSE.equals(fields.get("suspend"));
            return new DebugJvm(host, port, suspend);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> stringList(@Nullable Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof String str && !str.isBlank()) out.add(str);
        }
        return out;
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
        // Structural — the old regex collected any "uri" anywhere — including ones
        // nested inside data payloads.
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

    private synchronized void respond(@Nullable String id, String resultJson) throws IOException {
        if (id == null) return;
        writeMessage(JsonFields.object()
                .string("jsonrpc", "2.0")
                .token("id", id)
                .token("result", resultJson)
                .finish());
    }

    private synchronized void notify(String method, String paramsJson) throws IOException {
        writeMessage(JsonFields.object()
                .string("jsonrpc", "2.0")
                .string("method", method)
                .token("params", paramsJson)
                .finish());
    }

    private synchronized void error(String id, int code, String message) throws IOException {
        writeMessage(JsonFields.object()
                .string("jsonrpc", "2.0")
                .token("id", id)
                .token(
                        "error",
                        JsonFields.object()
                                .number("code", code)
                                .string("message", message)
                                .finish())
                .finish());
    }

    private void writeMessage(String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        out.write("Content-Length: " + bytes.length + "\r\n\r\n");
        out.write(body);
        out.flush();
    }

    private @Nullable String readMessage() throws IOException {
        int contentLength = -1;
        while (true) {
            String line = in.readLine();
            if (line == null) return null;
            if (line.isEmpty()) break;
            Matcher m = CONTENT_LENGTH.matcher(line);
            if (m.find()) contentLength = Integer.parseInt(m.group(1));
        }
        if (contentLength < 0) return null;
        char[] buf = new char[contentLength];
        int off = 0;
        while (off < contentLength) {
            int n = in.read(buf, off, contentLength - off);
            if (n < 0) return null;
            off += n;
        }
        return new String(buf);
    }

    private static String pathUri(Path p) {
        return p.toAbsolutePath().normalize().toUri().toString();
    }

    /**
     * The request id, re-rendered as the JSON literal the response has to echo — a string id comes
     * back quoted, a numeric one bare — or {@code null} for a notification, which takes no reply.
     *
     * <p>The regex this replaced took the first {@code "id":} anywhere in the message, so a
     * {@code buildTarget/run} whose {@code params.data} carried an {@code id} of its own was
     * answered under the wrong one and the IDE waited out its own request forever.
     */
    private static @Nullable String requestId(@Nullable Object message) {
        Object id = MiniJson.get(message, "id");
        return id instanceof String || id instanceof Number ? MiniJson.write(id) : null;
    }
}
