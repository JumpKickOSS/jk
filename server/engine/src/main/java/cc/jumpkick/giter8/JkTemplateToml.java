// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldKind;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormException;
import cc.jumpkick.form.Option;
import cc.jumpkick.form.Step;
import cc.jumpkick.host.Log;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * {@code .jk-template.toml} at a Giter8 template root: picker metadata and the template's own
 * questions. Path {@code <lang>/<framework>/<name>.g8} is source of truth; the file must match.
 *
 * <p>Each {@code [[ask]]} is one field of the template's form step, in order. {@code internal}
 * names the {@code default.properties} entries that are not questions. {@link #load} also runs the
 * catalogue lint: every property is reserved, asked or internal, so a template cannot carry an
 * untyped knob no surface knows how to ask for.
 */
public final class JkTemplateToml {

    public static final String FILE_NAME = ".jk-template.toml";

    /** The step id of a template's own questions, after jk's shared step. */
    public static final String STEP_ID = "template";

    /**
     * Properties jk sets itself: the shared step's answers and what the apply derives. A template
     * may not ask for one, and needs no {@code internal} entry to default one.
     */
    public static final Set<String> RESERVED = Set.of(
            "lang",
            "template",
            "layout",
            "name",
            "organization",
            "package",
            "group",
            "java",
            "jk_languages",
            "jk_layout",
            "jk_version",
            "name_normalized",
            Giter8ShortNames.LAYOUT_SIMPLE,
            "verbatim");

    /** The kinds a template may ask: each has a Giter8 binding. */
    static final Set<FieldKind> TEMPLATE_KINDS =
            Set.of(FieldKind.TEXT, FieldKind.BOOL, FieldKind.CHOICE, FieldKind.MULTI);

    private static final Set<String> TOP_KEYS =
            Set.of("language", "framework", "name", "description", "layouts", "ask", "internal");
    private static final Set<String> ASK_KEYS =
            Set.of("key", "label", "help", "kind", "options", "default", "required", "pattern", "when", "advanced");
    private static final Set<String> OPTION_KEYS = Set.of("id", "label", "help");

    public record Meta(
            String language,
            String framework,
            String name,
            String description,
            List<String> layouts,
            List<Field> asks,
            List<String> internal) {

        public Meta {
            language = language.strip().toLowerCase(Locale.ROOT);
            framework = framework.strip().toLowerCase(Locale.ROOT);
            name = name.strip().toLowerCase(Locale.ROOT);
            if (description == null) description = "";
            layouts = layouts == null || layouts.isEmpty()
                    ? List.of(Giter8ShortNames.LAYOUT_TRADITIONAL, Giter8ShortNames.LAYOUT_SIMPLE)
                    : List.copyOf(layouts);
            asks = asks == null ? List.of() : List.copyOf(asks);
            internal = internal == null ? List.of() : List.copyOf(internal);
        }

        public Meta(String language, String framework, String name, String description, List<String> layouts) {
            this(language, framework, name, description, layouts, List.of(), List.of());
        }

        /** The template's form step, or empty when it asks nothing. */
        public Optional<Step> step() {
            return asks.isEmpty() ? Optional.empty() : Optional.of(new Step(STEP_ID, "Template options", asks));
        }

        /**
         * The template's step as a form of its own, checked whole: each {@code when} names earlier
         * asks only. Empty when it asks nothing.
         */
        public Optional<Form> form() {
            return step().map(s -> new Form(STEP_ID, name, List.of(s)));
        }
    }

    /** Refusals already logged: a catalogue is rescanned often, and one warning per fault is enough. */
    private static final Set<String> REFUSED = ConcurrentHashMap.newKeySet();

    private JkTemplateToml() {}

    public static Path file(Path templateRoot) {
        return templateRoot.resolve(FILE_NAME);
    }

    /**
     * Reads and lints the template at {@code templateRoot}.
     *
     * @return empty when the template has no {@code .jk-template.toml}
     * @throws IllegalArgumentException when the file is malformed or the template fails lint; the
     *     message names the file and the property
     */
    public static Optional<Meta> load(Path templateRoot) throws IOException {
        Path f = file(templateRoot);
        if (!Files.isRegularFile(f)) return Optional.empty();
        Meta meta = parse(Files.readString(f, StandardCharsets.UTF_8), f.toString());
        lint(meta, defaultProperties(templateRoot), f.toString());
        return Optional.of(meta);
    }

    /**
     * {@link #load} for a catalogue scan: a template that cannot be read or fails lint is left out
     * of the catalogue, and the reason is logged once per message so the author sees which
     * property to fix.
     */
    public static Optional<Meta> loadOrRefuse(Path templateRoot) {
        try {
            return load(templateRoot);
        } catch (IOException | IllegalArgumentException e) {
            String message = "template refused: " + e.getMessage();
            if (REFUSED.add(message)) Log.warn(message);
            return Optional.empty();
        }
    }

    /**
     * The template's {@code default.properties}, beside the descriptor or under {@code src/main/g8}
     * (where {@link Giter8Apply} reads it); empty when there is none.
     */
    static Map<String, String> defaultProperties(Path templateRoot) throws IOException {
        Path propsFile = templateRoot.resolve("default.properties");
        if (!Files.isRegularFile(propsFile)) propsFile = templateRoot.resolve("src/main/g8/default.properties");
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(propsFile)) return out;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(propsFile)) {
            p.load(in);
        }
        for (String key : new TreeSet<>(p.stringPropertyNames())) out.put(key, p.getProperty(key));
        return out;
    }

    public static Meta parse(String toml, String displayPath) {
        TomlParseResult r = Toml.parse(toml);
        if (r.hasErrors()) {
            throw new IllegalArgumentException(
                    displayPath + " has invalid TOML: " + r.errors().getFirst().getMessage());
        }
        for (String key : r.keySet()) {
            if (!TOP_KEYS.contains(key)) {
                throw new IllegalArgumentException(displayPath + ": unknown key '" + key + "'");
            }
        }
        String language = requireIdent(stringOf(r, "language", displayPath), "language", displayPath);
        if (!isLang(language)) {
            throw new IllegalArgumentException(displayPath + ": language must be java, kotlin, or groovy");
        }
        String framework = requireIdent(stringOf(r, "framework", displayPath), "framework", displayPath);
        String name = requireIdent(stringOf(r, "name", displayPath), "name", displayPath);
        String description = stringOf(r, "description", displayPath);
        if (description == null) description = "";
        List<String> layouts = layoutsOf(arrayOf(r, "layouts", displayPath), displayPath);
        List<Field> asks = asksOf(r, displayPath);
        List<String> internal = stringsOf(arrayOf(r, "internal", displayPath), "internal", displayPath);
        return new Meta(language, framework, name, description.strip(), layouts, asks, internal);
    }

    /**
     * The catalogue lint. Every property in {@code default.properties} is reserved, asked or
     * internal; an ask is not reserved and not internal, and its default agrees with any value
     * {@code default.properties} also gives it; every {@code internal} entry is a property; no
     * {@code <key>_<option>} binding shadows another property; and the asks build one form step,
     * so each {@code when} names earlier asks only and each default is one of its options.
     *
     * @throws IllegalArgumentException naming {@code displayPath} and the property at fault
     */
    public static void lint(Meta meta, Map<String, String> properties, String displayPath) {
        Set<String> asked = new LinkedHashSet<>();
        for (Field f : meta.asks()) asked.add(f.key());
        Set<String> internal = new LinkedHashSet<>();
        for (String key : meta.internal()) {
            if (!internal.add(key)) throw fault(displayPath, "internal lists '" + key + "' twice");
            if (RESERVED.contains(key)) {
                throw fault(displayPath, "internal lists '" + key + "', which is reserved: jk sets it");
            }
            if (asked.contains(key)) {
                throw fault(displayPath, "internal lists '" + key + "', which is also an [[ask]]");
            }
            if (!properties.containsKey(key)) {
                throw fault(displayPath, "internal lists '" + key + "', which default.properties does not set");
            }
        }
        for (String key : properties.keySet()) {
            if (RESERVED.contains(key) || asked.contains(key) || internal.contains(key)) continue;
            throw fault(
                    displayPath,
                    "default.properties sets '" + key
                            + "', which is neither reserved nor asked: declare it with an [[ask]], or list it under"
                            + " internal = [...] when it is not a question");
        }
        Set<String> taken = new LinkedHashSet<>(RESERVED);
        taken.addAll(properties.keySet());
        taken.addAll(asked);
        for (Field f : meta.asks()) {
            if (RESERVED.contains(f.key())) {
                throw fault(displayPath, "[[ask]] '" + f.key() + "' is reserved: jk asks for it in its own step");
            }
            for (String binding : bindingNames(f)) {
                if (!taken.add(binding)) {
                    throw fault(
                            displayPath,
                            "[[ask]] '" + f.key() + "' binds '" + binding + "', which is already a property");
                }
            }
            String given = properties.get(f.key());
            if (given != null && f.defaultValue() == null) {
                throw fault(
                        displayPath,
                        "[[ask]] '" + f.key() + "' has no default, but default.properties sets it; move the value to"
                                + " the [[ask]]'s default");
            }
            Object literal = TemplateAnswers.literalDefault(f);
            if (given != null
                    && literal != null
                    && !TemplateAnswers.text(literal).equals(given)) {
                throw fault(
                        displayPath,
                        "[[ask]] '" + f.key() + "' defaults to '" + TemplateAnswers.text(literal)
                                + "', but default.properties sets it to '" + given + "'");
            }
        }
        try {
            meta.form();
        } catch (FormException e) {
            throw fault(displayPath, String.valueOf(e.getMessage()));
        }
    }

    /** The {@code <key>_<option>} properties a {@code choice} or {@code multi} ask sets. */
    static List<String> bindingNames(Field f) {
        if (f.kind() != FieldKind.CHOICE && f.kind() != FieldKind.MULTI) return List.of();
        List<String> out = new ArrayList<>();
        for (Option o : f.options()) out.add(bindingName(f.key(), o.id()));
        return out;
    }

    static String bindingName(String key, String option) {
        return key + "_" + option.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /**
     * @return empty when language/framework/name disagree with the directory path
     */
    public static Optional<Meta> matching(Meta meta, String language, String framework, String name) {
        if (meta.language().equals(language)
                && meta.framework().equals(framework)
                && meta.name().equals(name)) {
            return Optional.of(meta);
        }
        return Optional.empty();
    }

    static boolean isLang(String s) {
        return "java".equals(s) || "kotlin".equals(s) || "groovy".equals(s);
    }

    static boolean isKebab(String s) {
        return s != null && s.matches("[a-z][a-z0-9-]*");
    }

    static @Nullable String nameFromDir(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        String n = fileName;
        if (n.endsWith(".g8")) n = n.substring(0, n.length() - 3);
        n = n.toLowerCase(Locale.ROOT);
        return isKebab(n) ? n : null;
    }

    private static List<Field> asksOf(TomlParseResult r, String displayPath) {
        Object raw = r.get("ask");
        if (raw == null) return List.of();
        if (!(raw instanceof TomlArray arr)) throw fault(displayPath, "ask must be [[ask]] tables");
        List<Field> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof TomlTable table)) throw fault(displayPath, "ask must be [[ask]] tables");
            out.add(askOf(table, i + 1, displayPath));
        }
        return out;
    }

    private static Field askOf(TomlTable t, int ordinal, String displayPath) {
        Object rawKey = t.get("key");
        String key = rawKey instanceof String s ? s : null;
        String where = key == null ? "[[ask]] #" + ordinal : "[[ask]] '" + key + "'";
        if (key == null) throw fault(displayPath, where + " has no key");
        for (String k : t.keySet()) {
            if (!ASK_KEYS.contains(k)) throw fault(displayPath, where + ": unknown attribute '" + k + "'");
        }
        try {
            String label = askString(t, "label", where, displayPath);
            if (label == null) throw fault(displayPath, where + " has no label");
            String kindId = askString(t, "kind", where, displayPath);
            if (kindId == null) throw fault(displayPath, where + " has no kind");
            FieldKind kind = FieldKind.of(kindId);
            if (!TEMPLATE_KINDS.contains(kind)) {
                throw fault(displayPath, where + ": a template asks text, bool, choice or multi, not " + kind.id());
            }
            Field.Builder b = Field.of(key, label, kind)
                    .help(askString(t, "help", where, displayPath))
                    .pattern(askString(t, "pattern", where, displayPath))
                    .when(askString(t, "when", where, displayPath));
            Object options = t.get("options");
            if (options != null) b.options(optionsOf(options, where, displayPath));
            Object def = t.get("default");
            if (def != null) b.defaultValue(literalOf(def, where, displayPath));
            Object required = t.get("required");
            if (required != null) b.required(askBool(required, "required", where, displayPath));
            Object advanced = t.get("advanced");
            if (advanced != null) b.advanced(askBool(advanced, "advanced", where, displayPath));
            return b.build();
        } catch (FormException e) {
            throw fault(displayPath, where + ": " + e.getMessage());
        }
    }

    private static List<Option> optionsOf(Object raw, String where, String displayPath) {
        if (!(raw instanceof TomlArray arr)) throw fault(displayPath, where + ": options must be an array");
        List<Option> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            Object o = arr.get(i);
            if (o instanceof String id) {
                out.add(Option.of(id));
            } else if (o instanceof TomlTable table) {
                for (String k : table.keySet()) {
                    if (!OPTION_KEYS.contains(k)) {
                        throw fault(displayPath, where + ": unknown option attribute '" + k + "'");
                    }
                }
                String id = askString(table, "id", where, displayPath);
                if (id == null) throw fault(displayPath, where + ": an option table needs an id");
                out.add(new Option(
                        id,
                        askString(table, "label", where, displayPath),
                        askString(table, "help", where, displayPath)));
            } else {
                throw fault(displayPath, where + ": each option is a string or an { id, label, help } table");
            }
        }
        return out;
    }

    private static Object literalOf(Object raw, String where, String displayPath) {
        if (raw instanceof String || raw instanceof Boolean) return raw;
        if (raw instanceof TomlArray arr) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                if (!(arr.get(i) instanceof String s)) {
                    throw fault(displayPath, where + ": a list default holds option ids only");
                }
                out.add(s);
            }
            return out;
        }
        throw fault(displayPath, where + ": default must be a string, a boolean or a list of option ids");
    }

    private static @Nullable String askString(TomlTable t, String key, String where, String displayPath) {
        Object v = t.get(key);
        if (v == null) return null;
        if (!(v instanceof String s)) throw fault(displayPath, where + ": " + key + " must be a string");
        return s;
    }

    private static boolean askBool(Object v, String key, String where, String displayPath) {
        if (!(v instanceof Boolean b)) throw fault(displayPath, where + ": " + key + " must be true or false");
        return b;
    }

    private static @Nullable String stringOf(TomlParseResult r, String key, String displayPath) {
        Object v = r.get(key);
        if (v == null) return null;
        if (!(v instanceof String s)) throw fault(displayPath, key + " must be a string");
        return s;
    }

    private static @Nullable TomlArray arrayOf(TomlParseResult r, String key, String displayPath) {
        Object v = r.get(key);
        if (v == null) return null;
        if (!(v instanceof TomlArray a)) throw fault(displayPath, key + " must be an array");
        return a;
    }

    private static List<String> stringsOf(@Nullable TomlArray arr, String key, String displayPath) {
        if (arr == null) return List.of();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof String s) || s.isBlank()) {
                throw fault(displayPath, key + " holds property names only");
            }
            out.add(s.strip());
        }
        return out;
    }

    private static IllegalArgumentException fault(String displayPath, String message) {
        return new IllegalArgumentException(displayPath + ": " + message);
    }

    private static String requireIdent(@Nullable String raw, String key, String displayPath) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(displayPath + ": missing " + key);
        }
        String v = raw.strip().toLowerCase(Locale.ROOT);
        if (!isKebab(v)) {
            throw new IllegalArgumentException(displayPath + ": " + key + " must be kebab-case");
        }
        return v;
    }

    private static List<String> layoutsOf(@Nullable TomlArray arr, String displayPath) {
        if (arr == null) return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            String raw = arr.get(i) instanceof String s ? s : null;
            if (raw == null || raw.isBlank()) continue;
            if (!Giter8ShortNames.isKnownLayoutToken(raw)) {
                throw new IllegalArgumentException(displayPath + ": unknown layout " + raw);
            }
            String n = Giter8ShortNames.normalizeLayout(raw);
            if (!out.contains(n)) out.add(n);
        }
        return out;
    }
}
