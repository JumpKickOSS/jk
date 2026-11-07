// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Wires a project for the coding agents that work in it: jk's {@code AGENTS.md} section, then, for
 * each agent in use, the project-scoped files that register jk's MCP server, allow its tools and
 * route the agent through {@code jk agent-hook}. Only project files are written, and only jk's own
 * entries in them: every other key is kept.
 */
public final class AgentSetup {

    /** The hook command both the settings file and {@link AgentHook} agree on. */
    public static final String HOOK_COMMAND = "jk agent-hook";

    static final String MCP_FILE = ".mcp.json";
    static final String CLAUDE_MD = "CLAUDE.md";
    static final String CLAUDE_DIR = ".claude";
    static final String CLAUDE_SETTINGS = "settings.json";
    static final String SERVER = "jk";
    static final String HOOK_MATCHER = "Bash|Grep";
    static final String CURSOR_MCP = ".cursor/mcp.json";
    static final String VSCODE_MCP = ".vscode/mcp.json";
    static final String GEMINI_DIR = ".gemini";
    static final String GEMINI_SETTINGS = ".gemini/settings.json";
    static final String OPENCODE_JSON = "opencode.json";
    static final String OPENCODE_JSONC = "opencode.jsonc";

    /** agy's always-on workspace rule, as measured: without it agy reads every file the graph names. */
    static final String AGY_RULES_FILE = ".agents/rules/jk.md";

    static final String AGY_RULES = """
            ---
            trigger: always_on
            ---
            jk's MCP answers are compiled facts; they are final. `callers` and `rename` take `Type.method` as
            the task names it: no file needs finding or opening first.

            - Who calls `T.m`: call_mcp_tool jk `callers` once and write its list as the answer. Opening the
              files it names costs turns and changes nothing: do not.
            - Rename `T.m`: jk `rename` once. It runs the edited tests and guard itself; when its answer says
              the task is done, reply, without searching for the old name or re-reading files (another type's
              method of the same name is not this one). Edit only the comment or doc lines its answer lists, if
              they mean the method.
            - jk `skill` only when a verdict names a topic.
            """;

    /** Cursor's always-on rule, as measured: without it Cursor greps before it calls {@code rename}. */
    static final String CURSOR_RULES_FILE = ".cursor/rules/jk.mdc";

    static final String CURSOR_RULES = """
            ---
            description: JumpKick (jk) code graph
            alwaysApply: true
            ---
            This project's Java code is indexed by jk's code graph, on the `jk` MCP server. For a question about
            or a change to a Java method, use it, not Grep or file reads:

            - Rename `T.m`: call `rename` first, before any search or read. It edits the method, its overrides
              and every caller in one call, then runs the edited tests and guard: when its answer says the task
              is done, it is. Do not grep for the old name afterwards.
            - Who calls `T.m`: `callers` once. Its list is complete, from the compiled classes.
            """;

    /** An agent jk can tell is on this machine, by its CLI on {@code PATH} (or, for Claude Code, its shell). */
    public enum Host {
        CLAUDE("claude"),
        CURSOR("cursor-agent"),
        GEMINI("gemini"),
        OPENCODE("opencode"),
        AGY("agy"),
        CODEX("codex");

        /** The executable whose presence on {@code PATH} means the agent is installed. */
        public final String cli;

        Host(String cli) {
            this.cli = cli;
        }
    }

    /** Which agents get project files. */
    public enum Mode {
        /** Agents in use here or installed: a project marker, the calling shell, or the agent's CLI. */
        DETECT,
        /** Every supported agent. */
        ALL,
        /** {@code AGENTS.md} only. */
        NONE;

