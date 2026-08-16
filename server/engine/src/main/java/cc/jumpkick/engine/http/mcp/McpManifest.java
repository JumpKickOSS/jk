// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.config.JkBuildEditor;
import cc.jumpkick.model.Scope;
import cc.jumpkick.util.PathUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Surgical jk.toml edits (preview by default). */
public final class McpManifest {

    private static final Pattern JAVA_LINE = Pattern.compile("(?m)^([ \\t]*)java[ \\t]*=[ \\t]*\\d+[ \\t]*$");

    /** First TOML table header — root-level keys must be inserted before it. */
    private static final Pattern FIRST_TABLE = Pattern.compile("(?m)^[ \\t]*\\[");

    private McpManifest() {}

    public static Map<String, Object> deps(
            String dir, String action, List<String> coords, String scopeName, boolean apply) {
        Path file = PathUtil.resolveUserPath(dir).resolve("jk.toml");
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String before = Files.readString(file, StandardCharsets.UTF_8);
            String after = before;
            Scope scope = parseScope(scopeName);
            List<String> notes = new ArrayList<>();
            if ("remove".equals(action)) {
                for (String c : coords) {
                    String name = artifactName(c);
                    after = JkBuildEditor.removeDependency(after, scope, name);
                    notes.add("remove " + name);
                }
            } else {
                for (String c : coords) {
                    Parsed p = parseCoord(c);
                    if (p.version == null) {
                        notes.add("skip " + c + " (need g:n:v)");
                        continue;
                    }
                    after = JkBuildEditor.addDependency(after, scope, p.name, p.group, p.artifact, p.version);
                    notes.add("add " + p.group + ":" + p.artifact + ":" + p.version);
                }
            }
            out.put("notes", notes);
            out.put("changed", !after.equals(before));
            out.put("preview", after.equals(before) ? "" : after);
            if (apply && !after.equals(before)) {
                Files.writeString(file, after, StandardCharsets.UTF_8);
                out.put("applied", true);
            } else {
                out.put("applied", false);
            }
        } catch (Exception e) {
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    public static Map<String, Object> workspace(String dir, String action, String path, boolean apply) {
        Path file = PathUtil.resolveUserPath(dir).resolve("jk.toml");
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String before = Files.readString(file, StandardCharsets.UTF_8);
            String after = "remove_member".equals(action)
                    ? JkBuildEditor.removeWorkspaceModule(before, path)
                    : JkBuildEditor.registerWorkspaceModule(before, path);
            out.put("changed", !after.equals(before));
            out.put("preview", after.equals(before) ? "" : after);
            if (apply && !after.equals(before)) {
                Files.writeString(file, after, StandardCharsets.UTF_8);
                out.put("applied", true);
            } else {
                out.put("applied", false);
            }
        } catch (Exception e) {
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    public static Map<String, Object> setJava(String dir, int java, boolean apply) {
        Path file = PathUtil.resolveUserPath(dir).resolve("jk.toml");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("note", "java = " + java + " is language/--release, not jdk = " + java);
        try {
            String before = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = JAVA_LINE.matcher(before);
            String after;
            if (m.find()) {
                after = m.replaceFirst(m.group(1) + "java = " + java);
            } else {
                // `java` is a root-level key: appending after a table header would bury it inside
                // that table, so insert before the first table (or append when none exists).
                Matcher table = FIRST_TABLE.matcher(before);
                after = table.find()
                        ? before.substring(0, table.start()) + "java = " + java + "\n\n" + before.substring(table.start())
                        : before.stripTrailing() + "\njava = " + java + "\n";
            }
            out.put("changed", !after.equals(before));
            out.put("preview", after);
            if (apply) {
                Files.writeString(file, after, StandardCharsets.UTF_8);
                out.put("applied", true);
            } else {
                out.put("applied", false);
            }
        } catch (Exception e) {
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    private static Scope parseScope(String raw) {
        if (raw == null || raw.isBlank() || "main".equalsIgnoreCase(raw)) return Scope.MAIN;
        try {
            return Scope.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Scope.MAIN;
        }
    }

    private static String artifactName(String coord) {
        String[] p = coord.split(":");
        return p.length >= 2 ? p[1] : coord;
    }

    private record Parsed(String name, String group, String artifact, String version) {}

    private static Parsed parseCoord(String raw) {
        String[] p = raw.split(":");
        if (p.length >= 3) return new Parsed(p[1], p[0], p[1], p[2]);
        if (p.length == 2) return new Parsed(p[1], p[0], p[1], null);
        return new Parsed(raw, "", raw, null);
    }
}
