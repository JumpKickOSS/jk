// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import cc.jumpkick.guard.rules.LoadError.Severity;
import cc.jumpkick.guard.schema.ExtractorVocabulary;
import cc.jumpkick.guard.schema.GeneratedMarkers;
import cc.jumpkick.guard.schema.KeySpec;
import cc.jumpkick.guard.schema.KeyType;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.guard.schema.Kind.InsteadRule;
import cc.jumpkick.guard.schema.Kind.KeyGroup;
import cc.jumpkick.guard.schema.SchemaText;
import cc.jumpkick.guard.validate.EngineValidations;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

/**
 * Loads the root {@code jk-guards.toml} against the closed schema. Every problem is collected with
 * its file and line; nothing is thrown past the first mistake, so one load reports the whole file.
 *
 * <p>What is checked here is the shape: a known kind, known keys with the right TOML type, the
 * required keys and one-of groups, {@code why} and every {@code allow.reason} present, ids unique
 * and lower-kebab. What is <em>not</em> checked here is the tree — signature resolution, owner
 * probes and must-bite need the facts and belong to the evaluators.
 */
public final class GuardRules {

    /** The id is the diagnostic {@code code}; keep it greppable and shell-safe. */
    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** Above this a rule is a program, not a rule. Warned, not refused. */
    static final int TOKEN_BUDGET = 60;

    private GuardRules() {}

