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

    /** Which agents get project files. */
    public enum Mode {
        /** Agents already in use here: their marker exists, or the calling shell belongs to one. */
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

    private AgentSetup() {}

    /**
     * Bring {@code dir} up to date for its agents.
     *
     * @param claudeRunning whether the calling shell belongs to Claude Code
     * @return one line per file written or skipped; empty when everything was already current
     * @throws IOException when {@code AGENTS.md} cannot be written or has malformed markers
     */
    public static List<String> install(Path dir, Mode mode, boolean claudeRunning) throws IOException {
        List<String> lines = new ArrayList<>();
        AgentsGuide.Change guide = AgentsGuide.sync(dir);
        if (guide != AgentsGuide.Change.UNCHANGED) lines.add(guideLine(guide, dir.resolve(AgentsGuide.FILE)));
        if (mode == Mode.NONE) return lines;
        boolean claude = mode == Mode.ALL
                || claudeRunning
                || Files.isDirectory(dir.resolve(CLAUDE_DIR))
                || Files.exists(dir.resolve(CLAUDE_MD));
        if (claude) claudeCode(dir, lines);
        return lines;
    }

    /**
     * Take jk's entries out of {@code dir}: its {@code AGENTS.md} section and the Claude Code files
     * {@link #install} wrote. Everything else in those files stays, and a file left empty is deleted.
     *
     * @return one line per file changed; empty when jk had written nothing here
     */
    public static List<String> remove(Path dir) throws IOException {
        List<String> lines = new ArrayList<>();
        if (AgentsGuide.remove(dir) == AgentsGuide.Change.UPDATED) {
            lines.add("removed jk's section of " + dir.resolve(AgentsGuide.FILE));
        }
        step(dir.resolve(CLAUDE_MD), lines, () -> dropClaudeMd(dir.resolve(CLAUDE_MD)), "removed jk's entries from ");
        Path mcp = dir.resolve(MCP_FILE);
        step(mcp, lines, () -> mergeJson(mcp, AgentSetup::dropMcpServer), "removed jk's entries from ");
        Path settings = dir.resolve(CLAUDE_DIR).resolve(CLAUDE_SETTINGS);
        step(settings, lines, () -> mergeJson(settings, AgentSetup::dropClaudeSettings), "removed jk's entries from ");
        return lines;
    }

    private static String guideLine(AgentsGuide.Change change, Path file) {
        return switch (change) {
            case CREATED -> "created " + file;
            case INSERTED -> "added jk's section to " + file;
            case UPDATED -> "updated jk's section of " + file;
            case UNCHANGED, ABSENT -> file + " is current";
        };
    }

    private static void claudeCode(Path dir, List<String> lines) {
        step(dir.resolve(CLAUDE_MD), lines, () -> claudeMd(dir.resolve(CLAUDE_MD)));
        step(dir.resolve(MCP_FILE), lines, () -> mergeJson(dir.resolve(MCP_FILE), AgentSetup::mcpServer));
        Path settings = dir.resolve(CLAUDE_DIR).resolve(CLAUDE_SETTINGS);
        step(settings, lines, () -> mergeJson(settings, AgentSetup::claudeSettings));
    }

    private interface Write {
        boolean run() throws IOException;
    }

    /** Runs one file's writer; a file jk cannot safely edit is reported and left as it was. */
    private static void step(Path file, List<String> lines, Write write) {
        step(file, lines, write, "wrote jk's entries in ");
    }

    private static void step(Path file, List<String> lines, Write write, String done) {
        try {
            if (write.run()) lines.add(done + file);
        } catch (IOException | RuntimeException unsafe) {
            lines.add("left " + file + " alone: " + unsafe.getMessage());
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
        Map<String, Object> servers = existingObject(root, "mcpServers");
        if (servers == null) return;
        servers.remove(SERVER);
        if (servers.isEmpty()) root.remove("mcpServers");
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

    private static void mcpServer(Map<String, Object> root) throws IOException {
        Map<String, Object> servers = object(root, "mcpServers");
        servers.putIfAbsent(
                SERVER, new LinkedHashMap<>(Map.of("command", "jk", "args", new ArrayList<>(List.of("mcp")))));
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
