// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.NodeTable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

/** The {@code [node]} table of {@code jk.toml}. */
final class ManifestNode {

    static final String TABLE = "node";

    static final List<String> KEYS = List.of(
            "version",
            "workspace",
            "package-manager",
            "framework",
            "install",
            "build",
            "test",
            "dev",
            "start",
            "out",
            "classpath-root",
            "webapp-root",
            "env-prefixes",
            "dev-port",
            "dir",
            "skip",
            "steps",
            "exports");

    static final List<String> STEP_KEYS =
            List.of("name", "run", "npx", "exec", "before", "tier", "inputs", "outputs", "allow-unlocked");

    static final List<String> PACKAGE_MANAGERS = List.of("npm", "pnpm", "yarn", "bun");

    static final List<String> FRAMEWORKS = List.of(
            "vite",
            "next",
            "angular",
            "nuxt",
            "sveltekit",
            "astro",
            "solid-start",
            "tanstack-start",
            "react-router",
            "plain");

    private ManifestNode() {}

    /** {@code [node]} as written; empty when the table is absent. */
    static Optional<NodeTable> parse(TomlTable root) {
        if (root.contains(TABLE) && !root.isTable(TABLE)) {
            return Optional.empty();
        }
        TomlTable t = root.getTable(TABLE);
        if (t == null) return Optional.empty();
        rejectUnknown(t, KEYS, "[node]");
        // node = { workspace = true } and [node] version = … are the toolchain alone, not a table of settings.
        if (onlyToolchain(t)) return Optional.empty();
        String pm = string(t, "package-manager");
        if (pm != null && !PACKAGE_MANAGERS.contains(pm)) {
            throw new JkBuildParseException("[node] package-manager must be one of "
                    + String.join(", ", PACKAGE_MANAGERS) + ", got \"" + pm + "\"");
        }
        String framework = string(t, "framework");
        if (framework != null && !FRAMEWORKS.contains(framework)) {
            throw new JkBuildParseException(
                    "[node] framework must be one of " + String.join(", ", FRAMEWORKS) + ", got \"" + framework + "\"");
        }
        Integer devPort = null;
        if (t.contains("dev-port")) {
            if (!(t.get("dev-port") instanceof Long port) || port < 1 || port > 65535) {
                throw new JkBuildParseException("[node] dev-port must be a port number, e.g. 5173");
            }
            devPort = port.intValue();
        }
        Boolean skip = t.contains("skip") ? t.get("skip") instanceof Boolean b ? b : null : Boolean.FALSE;
        if (skip == null) throw new JkBuildParseException("[node] skip must be true or false");
        return Optional.of(new NodeTable(
                pm,
                framework,
                string(t, "install"),
                build(t),
                string(t, "test"),
                string(t, "dev"),
                string(t, "start"),
                relativePath(t, "out", "[node] out"),
                string(t, "classpath-root"),
                string(t, "webapp-root"),
                t.contains("env-prefixes")
                        ? JkBuildParser.optionalStringList(t, "env-prefixes", "node.env-prefixes")
                        : null,
                devPort,
                relativePath(t, "dir", "[node] dir"),
                skip,
                steps(t),
                exports(t)));
    }

    private static boolean onlyToolchain(TomlTable t) {
        for (String key : t.keySet()) {
            if (!key.equals("version") && !key.equals("workspace")) return false;
        }
        return true;
    }

    /** {@code build}: a script name, or an inline table naming one of {@code run}, {@code npx}, {@code exec}. */
    private static NodeTable.@Nullable Command build(TomlTable t) {
        if (!t.contains("build")) return null;
        Object raw = t.get("build");
        if (raw instanceof String script) return NodeTable.Command.run(script);
        if (raw instanceof TomlTable inline) {
            rejectUnknown(inline, List.of("run", "npx", "exec"), "[node] build");
            return command(inline, "[node] build");
        }
        throw new JkBuildParseException("[node] build must be a script name or { npx = \"…\" }");
    }