        /** {@code detect}, {@code all} or {@code none}; anything else (or {@code null}) is {@link #DETECT}. */
        public static Mode parse(@Nullable String text) {
            if (text == null) return DETECT;
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "all" -> ALL;
                case "none" -> NONE;
                default -> DETECT;
            };
        }
    }

    /** The step every agent leaves to the user, so it is said once, not per agent. */
    public static final String TRUST_NOTE = "Agents ignore AGENTS.md and .mcp.json until you trust this folder";

    static final String CODEX_NOTE = "Codex: register jk's tools once with codex mcp add jk -- jk mcp";

    private AgentSetup() {}

    /** One file jk changed; {@code action} is the verb read before its path ({@code Wrote}, {@code Removed}). */
    public record Change(String action, Path file) {}

    /**
     * What a run did: the files it changed, then each step it left to the user, paths relative to
     * the project directory.
     */
    public record Report(List<Change> changes, List<String> notes) {
        public Report {
            changes = List.copyOf(changes);
            notes = List.copyOf(notes);
        }
    }

    private record Draft(Path dir, List<Change> changes, List<String> notes) {
        Draft(Path dir) {
            this(dir, new ArrayList<>(), new ArrayList<>());
        }

        Report report() {
            return new Report(changes, notes);
        }
    }

    /**
     * Bring {@code dir} up to date for its agents.
     *
     * @param hosts the agents present on this machine; in {@link Mode#DETECT} each gets its files
     * @return each file written, then each file left alone and each step only the user can take,
     *     {@link #TRUST_NOTE} first
     * @throws IOException when {@code AGENTS.md} cannot be written or has malformed markers
     */
    public static Report install(Path dir, Mode mode, Set<Host> hosts) throws IOException {
        Draft lines = new Draft(dir);
        lines.notes().add(TRUST_NOTE);
        AgentsGuide.Change guide = AgentsGuide.sync(dir);
        if (guide != AgentsGuide.Change.UNCHANGED && guide != AgentsGuide.Change.ABSENT) {
            lines.changes().add(new Change(WROTE, dir.resolve(AgentsGuide.FILE)));
        }
        if (mode == Mode.NONE) return lines.report();
        boolean all = mode == Mode.ALL;
        if (all
                || hosts.contains(Host.CLAUDE)
                || Files.isDirectory(dir.resolve(CLAUDE_DIR))
                || Files.exists(dir.resolve(CLAUDE_MD))) {
            claudeCode(dir, lines);
        }
        if (all || hosts.contains(Host.CURSOR) || Files.isDirectory(dir.resolve(".cursor"))) {
            Path file = dir.resolve(CURSOR_MCP);
            step(file, lines, () -> mergeJson(file, AgentSetup::mcpServer));
            Path rules = dir.resolve(CURSOR_RULES_FILE);
            step(rules, lines, () -> ownedFile(rules, CURSOR_RULES));
        }
        if (all || Files.isDirectory(dir.resolve(".vscode"))) {
            Path file = dir.resolve(VSCODE_MCP);
            step(file, lines, () -> mergeJson(file, root -> vscodeServer(root)));
        }
        if (all
                || hosts.contains(Host.GEMINI)
                || Files.isDirectory(dir.resolve(GEMINI_DIR))
                || Files.exists(dir.resolve("GEMINI.md"))) {
            Path file = dir.resolve(GEMINI_SETTINGS);
            step(file, lines, () -> mergeJson(file, AgentSetup::geminiSettings));
        }
        Path opencode = opencodeFile(dir);
        if (all
                || hosts.contains(Host.OPENCODE)
                || Files.exists(opencode)
                || Files.isDirectory(dir.resolve(".opencode"))) {
            step(opencode, lines, () -> mergeJson(opencode, AgentSetup::opencodeConfig));
        }
        if (all || hosts.contains(Host.AGY) || Files.isDirectory(dir.resolve(".agents/rules"))) {
            Path file = dir.resolve(AGY_RULES_FILE);
            step(file, lines, () -> ownedFile(file, AGY_RULES));
        }
        if (all || hosts.contains(Host.CODEX)) lines.notes().add(CODEX_NOTE);
        return lines.report();
    }

    /** A file jk owns outright: written when absent or different from {@code text}. */
    private static boolean ownedFile(Path file, String text) throws IOException {
        if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) return false;
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return true;
    }

    /**
     * Take jk's entries out of {@code dir}: its {@code AGENTS.md} section and the Claude Code files
     * {@link #install} wrote. Everything else in those files stays, and a file left empty is deleted.
     *
     * @return each file changed, then each file left alone; no changes when jk had written nothing here
     */
    public static Report remove(Path dir) throws IOException {
        Draft lines = new Draft(dir);
        String done = "Removed jk's entries from";
        if (AgentsGuide.remove(dir) == AgentsGuide.Change.UPDATED) {
            lines.changes().add(new Change(done, dir.resolve(AgentsGuide.FILE)));
        }
        step(dir.resolve(CLAUDE_MD), lines, () -> dropClaudeMd(dir.resolve(CLAUDE_MD)), done);
        Path mcp = dir.resolve(MCP_FILE);
        step(mcp, lines, () -> mergeJson(mcp, AgentSetup::dropMcpServer), done);
        Path settings = dir.resolve(CLAUDE_DIR).resolve(CLAUDE_SETTINGS);
        step(settings, lines, () -> mergeJson(settings, AgentSetup::dropClaudeSettings), done);
        Path cursor = dir.resolve(CURSOR_MCP);
        step(cursor, lines, () -> mergeJson(cursor, AgentSetup::dropMcpServer), done);
        Path vscode = dir.resolve(VSCODE_MCP);
        step(vscode, lines, () -> mergeJson(vscode, root -> dropServer(root, "servers")), done);
        Path gemini = dir.resolve(GEMINI_SETTINGS);
        step(gemini, lines, () -> mergeJson(gemini, AgentSetup::dropMcpServer), done);
        Path opencode = opencodeFile(dir);
        step(opencode, lines, () -> mergeJson(opencode, AgentSetup::dropOpencode), done);
        Path cursorRules = dir.resolve(CURSOR_RULES_FILE);
        step(cursorRules, lines, () -> dropOwnedFile(cursorRules, CURSOR_RULES), "Removed");
        Path agy = dir.resolve(AGY_RULES_FILE);
        step(agy, lines, () -> dropOwnedFile(agy, AGY_RULES), "Removed");
        return lines.report();
    }

    private static final String WROTE = "Wrote";

    private static void claudeCode(Path dir, Draft lines) {
        step(dir.resolve(CLAUDE_MD), lines, () -> claudeMd(dir.resolve(CLAUDE_MD)));
        step(dir.resolve(MCP_FILE), lines, () -> mergeJson(dir.resolve(MCP_FILE), AgentSetup::mcpServer));
        Path settings = dir.resolve(CLAUDE_DIR).resolve(CLAUDE_SETTINGS);
        step(settings, lines, () -> mergeJson(settings, AgentSetup::claudeSettings));
    }

    private interface Write {
        boolean run() throws IOException;
    }

    /** Runs one file's writer; a file jk cannot safely edit is reported and left as it was. */
    private static void step(Path file, Draft lines, Write write) {
        step(file, lines, write, WROTE);
    }

    private static void step(Path file, Draft lines, Write write, String done) {
        try {
            if (write.run()) lines.changes().add(new Change(done, file));
        } catch (IOException | RuntimeException unsafe) {
            String shown =
                    file.startsWith(lines.dir()) ? lines.dir().relativize(file).toString() : file.toString();
            lines.notes().add("Left " + shown.replace('\\', '/') + " alone: " + unsafe.getMessage());
        }
    }

    /** {@code CLAUDE.md} imports {@code AGENTS.md}, so Claude Code reads the same guide as every other agent. */
    private static boolean claudeMd(Path file) throws IOException {
        String line = "@" + AgentsGuide.FILE;
        if (!Files.exists(file)) {
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8);
            return true;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.lines().anyMatch(l -> l.strip().equals(line))) return false;
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String sep = text.isEmpty() ? "" : text.endsWith("\n") ? eol : eol + eol;
        Files.writeString(file, text + sep + line + eol, StandardCharsets.UTF_8);
        return true;
    }

    /** Deletes a file jk owns, unless the project has changed it. */
    private static boolean dropOwnedFile(Path file, String text) throws IOException {
        if (!Files.exists(file)
                || !Files.readString(file, StandardCharsets.UTF_8).equals(text)) return false;
        Files.delete(file);
        return true;
    }

    private static boolean dropClaudeMd(Path file) throws IOException {
        if (!Files.exists(file)) return false;
        String line = "@" + AgentsGuide.FILE;
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.lines().noneMatch(l -> l.strip().equals(line))) return false;
        String rest = String.join(
                        "\n", text.lines().filter(l -> !l.strip().equals(line)).toList())
                .strip();
        if (rest.isEmpty()) Files.delete(file);
        else Files.writeString(file, rest + "\n", StandardCharsets.UTF_8);
        return true;
    }

    private static void dropMcpServer(Map<String, Object> root) throws IOException {
        dropServer(root, "mcpServers");
    }

    private static void dropClaudeSettings(Map<String, Object> root) throws IOException {
        Map<String, Object> permissions = existingObject(root, "permissions");
        if (permissions != null) {
            List<Object> allow = existingArray(permissions, "allow");
            if (allow != null) {
                allow.removeAll(List.of("mcp__" + SERVER, "Bash(jk *)"));
                if (allow.isEmpty()) permissions.remove("allow");
            }
            if (permissions.isEmpty()) root.remove("permissions");
        }
        List<Object> enabled = existingArray(root, "enabledMcpjsonServers");
        if (enabled != null) {
            enabled.remove(SERVER);
            if (enabled.isEmpty()) root.remove("enabledMcpjsonServers");
        }
        Map<String, Object> hooks = existingObject(root, "hooks");
        if (hooks != null) {
            List<Object> pre = existingArray(hooks, "PreToolUse");
            if (pre != null) {
                pre.removeIf(AgentSetup::runsHook);
                if (pre.isEmpty()) hooks.remove("PreToolUse");
            }
            if (hooks.isEmpty()) root.remove("hooks");
        }
    }

    private static Map<String, Object> entry(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }

    private static List<Object> list(Object... values) {
        return new ArrayList<>(List.of(values));
    }

    /** {@code mcpServers.jk}: the shape Claude Code ({@code .mcp.json}) and Cursor read. */
    private static void mcpServer(Map<String, Object> root) throws IOException {
        object(root, "mcpServers").putIfAbsent(SERVER, entry("command", "jk", "args", list("mcp")));
    }

    /** VS Code's {@code servers.jk}; it requires the transport named. */
    private static void vscodeServer(Map<String, Object> root) throws IOException {
        object(root, "servers").putIfAbsent(SERVER, entry("type", "stdio", "command", "jk", "args", list("mcp")));
    }

    /**
     * Gemini CLI: the server (its tool calls trusted, as the Claude Code allow-list does) and
     * {@code AGENTS.md} among the context files it reads.
     */
    private static void geminiSettings(Map<String, Object> root) throws IOException {
        object(root, "mcpServers")
                .putIfAbsent(SERVER, entry("command", "jk", "args", list("mcp"), "trust", Boolean.TRUE));
        Map<String, Object> context = object(root, "context");
        Object names = context.get("fileName");
        if (names == null) {
            context.put("fileName", list(AgentsGuide.FILE, "GEMINI.md"));
        } else if (names instanceof String one) {
            if (!one.equals(AgentsGuide.FILE)) context.put("fileName", list(AgentsGuide.FILE, one));
        } else {
            addAll(array(context, "fileName"), List.of(AgentsGuide.FILE));
        }
    }

    /** opencode's {@code mcp.jk}: a local server whose command is one array. */
    private static void opencodeConfig(Map<String, Object> root) throws IOException {
        if (root.isEmpty()) root.put("$schema", "https://opencode.ai/config.json");
        object(root, "mcp")
                .putIfAbsent(SERVER, entry("type", "local", "command", list("jk", "mcp"), "enabled", Boolean.TRUE));
    }

    private static Path opencodeFile(Path dir) {
        Path jsonc = dir.resolve(OPENCODE_JSONC);
        return Files.exists(jsonc) ? jsonc : dir.resolve(OPENCODE_JSON);
    }

    private static void dropServer(Map<String, Object> root, String key) throws IOException {
        Map<String, Object> servers = existingObject(root, key);
        if (servers == null) return;
        servers.remove(SERVER);
        if (servers.isEmpty()) root.remove(key);
    }

    private static void dropOpencode(Map<String, Object> root) throws IOException {
        dropServer(root, "mcp");
        if (root.size() == 1 && root.containsKey("$schema")) root.clear();
    }

    private static void claudeSettings(Map<String, Object> root) throws IOException {
        addAll(array(object(root, "permissions"), "allow"), List.of("mcp__" + SERVER, "Bash(jk *)"));
        addAll(array(root, "enabledMcpjsonServers"), List.of(SERVER));
        List<Object> pre = array(object(root, "hooks"), "PreToolUse");
        if (pre.stream().noneMatch(AgentSetup::runsHook)) {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("type", "command");
            command.put("command", HOOK_COMMAND);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("matcher", HOOK_MATCHER);
            entry.put("hooks", new ArrayList<>(List.of(command)));
            pre.add(entry);
        }
    }

    private static boolean runsHook(Object entry) {
        return entry instanceof Map<?, ?> m
                && m.get("hooks") instanceof List<?> hooks
                && hooks.stream().anyMatch(h -> h instanceof Map<?, ?> hm && HOOK_COMMAND.equals(hm.get("command")));
    }

    private interface Edit {
        void apply(Map<String, Object> root) throws IOException;
    }

    /** Parses {@code file} (or starts an empty object), applies {@code edit}, writes only on change. */
    private static boolean mergeJson(Path file, Edit edit) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        String before = "";
        if (Files.exists(file)) {
            before = Files.readString(file, StandardCharsets.UTF_8);
            Object parsed;
            try {
                parsed = MiniJson.parse(before);
            } catch (IllegalArgumentException bad) {
                throw new IOException("not strict JSON (" + bad.getMessage() + "); add jk's entries by hand");
            }
            if (!(parsed instanceof Map<?, ?> map)) throw new IOException("not a JSON object");
            map.forEach((k, v) -> root.put(String.valueOf(k), v));
        }
        String was = MiniJson.write(root);
        edit.apply(root);
        if (Files.exists(file) && MiniJson.write(root).equals(was)) return false;
        if (root.isEmpty()) {
            Files.delete(file);
            return true;
        }
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, MiniJson.writePretty(root), StandardCharsets.UTF_8);
        return true;
    }

    /** The mutable object at {@code key}, or {@code null} when absent or not an object. */
    private static @Nullable Map<String, Object> existingObject(Map<String, Object> parent, String key)
            throws IOException {
        return parent.get(key) instanceof Map<?, ?> ? object(parent, key) : null;
    }

    /** The mutable array at {@code key}, or {@code null} when absent or not an array. */
    private static @Nullable List<Object> existingArray(Map<String, Object> parent, String key) throws IOException {
        return parent.get(key) instanceof List<?> ? array(parent, key) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> parent, String key) throws IOException {
        Object child = parent.get(key);
        if (child == null) {
            Map<String, Object> made = new LinkedHashMap<>();
            parent.put(key, made);
            return made;
        }
        if (!(child instanceof Map<?, ?> map)) throw new IOException("\"" + key + "\" is not an object");
        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
        parent.put(key, copy);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Map<String, Object> parent, String key) throws IOException {
        Object child = parent.get(key);
        if (child == null) {
            List<Object> made = new ArrayList<>();
            parent.put(key, made);
            return made;
        }
        if (!(child instanceof List<?> list)) throw new IOException("\"" + key + "\" is not an array");
        List<Object> copy = new ArrayList<>((List<Object>) list);
        parent.put(key, copy);
        return copy;
    }

    private static void addAll(List<Object> into, List<String> wanted) {
        for (String w : wanted) if (!into.contains(w)) into.add(w);
    }
}
