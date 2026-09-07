// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.schema;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.model.Scope;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * The closed extractor and template vocabulary of {@code parity} and {@code generated}. An extractor
 * turns one source of truth into rows keyed by an element; a template renders rows into the block a
 * {@code generated} rule keeps in a file. The names are the whole language: there is no expression
 * form, so a rule's read set is known from its keys alone.
 */
public final class ExtractorVocabulary {

    /** Extractor name → its arguments, as {@code explain --schema} prints them. */
    public static final Map<String, String> EXTRACTORS = extractors();

    /** Template name → what it renders. */
    public static final Map<String, String> TEMPLATES = templates();

    private ExtractorVocabulary() {}

    private static Map<String, String> extractors() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("workspace-modules", "(file = the root manifest) the [workspace] modules");
        m.put("manifest-deps", "{ module, scope = main | … | all } a module's declared dependencies as coordinates");
        m.put("lock-artifacts", "(file = the lockfile) every locked artifact as name → version");
        m.put("catalog-aliases", "(file) the aliases of a version catalog ([libraries] or [catalog])");
        m.put("toml-keys", "{ file, table = \"\" } the keys of one TOML table");
        m.put("yaml-keys", "{ file, path = \"\" } the keys of one YAML mapping (dot path)");
        m.put("properties-keys", "(file) the keys of a .properties file");
        m.put("json-keys", "{ file, path = \"\" } the keys of one JSON object (dot path)");
        m.put("regex", "{ file, pattern, group = 1 } every match's group, comments blanked where the file is code");
        m.put(
                "gradle-includes",
                "(file = settings.gradle.kts) the project directories a Gradle settings script includes");
        m.put("enum-constants", "(class) the constants of an enum, from the facts index");
        m.put("static-finals", "(class) the static final constants of a class as name → value");
        m.put("guard-ids", "(true) every rule of this rules file: id, kind, why");
        m.put("guard-kinds", "(true) every rule kind: kind, substrate, lane, summary");
        m.put("guard-schemas", "(true | kind) the keys of every kind, or one: kind, key, required, type, meaning");
        m.put("markdown-table", "{ file, column } the cells of one column of the first table in a Markdown file");
        m.put("markdown-links", "(file) the destination of every [text](href) link in a Markdown file");
        return Map.copyOf(m);
    }

    private static Map<String, String> templates() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("table", "(columns) a Markdown table with those columns");
        m.put("list", "(true | column) a Markdown bullet list of the element or one column");
        m.put("arrow-chain", "(true) the elements on one line joined by ` -> `");
        m.put("toml-array", "(name) `name = [\"a\", \"b\"]`");
        m.put("code-block", "(language) a fenced code block, one element per line");
        return Map.copyOf(m);
    }

    /** The primary argument each extractor takes when spelled as a bare string. */
    public static String primaryArg(String name) {
        return switch (name) {
            case "manifest-deps" -> "module";
            case "enum-constants", "static-finals" -> "class";
            case "guard-ids", "guard-kinds" -> "all";
            case "guard-schemas" -> "kind";
            default -> "file";
        };
    }

    /** A spec's arguments by name: a bare value is the extractor's primary argument. */
    public static Map<String, String> args(String name, @Nullable Object value) {
        Map<String, String> args = new LinkedHashMap<>();
        if (value instanceof TomlTable t) {
            for (String k : t.keySet()) args.put(k, String.valueOf(t.get(k)));
        } else if (value != null) {
            args.put(primaryArg(name), String.valueOf(value));
        }
        return args;
    }

    /**
     * Why an extractor spec cannot load, or {@code null}: not exactly one key, an unknown name, a
     * missing argument, a pattern that does not compile. Judged at load so the rule file, not a
     * build, is what goes red.
     */
    public static @Nullable String extractorProblem(String key, @Nullable TomlTable spec) {
        if (spec == null || spec.keySet().size() != 1) {
            return key + " must be an inline table naming exactly one extractor; extractors are "
                    + String.join(", ", extractorNames());
        }
        String name = spec.keySet().iterator().next();
        if (!EXTRACTORS.containsKey(name)) {
            return key + ": unknown extractor `" + name + "`; extractors are " + String.join(", ", extractorNames());
        }
        Map<String, String> a = args(name, spec.get(name));
        switch (name) {
            case "guard-schemas" -> {
                String kind = a.getOrDefault("kind", "all");
                if (EnvValues.parseBool(kind).isEmpty()
                        && !kind.equals("all")
                        && Kind.byId(kind).isEmpty())
                    return key + ": guard-schemas kind `" + kind + "` is not a rule kind; kinds are " + kindIds();
            }
            case "manifest-deps" -> {
                if (a.get("module") == null) return key + ": manifest-deps needs `module`";
                String scope = a.getOrDefault("scope", "main");
                if (!scope.equals("all") && scopeOf(scope) == null)
                    return key + ": manifest-deps scope `" + scope + "` is not a dependency scope";
            }
            case "toml-keys", "yaml-keys", "json-keys", "properties-keys", "catalog-aliases", "markdown-links" -> {
                if (a.get("file") == null) return key + ": " + name + " needs `file`";
            }
            case "markdown-table" -> {
                if (a.get("file") == null || a.get("column") == null)
                    return key + ": markdown-table needs `file` and `column`";
            }
            case "regex" -> {
                if (a.get("file") == null || a.get("pattern") == null)
                    return key + ": regex needs `file` and `pattern`";
                try {
                    Pattern p = Pattern.compile(a.get("pattern"));
                    int group = Integer.parseInt(a.getOrDefault("group", "1"));
                    if (group < 0 || group > p.matcher("").groupCount())
                        return key + ": regex group " + group + " is not in the pattern";
                } catch (PatternSyntaxException e) {
                    return key + ": regex pattern does not compile: " + e.getDescription();
                } catch (NumberFormatException e) {
                    return key + ": regex `group` must be a number";
                }
            }
            case "enum-constants", "static-finals" -> {
                if (a.get("class") == null) return key + ": " + name + " needs `class`";
            }
            default -> {}
        }
        return null;
    }

    /** The scope an extractor argument names, by id or by manifest table, or {@code null}. */
    public static @Nullable Scope scopeOf(String id) {
        for (Scope sc : Scope.values())
            if (sc.canonical().equals(id) || sc.tomlSection().equals(id)) return sc;
        return null;
    }

    /** Why a template spec cannot load, or {@code null}. */
    public static @Nullable String templateProblem(@Nullable TomlTable spec) {
        if (spec == null || spec.keySet().size() != 1) {
            return "template must be an inline table naming exactly one template; templates are "
                    + String.join(", ", templateNames());
        }
        String name = spec.keySet().iterator().next();
        if (!TEMPLATES.containsKey(name)) {
            return "template: unknown template `" + name + "`; templates are " + String.join(", ", templateNames());
        }
        Object v = spec.get(name);
        return switch (name) {
            case "table" ->
                v instanceof TomlArray a && a.size() > 0
                        ? null
                        : "template: table needs its columns, e.g. { table = [\"id\", \"kind\", \"why\"] }";
            case "toml-array" -> v instanceof String ? null : "template: toml-array needs the array's name";
            case "code-block" -> v instanceof String ? null : "template: code-block needs a language (\"\" for none)";
            default -> null;
        };
    }

    /** The extractor names, in vocabulary order. */
    public static Set<String> extractorNames() {
        return EXTRACTORS.keySet();
    }

    public static Set<String> templateNames() {
        return TEMPLATES.keySet();
    }

    private static String kindIds() {
        StringBuilder sb = new StringBuilder();
        for (Kind k : Kind.values()) sb.append(sb.isEmpty() ? "" : ", ").append(k.id());
        return sb.toString();
    }
}
