// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.MethodFacts;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Renames a field (with its bean accessors) or a type, from javac's reference index: every span
 * javac resolved to the symbol is edited, in every Java source whose index describes it as it
 * stands. Everything else that spells the name (a Kotlin or Groovy source, a template, a string, a
 * services file) is a candidate in the {@link RenamePlan}, never an edit made unasked.
 */
final class SymbolRename {

    private SymbolRename() {}

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_PROTECTED = 0x0004;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_TRANSIENT = 0x0080;

    /** The workspace as one rename reads it: the sources, their index, the classes. */
    static final class Ctx {
        final GraphService.Workspace w;
        final Path root;
        final CodeGraph graph;
        final Modules modules;
        final List<String> javaFiles;
        private final Map<String, @Nullable String> texts = new HashMap<>();
        private final Map<String, List<ReferenceIndex.Span>> spans = new HashMap<>();
        private final Set<String> unindexed = new TreeSet<>();
        private @Nullable Map<String, GraphRename.Loaded> classes;

        Ctx(GraphService.Workspace w, CodeGraph graph) {
            this.w = w;
            this.root = w.root();
            this.graph = graph;
            this.modules = graph.modules();
            this.javaFiles = RenameProof.javaFiles(graph);
        }

        @Nullable
        String text(String rel) {
            return texts.computeIfAbsent(rel, r -> RenameText.read(root, r));
        }

        /** {@code rel}'s spans when its index describes it as it stands, else {@code null}. */
        @Nullable
        List<ReferenceIndex.Span> spans(String rel) {
            List<ReferenceIndex.Span> had = spans.get(rel);
            if (had != null) return had;
            if (unindexed.contains(rel)) return null;
            String text = text(rel);
            List<ReferenceIndex.Span> s = text == null ? null : ReferenceIndex.of(root, modules, rel, text);
            if (s == null) {
                unindexed.add(rel);
                return null;
            }
            spans.put(rel, s);
            return s;
        }

        /** The Java sources whose index describes them as they stand. */
        Set<String> indexed() {
            Set<String> out = new TreeSet<>();
            for (String rel : javaFiles) {
                if (spans(rel) != null) out.add(rel);
            }
            return out;
        }

        Map<String, GraphRename.Loaded> classes() throws IOException {
            if (classes == null) classes = GraphRename.classes(w.indexes());
            return classes;
        }

        /** Every JVM source of a source set in the modules {@code reaching} names ({@code null}: every module). */
        List<String> jvmSources(@Nullable Set<String> reaching) {
            List<String> out = new ArrayList<>();
            Set<String> seen = new TreeSet<>();
            for (GraphNode n : graph.nodes()) {
                String rel = n.sourceFile();
                if (!RenameText.jvmSource(rel) || rel.contains("/resources/") || !seen.add(rel)) continue;
                if (ClassSources.set(modules, rel) == null) continue;
                if (reaching != null && !reaching.contains(prefix(rel))) continue;
                out.add(rel);
            }
            out.sort(null);
            return out;
        }

        String prefix(String rel) {
            Modules.Module m = modules.of(rel);
            return m == null ? "" : m.prefix();
        }

        /** The modules that can use a symbol declared in {@code rel}'s module; {@code null} for every module. */
        @Nullable
        Set<String> reaching(String rel) {
            return w.reach().reaching(List.of(prefix(rel)), modules);
        }
    }

    // ---- types ----

    /** A workspace type: its graph node, binary name ({@code a.b.Outer$Inner}) and source. */
    record Type(GraphNode node, String binary, String rel) {
        String simple() {
            return binary.substring(Math.max(binary.lastIndexOf('.'), binary.lastIndexOf('$')) + 1);
        }

        String pkg() {
            int dot = binary.lastIndexOf('.');
            return dot < 0 ? "" : binary.substring(0, dot);
        }

        boolean nested() {
            return binary.indexOf('$', binary.lastIndexOf('.') + 1) >= 0;
        }

        String internal() {
            return binary.replace('.', '/');
        }
    }

    /** The workspace types {@code name} names: simple, nested ({@code Outer.Inner}) or qualified. */
    static List<Type> types(CodeGraph g, String name) {
        String dotted = name.strip().replace('$', '.');
        List<Type> out = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (GraphNode n : g.nodes()) {
            if (!Decl.isType(n) || !(n.extra().get(Linker.QUALIFIED_NAME) instanceof String q)) continue;
            String d = q.replace('$', '.');
            if (!(d.equals(dotted) || d.endsWith("." + dotted)) || !seen.add(q)) continue;
            out.add(new Type(n, q, n.sourceFile()));
        }
        out.sort(Comparator.comparing(Type::binary));
        return out;
    }

