// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.ide.IdeEngineClient;
import cc.jumpkick.command.ide.IdeSourceRoots;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.diagnostic.CompilerLocus;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
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
                case "build/initialize" ->
                    respond(
                            id,
                            "{\"displayName\":\"jk\",\"version\":"
                                    + q(Jk.VERSION)
                                    + ",\"bspVersion\":\"2.1.0\","
                                    + "\"capabilities\":{"
                                    + "\"compileProvider\":{\"languageIds\":[\"java\",\"kotlin\",\"groovy\"]},"
                                    + "\"testProvider\":{\"languageIds\":[\"java\",\"kotlin\",\"groovy\"]},"
                                    + "\"runProvider\":{\"languageIds\":[\"java\",\"kotlin\",\"groovy\"]},"
                                    + "\"canReload\":true}}");
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
                            case "run" -> runJson(moduleDir);
                            default -> "{\"statusCode\":2,\"message\":" + q("unknown op " + kind) + "}";
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
        return "{\"targets\":[" + String.join(",", targets) + "]}";
    }

    /** Package-visible for contract tests. */
    static String targetJson(String id, String display, String baseDir) {
        return targetJson(id, display, baseDir, false);
    }

    static String targetJson(String id, String display, String baseDir, boolean canRun) {
        return "{\"id\":{\"uri\":"
                + q(id)
                + "},\"displayName\":"
                + q(display)
                + ",\"baseDirectory\":"
                + q(baseDir)
                + ",\"tags\":[\"library\"],\"languageIds\":[\"java\",\"kotlin\",\"groovy\"],\"dependencies\":[],"
                + "\"capabilities\":{\"canCompile\":true,\"canTest\":true,\"canRun\":"
                + canRun
                + "}}";
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
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private static String sourcesItem(String tid, Path mod, IdeWireModel model, int moduleIndex) {
        List<String> srcs = new ArrayList<>();
        // same roots as jk ide (all TestSuites + main + resources).
        for (IdeSourceRoots.Root root : IdeSourceRoots.of(mod)) {
            // BSP SourceItemKind: 1 = file/normal source, 2 = test (see BSP protocol).
            int kind = root.test() ? 2 : 1;
            Path s = mod.resolve(root.relative());
            if (Files.isDirectory(s)) {
                srcs.add("{\"uri\":" + q(pathUri(s)) + ",\"kind\":" + kind + ",\"generated\":false}");
            }
        }
        // Generated sources from the engine model when present.
        if (model != null) {
            addGenRoot(srcs, model.genSrcDirs(), moduleIndex, false);
            addGenRoot(srcs, model.genTestSrcDirs(), moduleIndex, true);
        }
        return "{\"target\":{\"uri\":" + q(tid) + "},\"sources\":[" + String.join(",", srcs) + "]}";
    }

    private static void addGenRoot(List<String> srcs, List<String> dirs, int index, boolean test) {
        if (dirs == null || index < 0 || index >= dirs.size()) return;
        String d = dirs.get(index);
        if (d == null || d.isBlank()) return;
        Path p = Path.of(d);
        if (!Files.isDirectory(p)) return;
        int kind = test ? 2 : 1;
        srcs.add("{\"uri\":" + q(pathUri(p)) + ",\"kind\":" + kind + ",\"generated\":true}");
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
                items.add("{\"target\":{\"uri\":" + q(tid) + "},\"modules\":[" + String.join(",", depMods) + "]}");
            }
        } else {
            String tid = rootUri + "#root";
            if (requested.isEmpty() || requested.contains(tid)) {
                items.add("{\"target\":{\"uri\":" + q(tid) + "},\"modules\":[" + String.join(",", depMods) + "]}");
            }
        }
        return "{\"items\":[" + String.join(",", items) + "]}";
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
            StringBuilder artifacts = new StringBuilder();
            artifacts.append("{\"uri\":").append(q(pathUri(p))).append(",\"classifier\":\"\"}");
            if (i < sources.size()) {
                String src = sources.get(i);
                if (src != null && !src.isBlank()) {
                    Path sp = Path.of(src);
                    artifacts.append(",{\"uri\":").append(q(pathUri(sp))).append(",\"classifier\":\"sources\"}");
                }
            }
            modules.add("{\"name\":"
                    + q(name)
                    + ",\"version\":\"\",\"dataKind\":\"maven\",\"data\":{\"artifacts\":["
                    + artifacts
                    + "]}}");
        }
        return modules;
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
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private static String outputPathsItem(String tid, IdeWireModel model, int i) {
        List<String> outs = new ArrayList<>();
        if (model != null) {
            addOutput(outs, model.classesDirs(), i, 1); // 1 = directory
            addOutput(outs, model.testClassesDirs(), i, 1);
        }
        return "{\"target\":{\"uri\":" + q(tid) + "},\"outputPaths\":[" + String.join(",", outs) + "]}";
    }

    private static void addOutput(List<String> outs, List<String> dirs, int index, int kind) {
        if (dirs == null || index < 0 || index >= dirs.size()) return;
        String d = dirs.get(index);
        if (d == null || d.isBlank()) return;
        outs.add("{\"uri\":" + q(pathUri(Path.of(d))) + ",\"kind\":" + kind + "}");
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
     * "excludeTags": ["slow"]
     * }
     * </pre>
     *
     * Omitted data → default suite only (same as bare {@code jk test}).
     */
    private String testJson(String requestJson, @Nullable Path moduleDir) throws IOException {
        var selection = parseTestSelectionData(requestJson);
        var outcome = ide.testModule(moduleDir, null, selection);
        return statusResult(outcome, "test failed");
    }

    private String runJson(@Nullable Path moduleDir) throws IOException {
        // The launched app's output travels as build/logMessage notifications — the parent's
        // stdout is the frame channel and must never carry raw program bytes.
        var outcome = ide.runModule(moduleDir, null, line -> {
            try {
                notify("build/logMessage", "{\"type\":4,\"message\":" + q(line) + "}");
            } catch (IOException clientGone) {
                // The editor hung up mid-run; keep draining so the app can finish.
            }
        });
        return statusResult(outcome, "run failed");
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
                    "{\"textDocument\":{\"uri\":"
                            + q(e.getKey())
                            + "},\"buildTarget\":{\"uri\":"
                            + q(targetUri)
                            + "},\"diagnostics\":["
                            + String.join(",", e.getValue())
                            + "]}");
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

    private static String diagnosticJson(int line, int col, int severity, String message) {
        return "{\"range\":{\"start\":{\"line\":" + line + ",\"character\":" + col + "},\"end\":{\"line\":" + line
                + ",\"character\":" + col + "}},\"severity\":" + severity + ",\"message\":" + q(message) + "}";
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
                    stringList(src.get("excludeTags")));
        } catch (RuntimeException e) {
            return TestSelection.DEFAULT;
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

    private static String statusResult(IdeEngineClient.BuildOutcome outcome, String defaultFail) {
        int statusCode = outcome.success() ? 1 : 2; // BSP: 1=OK, 2=ERROR
        StringBuilder sb = new StringBuilder();
        sb.append("{\"statusCode\":").append(statusCode);
        if (!outcome.success()) {
            String msg = outcome.errors() != null && !outcome.errors().isEmpty()
                    ? String.join("; ", outcome.errors())
                    : defaultFail;
            sb.append(",\"message\":").append(q(msg));
        }
        sb.append('}');
        return sb.toString();
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
        writeMessage("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}");
    }

    private synchronized void notify(String method, String paramsJson) throws IOException {
        writeMessage("{\"jsonrpc\":\"2.0\",\"method\":" + q(method) + ",\"params\":" + paramsJson + "}");
    }

    private synchronized void error(String id, int code, String message) throws IOException {
        writeMessage("{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"error\":{\"code\":"
                + code
                + ",\"message\":"
                + q(message)
                + "}}");
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
     * JSON string literal, via the one escaper.
     *
     * <p>The local copy this replaced escaped only {@code \ " \n \r \t} and passed everything
     * else through, so any control character below 0x20 — which an application's ANSI-coloured
     * output routinely carries — produced invalid JSON-RPC on a protocol whose peer is an IDE.
     */
    private static String q(String s) {
        return Jsonl.quote(s);
    }

    /**
     * The request id, re-rendered as the JSON literal the response has to echo — a string id comes
     * back quoted, a numeric one bare — or {@code null} for a notification, which takes no reply.
     *
     * <p>The regex this replaced took the first {@code "id":} anywhere in the message, so a
     * {@code buildTarget/run} whose {@code params.data} carried an {@code id} of its own was
     * answered under the wrong one and the IDE waited out its own request forever.
     */
    private static @Nullable String requestId(Object message) {
        Object id = MiniJson.get(message, "id");
        return id instanceof String || id instanceof Number ? MiniJson.write(id) : null;
    }
}
