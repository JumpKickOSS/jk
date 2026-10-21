// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.GraphIds;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration files. Spring's {@code application*} and {@code bootstrap*} files declare one
 * node per key, so code reading {@code ${server.port}} links to the line that sets it; build files
 * declare their dependencies; everything else contributes only the class names and {@code ${…}}
 * placeholders it mentions. Keys of other files are not nodes: a data file's keys would bury the
 * code they configure.
 */
final class ConfigExtractors {

    private ConfigExtractors() {}

    /** Keys one file may declare before the rest are dropped. */
    static final int MAX_KEYS = 1000;

    private static final Pattern SPRING_CONFIG =
            Pattern.compile("(?:^|/)(?:application|bootstrap)(?:-[\\w.-]+)?\\.(?:ya?ml|properties)$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-\\[\\]]+)(?::[^}]*)?}");
    private static final Pattern YAML_KEY =
            Pattern.compile("^(\\s*)(?:-\\s+)?([^#\\s'\"][^:#]*?|'[^']*'|\"[^\"]*\")\\s*:(?:\\s+(.*))?$");
    private static final Pattern TOML_TABLE = Pattern.compile("^\\s*\\[\\[?\\s*([^\\]]+?)\\s*]]?\\s*(?:#.*)?$");
    private static final Pattern TOML_KEY = Pattern.compile("^\\s*([A-Za-z0-9_.\\-\"':]+)\\s*=\\s*(.*)$");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern TOML_FIELD = Pattern.compile("\\b(group|name|module)\\s*=\\s*\"([^\"]+)\"");

