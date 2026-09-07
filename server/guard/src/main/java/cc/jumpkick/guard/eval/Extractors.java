// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.extract.WorkspaceFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.ExtractorVocabulary;
import cc.jumpkick.host.CodeText;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * The closed extractors of {@code parity} and {@code generated} (PRD §4.2). A spec is an inline table
 * with exactly one key, the extractor's name; its value is the primary argument (a string) or a table
 * of named arguments. {@link #problem} judges a spec at load time so an unknown extractor is a load
 * error listing the set; {@link #extract} reads at evaluation time and throws {@link
 * ExtractorException} for a source it cannot read.
 */
final class Extractors {

    private static final int ACC_ENUM = 0x4000;

    private Extractors() {}

    /** One parsed spec: the extractor and its named arguments. */
    record Spec(String name, Map<String, String> args) {
        String arg(String key, String fallback) {
            String v = args.get(key);
            return v == null ? fallback : v;
        }

        @Nullable
        String arg(String key) {
            return args.get(key);
        }

        String label() {
            if (name.equals("guard-ids")) return name;
            StringBuilder sb = new StringBuilder(name).append('(');
            int i = 0;
            for (var e : args.entrySet()) {
                if (i++ > 0) sb.append(", ");
                sb.append(e.getValue());
            }
            return sb.append(')').toString();
        }
    }

    /** Parse a spec table; {@code null} with {@link #problem} naming what is wrong. */
    static @Nullable Spec parse(@Nullable TomlTable spec) {
        if (spec == null || spec.keySet().size() != 1) return null;
        String name = spec.keySet().iterator().next();
        if (!ExtractorVocabulary.EXTRACTORS.containsKey(name)) return null;
        return new Spec(name, ExtractorVocabulary.args(name, spec.get(name)));
    }

    static Extraction extract(Spec s, Rule rule, EvalContext ctx) throws IOException, ExtractorException {
        return switch (s.name()) {
            case "workspace-modules" -> workspaceModules(s, ctx);
            case "manifest-deps" -> manifestDeps(s, ctx);
            case "lock-artifacts" -> lockArtifacts(s, ctx);
            case "catalog-aliases" -> catalogAliases(s, ctx);
            case "toml-keys" -> tomlKeys(s, ctx);
            case "yaml-keys" -> yamlKeys(s, ctx);
            case "properties-keys" -> propertiesKeys(s, ctx);
            case "json-keys" -> jsonKeys(s, ctx);
            case "regex" -> regex(s, ctx);
            case "gradle-includes" -> gradleIncludes(s, ctx);
            case "enum-constants" -> enumConstants(s, ctx);
            case "static-finals" -> staticFinals(s, ctx);
            case "guard-ids" -> guardIds(s, rule, ctx);
            case "markdown-table" -> markdownTable(s, ctx);
            case "markdown-links" -> markdownLinks(s, ctx);
            default -> throw new ExtractorException("unknown extractor " + s.name());
        };
    }

    // ---- model -------------------------------------------------------------------------------

    private static Extraction workspaceModules(Spec s, EvalContext ctx) throws IOException, ExtractorException {
        String file = s.arg("file", ManifestPaths.MANIFEST);
        JkBuild build = JkBuildParser.parse(existing(ctx, file));
        List<Extraction.Row> rows = new ArrayList<>();
        if (build.workspace() != null) for (String m : build.workspace().modules()) rows.add(row(m, "module", m));
        return new Extraction(s.label(), file, rows);
    }

    private static Extraction manifestDeps(Spec s, EvalContext ctx) throws IOException, ExtractorException {
        String module = s.arg("module", "");
        String scopeArg = s.arg("scope", "main");
        String file = (module.isEmpty() ? "" : module + "/") + ManifestPaths.MANIFEST;
        Path manifest = existing(ctx, file);
        JkBuild build = module.isEmpty() ? JkBuildParser.parse(manifest) : JkBuildParser.parseLocal(manifest);
        List<Extraction.Row> rows = new ArrayList<>();
        for (var e : build.dependencies().byScope().entrySet()) {
            Scope scope = e.getKey();
            if (!scopeArg.equals("all") && scope != ExtractorVocabulary.scopeOf(scopeArg)) continue;
            for (Dependency d : e.getValue()) {
                String coordinate = d.isWorkspace() ? d.library() : d.module();
                Map<String, String> cols = new LinkedHashMap<>();
                cols.put("coordinate", coordinate);
                cols.put("version", d.isWorkspace() ? "" : d.version().raw());
                cols.put("scope", scope.canonical());
                rows.add(new Extraction.Row(coordinate, cols));
            }
        }
        return new Extraction(s.label(), file, rows);
    }

    private static Extraction lockArtifacts(Spec s, EvalContext ctx) throws IOException, ExtractorException {
        String file = s.arg("file", ManifestPaths.LOCK);
        Lockfile lock = LockfileReader.read(existing(ctx, file));
        List<Extraction.Row> rows = new ArrayList<>();
        for (Lockfile.Artifact a : lock.artifacts()) {
            Map<String, String> cols = new LinkedHashMap<>();
            cols.put("name", a.name());
            cols.put("version", a.version());
            cols.put("source", a.source());
            rows.add(new Extraction.Row(a.name(), cols));
        }
        return new Extraction(s.label(), file, rows);
    }

    // ---- structured text ---------------------------------------------------------------------

    private static Extraction catalogAliases(Spec s, EvalContext ctx) throws IOException, ExtractorException {
        String file = s.arg("file", "");
        TomlParseResult doc = toml(ctx, file);
        TomlTable table = doc.getTable("libraries");
        if (table == null) table = doc.getTable("catalog");
        if (table == null)
            throw new ExtractorException(
                    file + " has neither a [libraries] nor a [catalog] table to read aliases from");
        return new Extraction(s.label(), file, tableRows(table, "alias"));
    }

    private static Extraction tomlKeys(Spec s, EvalContext ctx) throws IOException, ExtractorException {
        String file = s.arg("file", "");
        String path = s.arg("table", "");
        TomlParseResult doc = toml(ctx, file);
        TomlTable table = path.isEmpty() ? doc : doc.getTable(path);
        if (table == null) throw new ExtractorException(file + " has no table [" + path + "]");
        return new Extraction(s.label(), file, tableRows(table, "key"));
    }

    private static List<Extraction.Row> tableRows(TomlTable table, String keyName) {
        List<Extraction.Row> rows = new ArrayList<>();
        for (String k : table.keySet()) {
            Object v = table.get(k);
            String value = v instanceof TomlTable || v instanceof TomlArray ? "" : String.valueOf(v);
            if (v instanceof TomlArray a) {
                List<String> items = new ArrayList<>();
                for (int i = 0; i < a.size(); i++) items.add(String.valueOf(a.get(i)));
                value = String.join(", ", items);
            }
            rows.add(row(k, keyName, k, "value", value));
        }
        return rows;
    }

    private static TomlParseResult toml(EvalContext ctx, String file) throws IOException, ExtractorException {
        TomlParseResult doc = Toml.parse(existing(ctx, file));
        if (doc.hasErrors())
            throw new ExtractorException(
                    file + ": TOML syntax: " + doc.errors().get(0).getMessage());
        return doc;
    }

    private static Extraction yamlKeys(Spec s, EvalContext ctx) throws IOException, ExtractorException {
        String file = s.arg("file", "");
        String path = s.arg("path", "");
        Map<String, String> keys = MinimalYaml.keysAt(Files.readString(existing(ctx, file)), path);
        if (keys == null) throw new ExtractorException(file + " has no mapping at `" + path + "`");
        List<Extraction.Row> rows = new ArrayList<>();
        for (var e : keys.entrySet()) rows.add(row(e.getKey(), "key", e.getKey(), "value", e.getValue()));
        return new Extraction(s.label(), file, rows);
    }

    private static Extraction propertiesKeys(Spec s, EvalContext ctx) throws IOException, ExtractorException {
        String file = s.arg("file", "");
        Properties p = new Properties();
        p.load(new StringReader(Files.readString(existing(ctx, file))));
        List<Extraction.Row> rows = new ArrayList<>();
        for (String k : p.stringPropertyNames()) rows.add(row(k, "key", k, "value", p.getProperty(k)));
        return new Extraction(s.label(), file, rows);
    }

    private static Extraction jsonKeys(Spec s, EvalContext ctx) throws IOException, ExtractorException {
        String file = s.arg("file", "");
        String path = s.arg("path", "");
        Object node;
        try {
            node = MiniJson.parse(Files.readString(existing(ctx, file)));
        } catch (RuntimeException e) {
            throw new ExtractorException(file + ": JSON syntax: " + e.getMessage());
        }
        if (!path.isEmpty()) {
            for (String seg : path.split("\\.")) {
                node = node instanceof Map<?, ?> m ? m.get(seg) : null;
                if (node == null) throw new ExtractorException(file + " has no object at `" + path + "`");
            }
        }
        if (!(node instanceof Map<?, ?> obj)) throw new ExtractorException(file + ": `" + path + "` is not an object");
        List<Extraction.Row> rows = new ArrayList<>();
        for (var e : obj.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            String value = v instanceof Map || v instanceof List ? "" : String.valueOf(v);
            rows.add(row(k, "key", k, "value", value));
        }
        return new Extraction(s.label(), file, rows);
    }

    // ---- free text ---------------------------------------------------------------------------

    private static Extraction regex(Spec s, EvalContext ctx) throws IOException, ExtractorException {
        String file = s.arg("file", "");
        Pattern p = Pattern.compile(s.arg("pattern", ""));
        int group = Integer.parseInt(s.arg("group", "1"));
        String text = codeView(existing(ctx, file));
        List<Extraction.Row> rows = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            String key = m.group(group);
            if (key == null) continue;
            Map<String, String> cols = new LinkedHashMap<>();
            cols.put("match", key);
            cols.put("line", Integer.toString(CodeText.lineAt(text, m.start())));
            for (int g = 1; g <= m.groupCount(); g++)
                cols.put(Integer.toString(g), m.group(g) == null ? "" : m.group(g));
            rows.add(new Extraction.Row(key, cols));
        }
        return new Extraction(s.label(), file, rows);
    }

    /** The file's text with comments blanked where the file is code; other files as they are. */
    private static String codeView(Path file) throws IOException, ExtractorException {
        String text = TextFiles.read(file);
        if (text == null) throw new ExtractorException(file.getFileName() + " is not UTF-8 text");
        TextFiles.Language lang = TextFiles.languageOf(file.getFileName().toString());
        return lang.code && lang.lexable ? CodeText.blank(text, CodeText.Blank.COMMENTS) : text;
    }

    private static final Pattern INCLUDE = Pattern.compile("\\binclude\\s*\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern PROJECT_DIR =
            Pattern.compile("project\\(\"(:[\\w-]+)\"\\)\\.projectDir\\s*=\\s*file\\(\"([^\"]+)\"\\)");

    private static Extraction gradleIncludes(Spec s, EvalContext ctx) throws IOException, ExtractorException {
        String file = s.arg("file", "settings.gradle.kts");
        String text = codeView(existing(ctx, file));
        Map<String, String> dirOf = new LinkedHashMap<>();
        Matcher pd = PROJECT_DIR.matcher(text);
        while (pd.find()) dirOf.put(pd.group(1), pd.group(2));
        List<Extraction.Row> rows = new ArrayList<>();
        Matcher inc = INCLUDE.matcher(text);
        while (inc.find()) {
            Matcher q = QUOTED.matcher(inc.group(1));
            while (q.find()) {
                String path = q.group(1);
                String dir =
                        dirOf.getOrDefault(path, path.replaceFirst("^:", "").replace(':', '/'));
                rows.add(row(dir, "dir", dir, "path", path));
            }
        }
        return new Extraction(s.label(), file, rows);
    }

    // ---- facts -------------------------------------------------------------------------------

    private static Extraction enumConstants(Spec s, EvalContext ctx) throws ExtractorException {
        String cls = s.arg("class", "");
        ClassFacts c = classFacts(cls, ctx);
        if (!c.hasFlag(ACC_ENUM)) throw new ExtractorException(cls + " is not an enum");
        List<Extraction.Row> rows = new ArrayList<>();
        for (FieldFacts f : c.fields()) if ((f.access() & ACC_ENUM) != 0) rows.add(row(f.name(), "name", f.name()));
        return new Extraction(s.label(), null, rows);
    }

    private static Extraction staticFinals(Spec s, EvalContext ctx) throws ExtractorException {
        String cls = s.arg("class", "");
        ClassFacts c = classFacts(cls, ctx);
        List<Extraction.Row> rows = new ArrayList<>();
        for (FieldFacts f : c.fields())
            if (f.constantValue() != null) rows.add(row(f.name(), "name", f.name(), "value", f.constantValue()));
        return new Extraction(s.label(), null, rows);
    }

    private static ClassFacts classFacts(String binaryName, EvalContext ctx) throws ExtractorException {
        String internal = Descriptors.internalName(binaryName);
        ClassFacts own = ctx.facts().classes().get(internal);
        if (own != null) return own;
        return WorkspaceFacts.lookup(ctx.root(), ctx.modules(), internal)
                .orElseThrow(() -> new ExtractorException(
                        binaryName + " is in no module's facts index; has its module been compiled?"));
    }

    // ---- rules -------------------------------------------------------------------------------

    private static Extraction guardIds(Spec s, Rule self, EvalContext ctx) {
        List<Extraction.Row> rows = new ArrayList<>();
        for (String id : ctx.rules().ids()) {
            Rule r = ctx.rules().rules().get(id);
            if (r == null) continue;
            Map<String, String> cols = new LinkedHashMap<>();
            cols.put("id", r.id());
            cols.put("kind", r.kind().id());
            cols.put("why", r.why());
            cols.put("instead", r.instead() == null ? "" : r.instead());
            cols.put("scope", r.scope().isEmpty() ? "*" : String.join(", ", r.scope()));
            cols.put("lane", Evaluators.laneOf(r).name().toLowerCase(Locale.ROOT));
            rows.add(new Extraction.Row(r.id(), cols));
        }
        return new Extraction(s.label(), null, rows);
    }

    // ---- markdown ----------------------------------------------------------------------------

    private static Extraction markdownTable(Spec s, EvalContext ctx) throws IOException, ExtractorException {
        String file = s.arg("file", "");
        String column = s.arg("column", "");
        List<String> lines = Files.readAllLines(existing(ctx, file));
        List<Extraction.Row> rows = new ArrayList<>();
        List<String> headers = null;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).strip();
            if (!l.startsWith("|")) {
                if (headers != null) break;
                continue;
            }
            List<String> cells = cells(l);
            if (headers == null) {
                if (i + 1 < lines.size()
                        && lines.get(i + 1).strip().matches("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?")) {
                    headers = cells;
                    i++;
                }
                continue;
            }
            int at = headers.indexOf(column);
            if (at < 0) {
                try {
                    at = Integer.parseInt(column);
                } catch (NumberFormatException e) {
                    throw new ExtractorException(
                            file + ": the first table has no column `" + column + "`; columns are " + headers);
                }
            }
            if (at >= cells.size()) continue;
            Map<String, String> cols = new LinkedHashMap<>();
            for (int c = 0; c < headers.size() && c < cells.size(); c++) cols.put(headers.get(c), cells.get(c));
            rows.add(new Extraction.Row(cells.get(at), cols));
        }
        if (headers == null) throw new ExtractorException(file + " has no Markdown table");
        return new Extraction(s.label(), file, rows);
    }

    private static List<String> cells(String tableLine) {
        String body = tableLine.strip();
        if (body.startsWith("|")) body = body.substring(1);
        if (body.endsWith("|")) body = body.substring(0, body.length() - 1);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length() && body.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
            } else if (c == '|') {
                out.add(cur.toString().strip());
                cur.setLength(0);
            } else cur.append(c);
        }
        out.add(cur.toString().strip());
        return out;
    }

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    private static Extraction markdownLinks(Spec s, EvalContext ctx) throws IOException, ExtractorException {
        String file = s.arg("file", "");
        String text = Files.readString(existing(ctx, file));
        List<Extraction.Row> rows = new ArrayList<>();
        Matcher m = LINK.matcher(text);
        while (m.find()) rows.add(row(m.group(2), "href", m.group(2), "text", m.group(1)));
        return new Extraction(s.label(), file, rows);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static Path existing(EvalContext ctx, String rel) throws ExtractorException {
        if (rel.isEmpty()) throw new ExtractorException("no file named");
        Path p = ctx.root().resolve(rel);
        if (!Files.isRegularFile(p)) throw new ExtractorException(rel + " does not exist");
        return p;
    }

    private static Extraction.Row row(String key, String... namesAndValues) {
        Map<String, String> cols = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) cols.put(namesAndValues[i], namesAndValues[i + 1]);
        return new Extraction.Row(key, cols);
    }
}
