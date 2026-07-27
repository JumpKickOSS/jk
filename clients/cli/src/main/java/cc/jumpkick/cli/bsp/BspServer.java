// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import cc.jumpkick.cli.ide.IdeEngineClient;
import cc.jumpkick.engine.protocol.IdeWireModel;
import cc.jumpkick.engine.protocol.ProjectInfo;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BSP 2.x JSON-RPC over Content-Length framing (stdio). Wire-only via {@link IdeEngineClient}.
 *
 * <p>ticket-1028 MVP + ticket-1041 import reliability + JK-1048 test provider: per-target
 * deps/sources, compile/test by target URI, workspace/reload, test source roots. {@code
 * buildTarget/run} is not implemented yet (use IDE tasks / {@code jk run}).
 */
public final class BspServer {

    private static final Pattern CONTENT_LENGTH =
            Pattern.compile("Content-Length:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private final IdeEngineClient ide;
    private final BufferedReader in;
    private final BufferedWriter out;
    private IdeWireModel cachedModel;
    private ProjectInfo cachedInfo;

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
        }
    }

    private void handle(String json) throws IOException {
        String method = extractString(json, "method");
        String id = extractId(json);
        if (method == null) return;
        try {
            switch (method) {
                case "build/initialize" ->
                    respond(
                            id,
                            "{\"displayName\":\"jk\",\"version\":"
                                    + q(cc.jumpkick.cli.Jk.VERSION)
                                    + ",\"bspVersion\":\"2.1.0\","
                                    + "\"capabilities\":{\"compileProvider\":{\"languageIds\":[\"java\",\"kotlin\",\"groovy\"]},"
                                    + "\"testProvider\":{\"languageIds\":[\"java\",\"kotlin\",\"groovy\"]},"
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
                case "buildTarget/compile" -> respond(id, compileJson(json));
                case "buildTarget/test" -> respond(id, testJson(json));
                case "build/shutdown" -> respond(id, "null");
                case "build/exit" -> throw new IOException("bsp exit");
                default -> {
                    if (id != null) error(id, -32601, "Method not found: " + method);
                }
            }
        } catch (IOException e) {
            if ("bsp exit".equals(e.getMessage())) throw e;
            // Per-request isolation (JK-1063): one failed handler must not kill the BSP session.
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
            for (int i = 0; i < dirs.size(); i++) {
                String name = i < names.size()
                        ? names.get(i)
                        : Path.of(dirs.get(i)).getFileName().toString();
                String id = rootUri + "#" + name;
                targets.add(targetJson(id, name, pathUri(Path.of(dirs.get(i)))));
            }
        } else {
            String display = info.coord() != null && !info.coord().isBlank() ? info.coord() : "root";
            targets.add(targetJson(rootUri + "#root", display, rootUri));
        }
        return "{\"targets\":[" + String.join(",", targets) + "]}";
    }

    /** Package-visible for contract tests (JK-1063). */
    static String targetJson(String id, String display, String baseDir) {
        return "{\"id\":{\"uri\":"
                + q(id)
                + "},\"displayName\":"
                + q(display)
                + ",\"baseDirectory\":"
                + q(baseDir)
                + ",\"tags\":[\"library\"],\"languageIds\":[\"java\",\"kotlin\",\"groovy\"],\"dependencies\":[],"
                + "\"capabilities\":{\"canCompile\":true,\"canTest\":true,\"canRun\":false}}";
    }

    private String sourcesJson(String requestJson) throws IOException {
        IdeWireModel model = model();
        List<String> requested = extractTargetUris(requestJson);
        Map<String, Integer> indexByName = nameIndex(model);
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
                items.add(sourcesItem(tid, mod));
            }
        } else {
            String tid = rootUri + "#root";
            if (requested.isEmpty() || requested.contains(tid)) {
                items.add(sourcesItem(tid, ide.projectDir()));
            }
        }
        // Ensure requested empty = all (already handled)
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private static String sourcesItem(String tid, Path mod) {
        List<String> srcs = new ArrayList<>();
        // JK-1140: same roots as jk ide (all TestSuites + main).
        for (cc.jumpkick.command.ide.IdeSourceRoots.Root root : cc.jumpkick.command.ide.IdeSourceRoots.of(mod)) {
            // BSP SourceItemKind: 1 = file/normal source, 2 = test (see BSP protocol).
            int kind = root.test() ? 2 : 1;
            Path s = mod.resolve(root.relative());
            if (Files.isDirectory(s)) {
                srcs.add("{\"uri\":" + q(pathUri(s)) + ",\"kind\":" + kind + ",\"generated\":false}");
            }
        }
        return "{\"target\":{\"uri\":" + q(tid) + "},\"sources\":[" + String.join(",", srcs) + "]}";
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
        if (model != null && model.libJars() != null) {
            for (String jar : model.libJars()) {
                if (jar == null || jar.isBlank()) continue;
                String path = jar.contains("|") ? jar.substring(jar.lastIndexOf('|') + 1) : jar;
                Path p = Path.of(path);
                modules.add("{\"name\":"
                        + q(p.getFileName().toString())
                        + ",\"version\":\"\",\"dataKind\":\"maven\",\"data\":{\"artifacts\":[{\"uri\":"
                        + q(pathUri(p))
                        + "}]}}");
            }
        }
        return modules;
    }

    private String compileJson(String requestJson) throws IOException {
        Path moduleDir = resolveTargetModule(requestJson);
        var outcome = ide.buildModule(moduleDir, null);
        return statusResult(outcome, "compile failed");
    }

    /**
     * BSP {@code buildTarget/test} — run {@code jk test} for the selected module. Optional jk
     * extension in {@code params.data} (JK-1143):
     *
     * <pre>
     * "data": {
     *   "allSuites": false,
     *   "suites": ["test","integration"],
     *   "includeTags": ["smoke"],
     *   "excludeTags": ["slow"]
     * }
     * </pre>
     *
     * Omitted data → default suite only (same as bare {@code jk test}).
     */
    private String testJson(String requestJson) throws IOException {
        Path moduleDir = resolveTargetModule(requestJson);
        var selection = parseTestSelectionData(requestJson);
        var outcome = ide.testModule(moduleDir, null, selection);
        return statusResult(outcome, "test failed");
    }

    /**
     * Parse optional {@code data} object on a BSP test request into {@link
     * cc.jumpkick.config.TestSelection}. Missing/empty → DEFAULT.
     */
    static cc.jumpkick.config.TestSelection parseTestSelectionData(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) {
            return cc.jumpkick.config.TestSelection.DEFAULT;
        }
        // Prefer a "data":{...} object if present; else allow top-level fields (lenient).
        String slice = requestJson;
        int dataIdx = requestJson.indexOf("\"data\"");
        if (dataIdx >= 0) {
            int brace = requestJson.indexOf('{', dataIdx);
            if (brace >= 0) {
                int depth = 0;
                int end = brace;
                for (; end < requestJson.length(); end++) {
                    char c = requestJson.charAt(end);
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            end++;
                            break;
                        }
                    }
                }
                if (depth == 0) slice = requestJson.substring(brace, end);
            }
        }
        return cc.jumpkick.engine.protocol.EngineProtocol.testSelectionOf(slice);
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
    private Path resolveTargetModule(String requestJson) throws IOException {
        List<String> uris = extractTargetUris(requestJson);
        if (uris.isEmpty()) return null; // whole project / workspace
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

    private static Map<String, Integer> nameIndex(IdeWireModel model) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (model == null || model.names() == null) return m;
        for (int i = 0; i < model.names().size(); i++) m.put(model.names().get(i), i);
        return m;
    }

    /** Collect target URIs from a BSP params object (targets array or single target). */
    static List<String> extractTargetUris(String json) {
        List<String> out = new ArrayList<>();
        // "uri":"file://...#name" inside targets
        Matcher m = Pattern.compile("\"uri\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            String u = m.group(1);
            if (u.contains("#") || u.startsWith("file:")) out.add(u);
        }
        return out;
    }

    private void respond(String id, String resultJson) throws IOException {
        if (id == null) return;
        writeMessage("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}");
    }

    private void error(String id, int code, String message) throws IOException {
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

    private String readMessage() throws IOException {
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

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        b.append('"');
        return b.toString();
    }

    private static String extractString(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String extractId(String json) {
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|\\d+)").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