    static Fragment yaml(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "document", "config");
        boolean spring = SPRING_CONFIG.matcher(relPath).find();
        List<int[]> indents = new ArrayList<>();
        List<String> path = new ArrayList<>();
        List<String> lines = Lines.split(text);
        int keys = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("---")) {
                indents.clear();
                path.clear();
                continue;
            }
            Matcher m = YAML_KEY.matcher(line);
            if (m.matches()) {
                int indent = m.group(1).length() + (line.stripLeading().startsWith("- ") ? 2 : 0);
                while (!indents.isEmpty() && indents.getLast()[0] >= indent) {
                    indents.removeLast();
                    path.removeLast();
                }
                indents.add(new int[] {indent});
                path.add(unquote(m.group(2).strip()));
                String value = m.group(3) == null ? "" : m.group(3).strip();
                boolean leaf = !value.isEmpty() && !value.startsWith("#") && !value.equals("|") && !value.equals(">");
                if (spring && leaf && keys++ < MAX_KEYS) configKey(b, String.join(".", path), i + 1);
            }
            values(b, line, i + 1);
        }
        return b.build();
    }

    static Fragment properties(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "document", "config");
        boolean spring = SPRING_CONFIG.matcher(relPath).find();
        List<String> lines = Lines.split(text);
        int keys = 0;
        for (int i = 0; i < lines.size(); i++) {
            int start = i;
            StringBuilder logical = new StringBuilder(lines.get(i).stripLeading());
            while (endsWithContinuation(logical) && i + 1 < lines.size()) {
                logical.setLength(logical.length() - 1);
                logical.append(lines.get(++i).stripLeading());
            }
            String entry = logical.toString();
            if (entry.isEmpty() || entry.startsWith("#") || entry.startsWith("!")) continue;
            int sep = separator(entry);
            String key = (sep < 0 ? entry : entry.substring(0, sep)).strip().replace("\\ ", " ");
            if (spring && !key.isEmpty() && keys++ < MAX_KEYS) configKey(b, key, start + 1);
            values(b, sep < 0 ? "" : entry.substring(sep + 1), start + 1);
        }
        return b.build();
    }

    static Fragment toml(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "document", "config");
        String name = relPath.substring(relPath.lastIndexOf('/') + 1);
        boolean jk = name.equals("jk.toml");
        boolean catalog = name.endsWith(".versions.toml");
        String table = "";
        boolean modules = false;
        List<String> lines = Lines.split(text);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int n = i + 1;
            Matcher t = TOML_TABLE.matcher(line);
            if (t.matches()) {
                table = t.group(1);
                modules = false;
                continue;
            }
            Matcher kv = TOML_KEY.matcher(line);
            if (kv.matches()) {
                String key = unquote(kv.group(1));
                String value = kv.group(2);
                modules = jk && table.equals("workspace") && key.equals("modules");
                if (jk && table.endsWith("dependencies")) jkDependency(b, key, value, n);
                if (catalog && table.equals("libraries")) catalogLibrary(b, value, n);
            }
            if (modules) {
                Matcher q = QUOTED.matcher(line);
                while (q.find()) {
                    b.mention(b.fileId(), Mention.Kind.FILE, "/" + q.group(1) + "/jk.toml", n, Relations.CONTAINS);
                }
                if (line.contains("]")) modules = false;
            }
            values(b, line, n);
        }
        return b.build();
    }

    static Fragment json(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "document", "config");
        String name = relPath.substring(relPath.lastIndexOf('/') + 1);
        Lines lines = new Lines(text);
        Object doc;
        try {
            doc = MiniJson.parseRelaxed(text);
        } catch (IllegalArgumentException malformed) {
            doc = null;
        }
        if (doc instanceof Map<?, ?> root) {
            if (name.equals("package.json")) packageJson(b, root, text, lines);
            if (name.startsWith("tsconfig") || name.startsWith("jsconfig")) {
                String ext = MiniJson.str(root, "extends");
                if (ext != null && ext.startsWith("."))
                    b.mention(b.fileId(), Mention.Kind.FILE, ext, lineOf(text, lines, "\"extends\""));
            }
        }
        Lines.eachClassName(text, lines, (cls, line) -> b.mention(b.fileId(), Mention.Kind.CLASS, cls, line));
        return b.build();
    }

    static Fragment csv(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "document", "data");
        int eol = text.indexOf('\n');
        String header = (eol < 0 ? text : text.substring(0, eol)).strip();
        if (!header.isEmpty()) {
            List<String> columns = new ArrayList<>();
            for (String c : header.split(header.contains(";") && !header.contains(",") ? ";" : ",")) {
                columns.add(unquote(c.strip()));
            }
            b.annotateFile(Map.of("columns", columns));
        }
        return b.build();
    }

    /** {@code META-INF/services/*}, {@code *.imports}, {@code spring.factories}: one class per entry. */
    static Fragment classList(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "document", "config");
        String name = relPath.substring(relPath.lastIndexOf('/') + 1);
        if (relPath.contains("META-INF/services/")) b.mention(b.fileId(), Mention.Kind.CLASS, name, 1);
        Lines.eachClassName(text, new Lines(text), (cls, line) -> b.mention(b.fileId(), Mention.Kind.CLASS, cls, line));
        return b.build();
    }

    private static void packageJson(FragmentBuilder b, Map<?, ?> root, String text, Lines lines) {
        for (String section : List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")) {
            if (MiniJson.get(root, section) instanceof Map<?, ?> deps) {
                for (Object dep : deps.keySet()) {
                    String d = String.valueOf(dep);
                    Dependencies.npm(b, b.fileId(), d, lineOf(text, lines, "\"" + d + "\""));
                }
            }
        }
        for (String entry : List.of("main", "module", "types", "browser")) {
            String target = MiniJson.str(root, entry);
            if (target != null)
                b.mention(b.fileId(), Mention.Kind.FILE, target, lineOf(text, lines, "\"" + entry + "\""));
        }
        if (MiniJson.get(root, "scripts") instanceof Map<?, ?> scripts) {
            for (Object script : scripts.keySet()) {
                String s = String.valueOf(script);
                int line = lineOf(text, lines, "\"" + s + "\"");
                String id = b.declared("script " + s, s, "code", line, Map.of("node_kind", "script"));
                b.edge(b.fileId(), id, Relations.CONTAINS, Confidence.EXTRACTED, line);
            }
        }
    }

    private static void jkDependency(FragmentBuilder b, String key, String value, int line) {
        Map<String, String> fields = new HashMap<>();
        Matcher f = TOML_FIELD.matcher(value);
        while (f.find()) fields.put(f.group(1), f.group(2));
        if (key.contains(":")) {
            String[] ga = key.split(":");
            Dependencies.declare(b, b.fileId(), ga[0], ga[1], line);
        } else if (fields.containsKey("group")) {
            Dependencies.declare(b, b.fileId(), fields.get("group"), fields.getOrDefault("name", key), line);
        } else if (!value.contains("workspace")) {
            String id = b.external(GraphIds.make("library", key), key);
            b.edge(b.fileId(), id, Relations.DEPENDS_ON, Confidence.EXTRACTED, line);
        }
    }

    private static void catalogLibrary(FragmentBuilder b, String value, int line) {
        Matcher f = TOML_FIELD.matcher(value);
        String group = null;
        String name = null;
        while (f.find()) {
            switch (f.group(1)) {
                case "module" -> {
                    String[] ga = f.group(2).split(":");
                    if (ga.length >= 2) {
                        group = ga[0];
                        name = ga[1];
                    }
                }
                case "group" -> group = f.group(2);
                case "name" -> name = f.group(2);
                default -> {}
            }
        }
        if (group == null) {
            Matcher q = QUOTED.matcher(value);
            if (q.find() && q.group(1).split(":").length >= 2) {
                String[] ga = q.group(1).split(":");
                group = ga[0];
                name = ga[1];
            }
        }
        if (group != null && name != null) Dependencies.declare(b, b.fileId(), group, name, line);
    }

    private static void configKey(FragmentBuilder b, String key, int line) {
        String id = b.node(
                GraphIds.make(GraphIds.stem(b.relPath()), key),
                key,
                "document",
                line,
                Map.of("node_kind", Linker.CONFIG_KEY));
        b.edge(b.fileId(), id, Relations.CONTAINS, Confidence.EXTRACTED, line);
    }

    /** Class names and {@code ${key}} placeholders in one value. */
    private static void values(FragmentBuilder b, String value, int line) {
        if (value.isEmpty()) return;
        Matcher m = Lines.FQCN.matcher(value);
        while (m.find()) b.mention(b.fileId(), Mention.Kind.CLASS, m.group(), line);
        Matcher p = PLACEHOLDER.matcher(value);
        while (p.find()) b.mention(b.fileId(), Mention.Kind.CONFIG_KEY, p.group(1), line);
    }

    private static int lineOf(String text, Lines lines, String needle) {
        int at = text.indexOf(needle);
        return at < 0 ? 1 : lines.at(at);
    }

    private static boolean endsWithContinuation(CharSequence s) {
        int backslashes = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) backslashes++;
        return backslashes % 2 == 1;
    }

    /** The offset of the first unescaped {@code =}, {@code :} or space ending a properties key. */
    private static int separator(String entry) {
        for (int i = 0; i < entry.length(); i++) {
            char c = entry.charAt(i);
            if (c == '\\') i++;
            else if (c == '=' || c == ':' || c == ' ' || c == '\t') return i;
        }
        return -1;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