    /** Load the root rule file, or an empty set when it does not exist. */
    public static LoadResult load(Path root, GuardsConfig config) {
        Path file = GuardsPresence.rulesFile(root);
        if (!Files.isRegularFile(file)) return new LoadResult(new RuleSet(Map.of(), Map.of(), config), List.of());
        List<LoadError> problems = new ArrayList<>();
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            problems.add(new LoadError(Severity.ERROR, file, 1, null, "cannot read: " + e.getMessage()));
            return new LoadResult(new RuleSet(Map.of(), Map.of(), config), problems);
        }
        Map<String, Rule> rules = loadText(file, text, RuleSource.Layer.ROOT, problems);
        checkTagVocabulary(root, file, rules, problems);
        checkGeneratedMarkers(root, file, rules, problems);
        Map<String, String> digests =
                Map.of(root.relativize(file).toString().replace('\\', '/'), Hashing.sha256Hex(text));
        return new LoadResult(new RuleSet(rules, digests, config), problems);
    }

    /**
     * A {@code tiers} rule's {@code tag}/{@code tagged} must be tags the manifests declare (in {@code
     * [test]} or a profile): a tag no tier owns is a test no build runs, so naming one is a load error.
     */
    private static void checkTagVocabulary(Path root, Path file, Map<String, Rule> rules, List<LoadError> problems) {
        Set<String> vocabulary = null;
        for (Rule r : rules.values()) {
            if (r.kind() != Kind.TIERS) continue;
            List<String> named = new ArrayList<>();
            String tag = r.table().getString("tag");
            if (tag != null) named.add(tag);
            TomlArray tagged = r.table().getArray("tagged");
            if (tagged != null) for (int i = 0; i < tagged.size(); i++) named.add(String.valueOf(tagged.get(i)));
            if (named.isEmpty()) continue;
            if (vocabulary == null) vocabulary = TestTags.vocabulary(root);
            for (String n : named) {
                if (vocabulary.contains(n)) continue;
                problems.add(new LoadError(
                        Severity.ERROR,
                        file,
                        r.source().line(),
                        r.id(),
                        "tag `" + n + "` is not in the test-tag vocabulary "
                                + (vocabulary.isEmpty()
                                        ? "(no manifest declares [test] include-tags/exclude-tags or a profile's tags)"
                                        : vocabulary)
                                + "; a tag no tier owns is a test no build runs"));
            }
        }
    }

    /**
     * A {@code generated} rule's {@code into} file, when it exists, must carry the rule's markers: a
     * block nobody delimited is a rendering with nowhere to go, so naming one is a load error. An
     * absent file is the evaluation's to report.
     */
    private static void checkGeneratedMarkers(Path root, Path file, Map<String, Rule> rules, List<LoadError> problems) {
        for (Rule r : rules.values()) {
            if (r.kind() != Kind.GENERATED) continue;
            String into = String.valueOf(r.table().getString("into"));
            Path target = root.resolve(into);
            if (!Files.isRegularFile(target)) continue;
            String name =
                    r.table().isString("markers") ? String.valueOf(r.table().getString("markers")) : r.id();
            try {
                if (GeneratedMarkers.find(Files.readAllLines(target, StandardCharsets.UTF_8), name) == null) {
                    problems.add(new LoadError(
                            Severity.ERROR,
                            file,
                            r.source().line(),
                            r.id(),
                            into + " has no `" + name + ":start` … `" + name + ":end` marker pair; add the two"
                                    + " comment lines where the block belongs"));
                }
            } catch (IOException e) {
                problems.add(
                        new LoadError(Severity.ERROR, file, r.source().line(), r.id(), into + ": " + e.getMessage()));
            }
        }
    }

    /** Parse one file's text. Package-private so tests and, later, pack fragments reach it. */
    static Map<String, Rule> loadText(Path file, String text, RuleSource.Layer layer, List<LoadError> problems) {
        Map<String, Rule> rules = new LinkedHashMap<>();
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) {
            for (TomlParseError e : toml.errors()) {
                problems.add(new LoadError(
                        Severity.ERROR, file, e.position().line(), null, "TOML syntax: " + e.getMessage()));
            }
            return rules;
        }
        for (String top : toml.keySet()) {
            if (!"guards".equals(top)) {
                problems.add(new LoadError(
                        Severity.ERROR,
                        file,
                        lineOf(toml, List.of(top)),
                        null,
                        "unknown top-level table `" + top + "` — every rule is a [guards.<id>] table"));
            }
        }
        TomlTable guards = toml.getTable("guards");
        if (guards == null) return rules;
        for (String key : guards.keySet()) {
            List<String> path = List.of("guards", key);
            int line = lineOf(toml, path);
            if ("extends".equals(key)) {
                problems.add(
                        new LoadError(
                                Severity.ERROR,
                                file,
                                line,
                                null,
                                "[guards] extends (rule packs) is not supported yet — remove it until packs ship; a silent no-op is not allowed"));
                continue;
            }
            Object value = guards.get(path.subList(1, 2));
            if (!(value instanceof TomlTable table)) {
                problems.add(new LoadError(
                        Severity.ERROR,
                        file,
                        line,
                        null,
                        "[guards] unknown key `" + key + "` — the only keys here are [guards.<id>] tables"));
                continue;
            }
            if (!ID.matcher(key).matches()) {
                problems.add(new LoadError(
                        Severity.ERROR,
                        file,
                        line,
                        key,
                        "rule id must be lower-case letters, digits and hyphens (it is the diagnostic code)"));
                continue;
            }
            if (EngineValidations.CODES.contains(key)) {
                problems.add(new LoadError(
                        Severity.ERROR,
                        file,
                        line,
                        key,
                        "`" + key + "` is an engine validation's code (jk guard explain " + key
                                + "); a rule cannot take it"));
                continue;
            }
            if (rules.containsKey(key)) {
                problems.add(new LoadError(Severity.ERROR, file, line, key, "duplicate rule id"));
                continue;
            }
            Rule rule = readRule(key, table, new RuleSource(file, line, layer), toml, problems);
            if (rule != null) rules.put(key, rule);
            int tokens = tokenCount(text, key);
            if (tokens > TOKEN_BUDGET) {
                problems.add(new LoadError(
                        Severity.WARNING,
                        file,
                        line,
                        key,
                        "rule is " + tokens + " tokens; the budget is " + TOKEN_BUDGET
                                + " — a rule this long is a program, consider a guard test"));
            }
        }
        return rules;
    }

    private static @Nullable Rule readRule(
            String id, TomlTable t, RuleSource source, TomlParseResult doc, List<LoadError> problems) {
        Path file = source.file();
        int start = problems.size();
        String kindId = t.isString("kind") ? t.getString("kind") : null;
        if (kindId == null) {
            problems.add(err(file, source.line(), id, "missing required key `kind`"));
            return null;
        }
        Kind kind = Kind.byId(kindId).orElse(null);
        if (kind == Kind.TEST) {
            problems.add(err(
                    file,
                    source.line(),
                    id,
                    "kind = \"test\" is not written in TOML: a guard test is a @Guard method in a"
                            + " @GuardSuite class under src/guard/java (jk guard explain --schema guard-test)"));
            return null;
        }
        if (kind == null) {
            problems.add(err(
                    file,
                    lineOf(doc, List.of("guards", id, "kind")),
                    id,
                    "unknown kind `" + kindId + "`; kinds are " + String.join(", ", kindIds())));
            return null;
        }
        Map<String, KeySpec> specs = new LinkedHashMap<>();
        for (KeySpec s : SchemaText.commonKeys()) specs.put(s.name(), s);
        for (KeySpec s : kind.keys()) specs.put(s.name(), s);
        if (kind.instead() != InsteadRule.ABSENT) {
            specs.put(
                    "instead",
                    new KeySpec(
                            "instead",
                            KeyType.STRING,
                            kind.instead() == InsteadRule.REQUIRED,
                            List.of(),
                            "the sanctioned alternative an agent applies"));
        }
        for (String key : t.keySet()) {
            KeySpec spec = specs.get(key);
            int line = lineOf(doc, List.of("guards", id, key));
            if (spec == null) {
                problems.add(err(
                        file,
                        line,
                        id,
                        "unknown key `" + key + "` for kind " + kind.id() + "; keys are "
                                + String.join(", ", specs.keySet())));
                continue;
            }
            String shape = shapeError(t, key, spec);
            if (shape != null) problems.add(err(file, line, id, shape));
        }
        for (KeySpec spec : specs.values()) {
            if (spec.required() && !t.contains(spec.name())) {
                problems.add(err(
                        file, source.line(), id, "missing required key `" + spec.name() + "` (" + spec.doc() + ")"));
            }
        }
        for (KeyGroup g : kind.groups()) {
            int present = 0;
            for (String k : g.keys()) if (t.contains(k)) present++;
            if (present == 0) {
                problems.add(err(file, source.line(), id, "one of " + String.join(", ", g.keys()) + " is required"));
            } else if (g.exactlyOne() && present > 1) {
                problems.add(
                        err(file, source.line(), id, "exactly one of " + String.join(", ", g.keys()) + " may be set"));
            }
        }
        if (kind == Kind.PARITY) {
            for (String side : List.of("left", "right")) {
                String p = ExtractorVocabulary.extractorProblem(side, t.getTable(side));
                if (p != null) problems.add(err(file, lineOf(doc, List.of("guards", id, side)), id, p));
            }
        }
        if (kind == Kind.GENERATED) {
            String p = ExtractorVocabulary.extractorProblem("source", t.getTable("source"));
            if (p != null) problems.add(err(file, lineOf(doc, List.of("guards", id, "source")), id, p));
            String tp = ExtractorVocabulary.templateProblem(t.getTable("template"));
            if (tp != null) problems.add(err(file, lineOf(doc, List.of("guards", id, "template")), id, tp));
        }
        List<Allow> allow = readAllow(id, t, doc, file, problems);
        if (problems.size() > start) return null;
        return new Rule(
                id,
                kind,
                Objects.requireNonNull(t.getString("why"), "why"),
                t.isString("instead") ? t.getString("instead") : derivedInstead(kind, t),
                stringOrList(t, "scope"),
                t.isString("source-set") ? Objects.requireNonNull(t.getString("source-set")) : "main",
                allow,
                Boolean.TRUE.equals(t.getBoolean("baseline")),
                t.isString("fixture") ? t.getString("fixture") : null,
                t,
                source);
    }

    /** The sanctioned alternative a kind can spell out itself when the author left it implicit. */
    static @Nullable String derivedInstead(Kind kind, TomlTable t) {
        if (kind == Kind.TIERS) {
            String suite = t.getString("suite");
            String tag = t.getString("tag");
            if (suite != null) return "move the class to src/" + suite + "/java";
            if (tag != null) return "add @Tag(\"" + tag + "\") to the class";
            return null;
        }
        if (kind == Kind.CLASSES) {
            TomlTable should = t.getTable("should");
            if (should == null) return null;
            List<String> parts = new ArrayList<>();
            for (String k : should.keySet()) parts.add(k + " = " + should.get(k));
            return "make the class satisfy: " + String.join(", ", parts);
        }
        if (kind == Kind.ANNOTATE) {
            String required = t.isString("require") ? t.getString("require") : null;
            if (required == null) return null;
            String simple = required.substring(Math.max(required.lastIndexOf('.'), required.lastIndexOf('$')) + 1);
            String on = t.isString("on") ? String.valueOf(t.getString("on")) : "";
            return "annotate the " + (on.equals("package") ? "package-info.java" : on.isEmpty() ? "element" : on)
                    + " with @" + simple;
        }
        return null;
    }

    private static List<Allow> readAllow(
            String id, TomlTable t, TomlParseResult doc, Path file, List<LoadError> problems) {
        List<Allow> out = new ArrayList<>();
        if (!t.isArray("allow")) return out;
        TomlArray arr = Objects.requireNonNull(t.getArray("allow"));
        int line = lineOf(doc, List.of("guards", id, "allow"));
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof TomlTable e)) {
                problems.add(err(file, line, id, "allow entries are inline tables { in = …, reason = … }"));
                continue;
            }
            String in = e.isString("in") ? e.getString("in") : null;
            String reason = e.isString("reason") ? e.getString("reason") : null;
            if (in == null || in.isBlank())
                problems.add(err(file, line, id, "allow entry " + (i + 1) + " has no `in`"));
            if (reason == null || reason.isBlank()) {
                problems.add(err(
                        file,
                        line,
                        id,
                        "allow entry " + (i + 1)
                                + " has no `reason` — an exemption without a reason is a suppression"));
            }
            for (String k : e.keySet()) {
                if (!"in".equals(k) && !"reason".equals(k))
                    problems.add(err(file, line, id, "allow entry " + (i + 1) + ": unknown key `" + k + "`"));
            }
            if (in != null && reason != null) out.add(new Allow(in, reason));
        }
        return out;
    }

    /** The TOML shape of {@code key} against its spec, or {@code null} when it fits. */
    static @Nullable String shapeError(TomlTable t, String key, KeySpec spec) {
        Object v = t.get(List.of(key));
        boolean ok =
                switch (spec.type()) {
                    case STRING -> v instanceof String;
                    case STRING_LIST -> isStringList(v);
                    case STRING_OR_LIST -> v instanceof String || isStringList(v);
                    case BOOL -> v instanceof Boolean;
                    case INT -> v instanceof Long;
                    case NUMBER -> v instanceof Long || v instanceof Double;
                    case NUMBER_OR_TABLE -> v instanceof Long || v instanceof Double || v instanceof TomlTable;
                    case TABLE -> v instanceof TomlTable;
                    case TABLE_LIST -> v instanceof TomlArray a && allOf(a, TomlTable.class);
                };
        if (!ok) return "`" + key + "` must be " + describe(spec.type());
        if (!spec.values().isEmpty() && v instanceof String s && !spec.values().contains(s)) {
            return "`" + key + "` must be one of " + String.join(", ", spec.values()) + ", not `" + s + "`";
        }
        return null;
    }

    private static boolean isStringList(@Nullable Object v) {
        return v instanceof TomlArray a && allOf(a, String.class);
    }

    private static boolean allOf(TomlArray a, Class<?> type) {
        for (int i = 0; i < a.size(); i++) if (!type.isInstance(a.get(i))) return false;
        return true;
    }

    private static String describe(KeyType type) {
        return switch (type) {
            case STRING -> "a string";
            case STRING_LIST -> "a list of strings";
            case STRING_OR_LIST -> "a string or a list of strings";
            case BOOL -> "true or false";
            case INT -> "an integer";
            case NUMBER -> "a number";
            case NUMBER_OR_TABLE -> "a number or a table of numbers per language";
            case TABLE -> "an inline table";
            case TABLE_LIST -> "a list of inline tables";
        };
    }

    private static List<String> stringOrList(TomlTable t, String key) {
        if (t.isString(key)) return List.of(t.getString(key));
        if (t.isArray(key)) {
            List<String> out = new ArrayList<>();
            TomlArray a = Objects.requireNonNull(t.getArray(key));
            for (int i = 0; i < a.size(); i++) out.add(Objects.requireNonNull(a.getString(i)));
            return out;
        }
        return List.of();
    }

    /** Whitespace-separated tokens of the rule's table in the source text, header included. */
    static int tokenCount(String text, String id) {
        String header = "[guards." + id + "]";
        int at = text.indexOf(header);
        if (at < 0) return 0;
        int end = text.indexOf("\n[", at + header.length());
        String body = end < 0 ? text.substring(at) : text.substring(at, end);
        int tokens = 0;
        for (String line : body.split("\n")) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            tokens += s.split("\\s+").length;
        }
        return tokens;
    }

    private static int lineOf(TomlParseResult doc, List<String> path) {
        TomlPosition p = doc.inputPositionOf(path);
        return p == null ? 1 : p.line();
    }

    private static LoadError err(Path file, int line, String id, String message) {
        return new LoadError(Severity.ERROR, file, line, id, message);
    }

    private static List<String> kindIds() {
        List<String> ids = new ArrayList<>();
        for (Kind k : Kind.values()) ids.add(k.id());
        return ids;
    }
}