    static boolean typeExists(CodeGraph g, String binary) {
        for (GraphNode n : g.nodes()) {
            if (Decl.isType(n) && binary.equals(n.extra().get(Linker.QUALIFIED_NAME))) return true;
        }
        return false;
    }

    // ---- shared editing ----

    /** One span to replace: its offsets and the new text. */
    record Splice(int start, int end, String text) {}

    /** {@code text} with {@code splices} applied (non-overlapping), and the lines they touch. */
    static String apply(String text, List<Splice> splices, Set<Integer> lines) {
        List<Splice> sorted = new ArrayList<>(splices);
        sorted.sort(Comparator.comparingInt(Splice::start));
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        for (Splice s : sorted) {
            if (s.start() < at) continue;
            out.append(text, at, s.start()).append(s.text());
            at = s.end();
            lines.add(lineOf(text, s.start()));
        }
        out.append(text, at, text.length());
        return out.toString();
    }

    static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /**
     * The bound edits: in every indexed Java source, each span whose symbol {@code renames} maps is
     * replaced by the new simple name; counted by {@code kindOf}.
     */
    static void indexEdits(
            Ctx c, RenamePlan plan, Map<String, String> renames, Map<String, String> newNames, KindOf kindOf) {
        for (String rel : c.javaFiles) {
            List<ReferenceIndex.Span> spans = c.spans(rel);
            String text = c.text(rel);
            if (spans == null || text == null) continue;
            List<Splice> splices = new ArrayList<>();
            for (ReferenceIndex.Span sp : spans) {
                String to = newNames.get(sp.symbol());
                if (to == null) continue;
                splices.add(new Splice(sp.start(), sp.end(), to));
                plan.count(kindOf.kind(sp), 1);
            }
            if (splices.isEmpty()) continue;
            Set<Integer> lines = new TreeSet<>();
            String edited = apply(plan.current(rel, text), splices, lines);
            plan.edit(rel, text, edited, lines);
        }
    }

    interface KindOf {
        String kind(ReferenceIndex.Span sp);
    }

    /**
     * Candidates for the JVM sources javac did not index as they stand (Kotlin, Groovy, a Java
     * source of a module with no compiled classes) that spell one of {@code names} in code: each such
     * line must change, matched by name.
     */
    static void unindexedSources(Ctx c, RenamePlan plan, @Nullable Set<String> reaching, Map<String, String> names) {
        Set<String> indexed = c.indexed();
        List<String> files = c.jvmSources(reaching).stream()
                .filter(rel -> !indexed.contains(rel))
                .toList();
        Pattern any = anyWord(names.keySet());
        RenameText.scan(
                c.root,
                plan,
                files,
                any,
                RenameText.mapped(names),
                (rel, line, at, inString, inComment) -> {
                    if (inComment) return null;
                    String why = rel.endsWith(".java")
                            ? "Java source with no index as it stands, matched by name"
                            : (rel.endsWith(".groovy") ? "Groovy" : "Kotlin") + " source, matched by name";
                    return new RenameText.Verdict(true, RenamePlan.Grade.INFERRED, why);
                },
                Set.of());
    }

    static Pattern anyWord(Set<String> names) {
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return Pattern.compile("(?<![\\w$])(?:"
                + String.join("|", sorted.stream().map(Pattern::quote).toList()) + ")(?![\\w$])");
    }