    private static NodeTable.Command command(TomlTable t, String where) {
        NodeTable.Command found = null;
        for (NodeTable.Command.Kind kind : NodeTable.Command.Kind.values()) {
            String value = string(t, kind.key(), where + "." + kind.key());
            if (value == null) continue;
            if (found != null) {
                throw new JkBuildParseException(where + " names more than one of run, npx, exec — keep one");
            }
            if (value.isBlank()) throw new JkBuildParseException(where + "." + kind.key() + " is empty");
            found = new NodeTable.Command(kind, value);
        }
        if (found == null) throw new JkBuildParseException(where + " needs one of run, npx or exec");
        return found;
    }

    private static List<NodeTable.Step> steps(TomlTable t) {
        if (!t.contains("steps")) return List.of();
        if (!(t.get("steps") instanceof TomlArray arr) || !(arr.isEmpty() || arr.get(0) instanceof TomlTable)) {
            throw new JkBuildParseException("[node] steps is a list of [[node.steps]] tables");
        }
        List<NodeTable.Step> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            TomlTable s = arr.getTable(i);
            String where = "[[node.steps]] #" + (i + 1);
            rejectUnknown(s, STEP_KEYS, where);
            String name = string(s, "name", where + ".name");
            if (name == null || name.isBlank()) throw new JkBuildParseException(where + " needs a name");
            if (!names.add(name)) throw new JkBuildParseException("[[node.steps]] name `" + name + "` is used twice");
            where = "[[node.steps]] `" + name + "`";
            Boolean allow =
                    s.contains("allow-unlocked") ? s.get("allow-unlocked") instanceof Boolean b ? b : null : false;
            if (allow == null) throw new JkBuildParseException(where + " allow-unlocked must be true or false");
            out.add(new NodeTable.Step(
                    name,
                    command(s, where),
                    choice(s, "before", where, NodeTable.Before.class, NodeTable.Before.BUILD),
                    choice(s, "tier", where, NodeTable.Tier.class, NodeTable.Tier.BUILD),
                    JkBuildParser.optionalStringList(s, "inputs", "node.steps." + name + ".inputs"),
                    JkBuildParser.optionalStringList(s, "outputs", "node.steps." + name + ".outputs"),
                    allow));
        }
        return out;
    }

    private static Map<String, String> exports(TomlTable t) {
        if (!t.contains("exports")) return Map.of();
        if (!(t.get("exports") instanceof TomlTable ex)) {
            throw new JkBuildParseException("[node] exports is a table of name = \"path\"");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : ex.keySet()) {
            String path = relativePath(ex, name, "[node] exports." + name);
            if (path == null) throw new JkBuildParseException("[node] exports." + name + " must be a path");
            out.put(name, path);
        }
        return out;
    }

    private static <E extends Enum<E>> E choice(TomlTable t, String key, String where, Class<E> type, E fallback) {
        String raw = string(t, key, where + "." + key);
        if (raw == null) return fallback;
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            List<String> names = new ArrayList<>();
            for (E c : type.getEnumConstants()) names.add(c.name().toLowerCase(Locale.ROOT));
            throw new JkBuildParseException(
                    where + " " + key + " must be one of " + String.join(", ", names) + ", got \"" + raw + "\"");
        }
    }

    private static @Nullable String relativePath(TomlTable t, String key, String where) {
        String path = string(t, key, where);
        if (path != null && (path.isBlank() || path.startsWith("/") || path.contains(".."))) {
            throw new JkBuildParseException(where + " must be a path inside the module, got \"" + path + "\"");
        }
        return path;
    }

    private static @Nullable String string(TomlTable t, String key) {
        return string(t, key, "[node] " + key);
    }

    private static @Nullable String string(TomlTable t, String key, String where) {
        if (!t.contains(key)) return null;
        if (!(t.get(key) instanceof String s)) throw new JkBuildParseException(where + " must be a string");
        return s;
    }

    /** An unknown key fails naming where it was typed. */
    private static void rejectUnknown(TomlTable t, List<String> known, String where) {
        for (String key : t.keySet()) {
            if (known.contains(key)) continue;
            TomlPosition at = t.inputPositionOf(List.of(key));
            String position = at == null ? "" : " at line " + at.line() + ", column " + at.column();
            throw new JkBuildParseException(where + " unknown key `" + key + "`" + position + " — expected one of: "
                    + String.join(", ", known));
        }
    }
}