    /** Comments in indexed Java sources and lines of docs that spell one of {@code names}: judgment. */
    static void prose(Ctx c, RenamePlan plan, List<String> textFiles, Map<String, String> names) {
        List<String> files = textFiles.stream()
                .filter(rel -> RenameText.doc(rel) || rel.endsWith(".java"))
                .toList();
        Set<String> indexed = c.indexed();
        RenameText.scan(
                c.root,
                plan,
                files,
                anyWord(names.keySet()),
                RenameText.mapped(names),
                (rel, line, at, inString, inComment) -> {
                    if (RenameText.doc(rel)) return new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, "doc");
                    if (!indexed.contains(rel)) return null;
                    return inComment ? new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, "comment") : null;
                },
                indexed);
    }

    // ---- field ----

    /**
     * Renames field {@code name} of {@code owner} to {@code to}, with its bean accessors unless {@code
     * accessors} is false: the field, {@code getX}/{@code isX}/{@code setX} and every override of
     * them, a record component's accessor.
     */
    static RenamePlan field(Ctx c, Type owner, String name, String to, boolean accessors, List<String> textFiles)
            throws IOException {
        String field = "F " + owner.binary() + "#" + name;
        Map<String, GraphRename.Loaded> classes = c.classes();
        GraphRename.Loaded loaded = classes.get(owner.internal());
        ClassFacts facts = loaded == null ? null : loaded.facts();
        if (facts != null && facts.fields().stream().anyMatch(f -> f.name().equals(to))) {
            throw new RenameRefusal("Not renamed: " + owner.simple() + " already has a field " + to + ".");
        }
        boolean record = facts != null && "java/lang/Record".equals(facts.superName());
        Map<String, String> renames = new LinkedHashMap<>();
        renames.put(field, "F " + owner.binary() + "#" + to);
        Map<String, String> newNames = new HashMap<>();
        newNames.put(field, to);
        Map<String, String> words = new LinkedHashMap<>();
        words.put(name, to);
        List<String> accessorNames = new ArrayList<>();
        if (accessors && facts != null) {
            String cap = RenameText.capitalize(name);
            String newCap = RenameText.capitalize(to);
            for (MethodFacts m : facts.methods()) {
                String renamed = null;
                int params = RenameNames.paramNames(m.desc()).size();
                if (m.name().equals("get" + cap) && params == 0) renamed = "get" + newCap;
                else if (m.name().equals("is" + cap) && params == 0 && m.desc().endsWith(")Z")) renamed = "is" + newCap;
                else if (m.name().equals("set" + cap) && params == 1) renamed = "set" + newCap;
                else if (record && m.name().equals(name) && params == 0) renamed = to;
                if (renamed == null || (m.access() & ACC_STATIC) != 0) continue;
                String clash = renamed;
                if (facts.methods().stream()
                        .anyMatch(o -> o.name().equals(clash) && o.desc().equals(m.desc()))) {
                    throw new RenameRefusal("Not renamed: " + owner.simple() + " already declares " + renamed + ".");
                }
                if (!m.name().equals(name)) accessorNames.add(m.name());
                words.put(m.name(), renamed);
                Map<String, Set<String>> family =
                        GraphRename.familyOf(classes, owner.internal(), m.name(), Set.of(m.desc()));
                for (Map.Entry<String, Set<String>> f : family.entrySet()) {
                    for (String d : f.getValue()) {
                        String sym = ReferenceIndex.method(f.getKey(), m.name(), d);
                        renames.put(sym, ReferenceIndex.method(f.getKey(), renamed, d));
                        newNames.put(sym, renamed);
                    }
                }
            }
        }
        String subject = owner.simple() + "." + name + " (field"
                + (accessorNames.isEmpty() ? "" : ", with " + String.join(", ", accessorNames)) + ")";
        RenamePlan plan = new RenamePlan(subject, to);
        indexEdits(c, plan, renames, newNames, sp -> {
            boolean ofField = sp.symbol().equals(field);
            return switch (sp.kind()) {
                case 'd' -> ofField ? "declaration" : "accessor declaration";
                case 'j' -> "doc link";
                case 'w' -> "write";
                default -> ofField ? "read" : "accessor call";
            };
        });
        if (plan.after.isEmpty()) {
            throw new RenameRefusal("Not renamed: javac's index records no declaration of " + owner.simple() + "."
                    + name + " as its sources stand; build, then rename.");
        }
        plan.symbols.putAll(renames);
        unindexedSources(c, plan, c.reaching(owner.rel()), words);
        fieldText(c, plan, owner, name, to, words, textFiles);
        prose(c, plan, textFiles, words);
        if (facts != null) fieldHazards(c, plan, owner, facts, name);
        return plan;
    }

    /** Non-code uses of a field's name: templates, config keys, JPQL and reflection strings, messages, SQL. */
    private static void fieldText(
            Ctx c,
            RenamePlan plan,
            Type owner,
            String name,
            String to,
            Map<String, String> words,
            List<String> textFiles) {
        Set<String> indexed = c.indexed();
        String ownerText = c.text(owner.rel());
        String configPrefix = ownerText == null ? null : configPrefix(ownerText);
        Map<String, String> configWords = new LinkedHashMap<>(words);
        configWords.put(RenameText.kebab(name), RenameText.kebab(to));
        // The environment variable Spring's relaxed binding reads for the key (PETCLINIC_VISITS_MAXPERDAY for
        // petclinic.visits.max-per-day): a compose file or workflow that sets it stops setting it once the
        // key is renamed, with no error anywhere.
        String key = configPrefix == null ? null : configPrefix + "." + RenameText.kebab(name);
        String envOld = key == null ? null : RenameText.envName(key);
        if (envOld != null) configWords.put(envOld, RenameText.envName(configPrefix + "." + RenameText.kebab(to)));
        List<String> files = textFiles.stream()
                .filter(rel -> !RenameText.doc(rel) && !RenameText.jvmSource(rel) || rel.endsWith(".java"))
                .toList();
        Pattern pattern = anyWord(configWords.keySet());
        RenameText.scan(
                c.root,
                plan,
                files,
                pattern,
                RenameText.mapped(configWords),
                (rel, line, at, inString, inComment) -> {
                    if (envOld != null && line.startsWith(envOld, at) && !rel.endsWith(".java")) {
                        return new RenameText.Verdict(
                                true, RenamePlan.Grade.INFERRED, "environment variable for " + key);
                    }
                    if (rel.endsWith(".java")) {
                        if (!indexed.contains(rel) || !inString) return null;
                        if (Pattern.compile(
                                                "@Query|createQuery|createNativeQuery|\\b(?i:select|update|delete)\\b.*\\b(?i:from|set)\\b")
                                        .matcher(line)
                                        .find()
                                && at > 0
                                && line.charAt(at - 1) == '.') {
                            return new RenameText.Verdict(true, RenamePlan.Grade.INFERRED, "JPQL path");
                        }
                        if (PINS.matcher(line).find()) {
                            return new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, "pins the old name");
                        }
                        if (REFLECTION.matcher(line).find()) {
                            return new RenameText.Verdict(true, RenamePlan.Grade.INFERRED, "reflection string");
                        }
                        if (wholeLiteral(line, at, line.length() - at)
                                && BINDING.matcher(line).find()) {
                            return new RenameText.Verdict(
                                    true, RenamePlan.Grade.INFERRED, "bean property name in a string");
                        }
                        return new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, "string");
                    }
                    if (RenameText.template(rel)) {
                        String head = line.substring(0, at);
                        int open = Math.max(
                                Math.max(head.lastIndexOf("${"), head.lastIndexOf("*{")), head.lastIndexOf("@{"));
                        int msg = head.lastIndexOf("#{");
                        int close = head.lastIndexOf('}');
                        if (open > close && open > msg) {
                            return new RenameText.Verdict(true, RenamePlan.Grade.INFERRED, "template bean path");
                        }
                        if (msg > close)
                            return new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, "template message key");
                        // A field name passed to a fragment as a string ('telephone') binds a form field too.
                        if (at > 0
                                && line.charAt(at - 1) == '\''
                                && line.indexOf('\'', at) > at
                                && line.contains("th:")) {
                            return new RenameText.Verdict(true, RenamePlan.Grade.INFERRED, "template field name");
                        }
                        if (Pattern.compile("(?:path|field)\\s*=\\s*\"[^\"]*$")
                                .matcher(head)
                                .find()) {
                            return new RenameText.Verdict(true, RenamePlan.Grade.INFERRED, "form field path");
                        }
                        return new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, "template text");
                    }
                    String ext = RenameText.ext(rel);
                    if (ext.equals("sql"))
                        return new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, "SQL column");
                    if (configPrefix != null && (ext.equals("properties") || ext.equals("yml") || ext.equals("yaml"))) {
                        String compact = line.replace(" ", "");
                        if (compact.contains(configPrefix + ".") || ext.startsWith("y")) {
                            return new RenameText.Verdict(
                                    true, RenamePlan.Grade.INFERRED, "config key of " + configPrefix);
                        }
                    }
                    if (ext.equals("properties") && rel.contains("messages")) {
                        return new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, "message key");
                    }
                    if (RenameText.classList(rel)) return null;
                    return new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, ext + " text");
                },
                Set.of());
    }

    /** Calls that read a bean property or field by its name in a string: renamed with it or broken. */
    private static final Pattern REFLECTION =
            Pattern.compile("getDeclaredField|getField\\(|getDeclaredMethod|getMethod\\(|"
                    + "ReflectionTestUtils|PropertyDescriptor|BeanWrapper|PropertyAccessor|getPropertyValue|setPropertyValue|"
                    + "hasProperty");

    /** Spring MVC and validation calls that bind a request parameter or an error to a bean property by name. */
    private static final Pattern BINDING =
            Pattern.compile("\\.param\\(|attributeHasFieldErrors|hasFieldErrors|rejectValue|getFieldError|"
                    + "attributeHasFieldErrorCode");

    /** Annotations whose string keeps a name on purpose: a column, a JSON or request name. */
    private static final Pattern PINS = Pattern.compile("@(?:Column|JoinColumn|JsonProperty|JsonAlias|SerializedName|"
            + "RequestParam|PathVariable|Param|Value)\\b");

    /** Whether the match at {@code at} is a whole string literal: {@code "telephone"}. */
    static boolean wholeLiteral(String line, int at, int rest) {
        if (at == 0 || line.charAt(at - 1) != '"') return false;
        int end = line.indexOf('"', at);
        return end > at && rest > 0 && line.substring(at, end).matches("[\\w$-]+");
    }

    private static final Pattern CONFIG_PROPERTIES =
            Pattern.compile("@ConfigurationProperties\\s*\\(\\s*(?:(?:prefix|value)\\s*=\\s*)?\"([^\"]+)\"");

    /** The prefix of {@code @ConfigurationProperties("…")} on a type, else {@code null}. */
    static @Nullable String configPrefix(String text) {
        Matcher m = CONFIG_PROPERTIES.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * A field rename that changes behaviour with no compile error: a JPA column named after the field,
     * a JSON property named after it, a serialized form. Each with the pin that keeps the old name.
     * A JPA column is a shared-surface name the graph's anchors model; until they answer for it, the
     * pin is offered here (see JK-Q6VJ6).
     */
    private static void fieldHazards(Ctx c, RenamePlan plan, Type owner, ClassFacts facts, String name) {
        String text = c.text(owner.rel());
        if (text == null) return;
        FieldFacts f = facts.fields().stream()
                .filter(x -> x.name().equals(name))
                .findFirst()
                .orElse(null);
        if (f == null || (f.access() & ACC_STATIC) != 0) return;
        String rel = plan.moves.getOrDefault(owner.rel(), owner.rel());
        String current = plan.current(owner.rel(), text);
        List<ReferenceIndex.Span> spans = c.spans(owner.rel());
        int declLine = -1;
        if (spans != null) {
            for (ReferenceIndex.Span sp : spans) {
                if (sp.kind() == 'd' && sp.symbol().equals("F " + owner.binary() + "#" + name)) declLine = sp.line();
            }
        }
        if (declLine < 0) return;
        String[] lines = current.split("\n", -1);
        int first = annotationsStart(lines, declLine);
        String annotations = String.join("\n", List.of(lines).subList(first - 1, declLine));
        String indent = lines[first - 1].substring(
                0, lines[first - 1].length() - lines[first - 1].stripLeading().length());
        boolean entity = Pattern.compile(
                        "@(?:jakarta\\.persistence\\.|javax\\.persistence\\.)?(?:Entity|Embeddable|MappedSuperclass)\\b")
                .matcher(text)
                .find();
        boolean transientField = (f.access() & ACC_TRANSIENT) != 0 || annotations.contains("@Transient");
        if (entity && !transientField) {
            Matcher col =
                    Pattern.compile("@(?:Column|JoinColumn)\\s*\\(([^)]*)\\)").matcher(annotations);
            boolean named = col.find() && col.group(1).contains("name");
            boolean relation = Pattern.compile("@(?:OneToMany|ManyToMany|Embedded|ElementCollection)\\b")
                    .matcher(annotations)
                    .find();
            if (!named && !relation) {
                String ns = text.contains("javax.persistence") ? "javax" : "jakarta";
                List<RenamePlan.Change> pin = new ArrayList<>();
                // An unnamed @Column there already is named in place: a second one would not compile.
                int existing = -1;
                for (int i = first; i <= declLine; i++) {
                    if (Pattern.compile("@(?:jakarta\\.persistence\\.|javax\\.persistence\\.)?Column\\b")
                            .matcher(lines[i - 1])
                            .find()) {
                        existing = i;
                    }
                }
                if (existing > 0) {
                    String was = lines[existing - 1];
                    String named2 = was.contains("Column(")
                            ? was.replaceFirst("Column\\(\\s*", "Column(name = \"" + name + "\", ")
                                    .replace(", )", ")")
                            : was.replaceFirst("Column\\b", "Column(name = \"" + name + "\")");
                    pin.add(new RenamePlan.LineChange(rel, existing, was, named2));
                } else {
                    pin.add(new RenamePlan.Insert(rel, first, indent + "@Column(name = \"" + name + "\")"));
                    RenamePlan.Insert imp = importLine(current, rel, ns + ".persistence.Column");
                    if (imp != null) pin.add(imp);
                }
                plan.hazards.add(new RenamePlan.Hazard(
                        owner.simple() + " is a JPA entity and " + name + " names no column, so the column it maps"
                                + " becomes the new name's and the existing data no longer maps.",
                        pin));
            }
        }
        if (text.contains("com.fasterxml.jackson") && !annotations.contains("@JsonProperty")) {
            List<RenamePlan.Change> pin = new ArrayList<>();
            pin.add(new RenamePlan.Insert(rel, first, indent + "@JsonProperty(\"" + name + "\")"));
            RenamePlan.Insert imp = importLine(current, rel, "com.fasterxml.jackson.annotation.JsonProperty");
            if (imp != null) pin.add(imp);
            plan.hazards.add(new RenamePlan.Hazard(
                    owner.simple() + " is serialized with Jackson, so its JSON property " + name + " is renamed.",
                    pin));
        }
        if (serializable(c, facts) && (f.access() & ACC_TRANSIENT) == 0) {
            plan.hazards.add(new RenamePlan.Hazard(
                    owner.simple() + " is Serializable: data serialized before the rename no longer fills the field.",
                    List.of()));
        }
    }

    /** Whether the class or a workspace supertype implements {@code java.io.Serializable}. */
    static boolean serializable(Ctx c, ClassFacts facts) {
        try {
            Map<String, GraphRename.Loaded> classes = c.classes();
            List<String> todo = new ArrayList<>(List.of(facts.name()));
            Set<String> seen = new TreeSet<>();
            for (int i = 0; i < todo.size(); i++) {
                GraphRename.Loaded l = classes.get(todo.get(i));
                if (l == null || !seen.add(todo.get(i))) continue;
                if (l.facts().interfaces().contains("java/io/Serializable")) return true;
                if (l.facts().superName() != null) todo.add(l.facts().superName());
                todo.addAll(l.facts().interfaces());
            }
        } catch (IOException unreadable) {
            return false;
        }
        return false;
    }

    /** The first line (1-based) of the annotations directly above declaration line {@code decl}. */
    static int annotationsStart(String[] lines, int decl) {
        int first = decl;
        for (int i = decl - 2; i >= 0; i--) {
            String t = lines[i].strip();
            if (t.startsWith("@") || t.endsWith(")") && first < decl && !t.endsWith(";") && !t.endsWith("}")) {
                first = i + 1;
            } else {
                break;
            }
        }
        return first;
    }

    /** The import of {@code fqcn} a file lacks, inserted after its last import or its package line; {@code null} when it has it. */
    static RenamePlan.@Nullable Insert importLine(String text, String rel, String fqcn) {
        String pkg = fqcn.substring(0, fqcn.lastIndexOf('.'));
        if (Pattern.compile("(?m)^\\s*import\\s+(?:" + Pattern.quote(fqcn) + "|" + Pattern.quote(pkg) + "\\.\\*)\\s*;")
                .matcher(text)
                .find()) {
            return null;
        }
        int at = importAt(text);
        return new RenamePlan.Insert(rel, at, "import " + fqcn + ";");
    }

    /** The 1-based line before which a new import goes: after the last import, else after the package line. */
    static int importAt(String text) {
        String[] lines = text.split("\n", -1);
        int lastImport = -1;
        int pkg = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.startsWith("import ")) lastImport = i;
            else if (t.startsWith("package ") && pkg < 0) pkg = i;
            else if (t.startsWith("public ") || t.startsWith("class ") || t.startsWith("@") && lastImport >= 0) break;
        }
        if (lastImport >= 0) return lastImport + 2;
        return pkg >= 0 ? pkg + 2 : 1;
    }

    // ---- type ----

    /** Renames type {@code t} to simple name {@code to}: every use javac resolved, its file, and candidates. */
    static RenamePlan type(Ctx c, Type t, String to, List<String> textFiles) throws IOException {
        String newBinary =
                t.binary().substring(0, t.binary().length() - t.simple().length()) + to;
        if (typeExists(c.graph, newBinary)) {
            throw new RenameRefusal("Not renamed: " + newBinary.replace('$', '.') + " already exists.");
        }
        Map<String, String> renames = typeSymbols(c, t.binary(), newBinary);
        Map<String, String> newNames = Map.of("T " + t.binary(), to);
        clashes(c, t, to);
        RenamePlan plan = new RenamePlan(t.binary().replace('$', '.') + " (" + kind(t) + ")", to);
        indexEdits(c, plan, renames, newNames, sp -> switch (sp.kind()) {
            case 'd' -> "declaration";
            case 'j' -> "doc link";
            default -> "use";
        });
        if (plan.after.isEmpty()) {
            throw new RenameRefusal("Not renamed: javac's index records no declaration of "
                    + t.binary().replace('$', '.') + " as its sources stand; build, then rename.");
        }
        String file = t.rel().substring(t.rel().lastIndexOf('/') + 1);
        if (!t.nested() && file.equals(t.simple() + ".java")) {
            String moved = t.rel().substring(0, t.rel().length() - file.length()) + to + ".java";
            if (RenameStore.inside(c.root, moved).toFile().exists()) {
                throw new RenameRefusal("Not renamed: " + moved + " already exists.");
            }
            String text = c.text(t.rel());
            if (text != null) plan.move(t.rel(), plan.current(t.rel(), text), moved);
        }
        plan.symbols.putAll(renames);
        Map<String, String> words = Map.of(t.simple(), to);
        unindexedSources(c, plan, c.reaching(t.rel()), words);
        typeText(c, plan, t.binary(), newBinary, textFiles);
        simpleNameText(c, plan, t, to, textFiles);
        namedTests(c, plan, t, to);
        modelAttributes(c, plan, t, to, textFiles);
        prose(c, plan, textFiles, words);
        return plan;
    }

    static String kind(Type t) {
        String k = Decl.kind(t.node());
        return k == null ? "type" : k;
    }

    /** The type and every type nested in it, old symbol → new: the proof's symbols. */
    static Map<String, String> typeSymbols(Ctx c, String oldBinary, String newBinary) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("T " + oldBinary, "T " + newBinary);
        for (GraphNode n : c.graph.nodes()) {
            if (Decl.isType(n)
                    && n.extra().get(Linker.QUALIFIED_NAME) instanceof String q
                    && q.startsWith(oldBinary + "$")) {
                out.put("T " + q, "T " + newBinary + q.substring(oldBinary.length()));
            }
        }
        return out;
    }

    /**
     * Refuses a type rename that would make a use mean another type: a file using the type that
     * already names another type {@code to}, or sits in a package that declares one.
     */
    private static void clashes(Ctx c, Type t, String to) {
        String symbol = "T " + t.binary();
        List<String> clashing = new ArrayList<>();
        for (String rel : c.javaFiles) {
            List<ReferenceIndex.Span> spans = c.spans(rel);
            if (spans == null || spans.stream().noneMatch(s -> s.symbol().equals(symbol))) continue;
            for (ReferenceIndex.Span s : spans) {
                if (s.symbol().startsWith("T ")
                        && !s.symbol().equals(symbol)
                        && ReferenceIndex.simpleName(s.symbol()).equals(to)) {
                    clashing.add(rel + ":" + s.line() + " ("
                            + s.symbol().substring(2).replace('$', '.') + ")");
                    break;
                }
            }
        }
        if (!clashing.isEmpty()) {
            throw new RenameRefusal("Not renamed: these files use " + t.simple() + " and already name another " + to
                    + ", so the new name would be ambiguous there: "
                    + String.join(", ", clashing.subList(0, Math.min(5, clashing.size()))));
        }
    }

    /**
     * A type's full name in non-code files and strings: a services file, Spring's imports and
     * factories and {@code jk.toml} name it in a format that declares a class (declared); an XML or
     * config file, and a Java string, by name (inferred). Every one must change.
     */
    static void typeText(Ctx c, RenamePlan plan, String oldBinary, String newBinary, List<String> textFiles) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(oldBinary, newBinary);
        String dotted = oldBinary.replace('$', '.');
        if (!dotted.equals(oldBinary)) names.put(dotted, newBinary.replace('$', '.'));
        String slashed = oldBinary.replace('.', '/');
        if (slashed.contains("/")) names.put(slashed, newBinary.replace('.', '/'));
        Set<String> indexed = c.indexed();
        Pattern p = Pattern.compile("(?<![\\w$.])(?:"
                + String.join(
                        "|",
                        names.keySet().stream()
                                .sorted(Comparator.comparingInt(String::length).reversed())
                                .map(Pattern::quote)
                                .toList())
                + ")(?![\\w])");
        RenameText.scan(
                c.root,
                plan,
                textFiles,
                p,
                RenameText.mapped(names),
                (rel, line, at, inString, inComment) -> {
                    if (RenameText.classList(rel))
                        return new RenameText.Verdict(true, RenamePlan.Grade.DECLARED, "class list");
                    if (RenameText.jvmSource(rel)) {
                        if (inComment) return null;
                        if (indexed.contains(rel) && !inString) return null;
                        return new RenameText.Verdict(
                                true, RenamePlan.Grade.INFERRED, inString ? "class name in a string" : "full name");
                    }
                    if (RenameText.doc(rel)) return new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, "doc");
                    return new RenameText.Verdict(
                            true, RenamePlan.Grade.DECLARED, "full class name in " + RenameText.ext(rel));
                },
                Set.of());
        // A services file named after an interface moves with it.
        for (String rel : textFiles) {
            int at = rel.indexOf("META-INF/services/");
            if (at < 0) continue;
            String name = rel.substring(at + "META-INF/services/".length());
            if (!name.equals(oldBinary) && !name.equals(dotted)) continue;
            String moved = rel.substring(0, rel.length() - name.length()) + newBinary;
            plan.candidates.add(new RenamePlan.Candidate(
                    true,
                    RenamePlan.Grade.DECLARED,
                    "services file named after it",
                    rel,
                    1,
                    "",
                    List.of(new RenamePlan.Move(rel, moved)),
                    "moves to " + moved));
        }
    }

    /** The simple name in templates (judgment) and in JPQL strings (must: an entity name). */
    private static void simpleNameText(Ctx c, RenamePlan plan, Type t, String to, List<String> textFiles) {
        Set<String> indexed = c.indexed();
        List<String> files = textFiles.stream()
                .filter(rel -> rel.endsWith(".java") && indexed.contains(rel) || RenameText.template(rel))
                .toList();
        RenameText.scan(
                c.root,
                plan,
                files,
                RenameText.word(t.simple()),
                m -> to,
                (rel, line, at, inString, inComment) -> {
                    if (RenameText.template(rel))
                        return new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, "template text");
                    if (!inString || inComment) return null;
                    if (Pattern.compile("@Query|createQuery|\\b(?i:from|join|update)\\s+$")
                                    .matcher(line.substring(0, at))
                                    .find()
                            || Pattern.compile("(?i)\\b(?:from|join|update)\\s+" + Pattern.quote(t.simple()) + "\\b")
                                    .matcher(line)
                                    .find()) {
                        return new RenameText.Verdict(true, RenamePlan.Grade.INFERRED, "JPQL entity name");
                    }
                    return new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, "string");
                },
                Set.of());
    }

    /**
     * Spring MVC names a model attribute it was not given a name for after its type: a controller
     * handling an {@code Owner} puts it in the model as {@code owner}, and after the rename as
     * {@code customer}, so a view reading {@code ${owner}} breaks with no compile error. Flagged when
     * a controller uses the type with no {@code @ModelAttribute("…")} and a template reads the old name.
     */
    private static void modelAttributes(Ctx c, RenamePlan plan, Type t, String to, List<String> textFiles) {
        String oldName =
                Character.toLowerCase(t.simple().charAt(0)) + t.simple().substring(1);
        String newName = Character.toLowerCase(to.charAt(0)) + to.substring(1);
        List<String> controllers = new ArrayList<>();
        for (String rel : c.javaFiles) {
            String text = c.text(rel);
            List<ReferenceIndex.Span> spans = c.spans(rel);
            if (text == null
                    || spans == null
                    || !Pattern.compile("@(?:[\\w.]+\\.)?(?:Rest)?Controller\\b")
                            .matcher(text)
                            .find()) continue;
            if (spans.stream().anyMatch(sp -> sp.symbol().equals("T " + t.binary()))) controllers.add(rel);
        }
        if (controllers.isEmpty()) return;
        Pattern read =
                Pattern.compile("\\$\\{" + Pattern.quote(oldName) + "\\b|th:object=\"\\$\\{" + Pattern.quote(oldName));
        boolean viewsRead = textFiles.stream().filter(RenameText::template).anyMatch(rel -> {
            String text = RenameText.read(c.root, rel);
            return text != null && read.matcher(text).find();
        });
        if (!viewsRead) return;
        plan.hazards.add(new RenamePlan.Hazard(
                "Spring MVC names an unnamed model attribute after its type, so views reading ${" + oldName + "} get ${"
                        + newName + "} instead: name it with @ModelAttribute(\"" + oldName + "\") where "
                        + String.join(
                                ", ",
                                controllers.stream()
                                        .map(r -> r.substring(r.lastIndexOf('/') + 1))
                                        .toList())
                        + " bind or return it.",
                List.of()));
    }

    /** Test classes named after the type ({@code OwnerTests}): judgment, renamed in a call of their own. */
    private static void namedTests(Ctx c, RenamePlan plan, Type t, String to) {
        for (GraphNode n : c.graph.nodes()) {
            if (!Decl.isType(n) || !(n.extra().get(Linker.QUALIFIED_NAME) instanceof String q)) continue;
            if (c.modules.sourceSet(n.sourceFile()) != Modules.SourceSet.TEST) continue;
            String simple = q.substring(Math.max(q.lastIndexOf('.'), q.lastIndexOf('$')) + 1);
            for (String suffix : List.of("Tests", "Test", "IT", "IntegrationTests", "IntegrationTest")) {
                if (!simple.equals(t.simple() + suffix)) continue;
                plan.candidates.add(new RenamePlan.Candidate(
                        false,
                        RenamePlan.Grade.INFERRED,
                        "test named after it",
                        n.sourceFile(),
                        Math.max(1, Decl.line(n)),
                        "",
                        List.of(),
                        "rename " + simple + " " + to + suffix));
            }
        }
    }

    // ---- members, for visibility checks ----

    /** Whether the member {@code symbol} names ({@code F}/{@code M}) is package-private, from the compiled classes. */
    static boolean packagePrivate(Map<String, GraphRename.Loaded> classes, String symbol) {
        int hash = symbol.indexOf('#');
        if (hash < 0) return false;
        GraphRename.Loaded l = classes.get(symbol.substring(2, hash).replace('.', '/'));
        if (l == null) return false;
        String member = symbol.substring(hash + 1);
        int flags = -1;
        if (symbol.startsWith("F ")) {
            for (FieldFacts f : l.facts().fields()) if (f.name().equals(member)) flags = f.access();
        } else {
            int paren = member.indexOf('(');
            String name = member.substring(0, paren);
            String desc = member.substring(paren);
            for (MethodFacts m : l.facts().methods())
                if (m.name().equals(name) && m.desc().equals(desc)) flags = m.access();
        }
        return flags >= 0 && (flags & (ACC_PUBLIC | ACC_PROTECTED | ACC_PRIVATE)) == 0;
    }

    /** {@code map} ordered by key. */
    static <V> Map<String, V> sorted(Map<String, V> map) {
        return new TreeMap<>(map);
    }
}
