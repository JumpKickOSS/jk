// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Budget;
import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.codegraph.extract.Extractors;
import cc.jumpkick.config.ManifestOutline;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Renames a method, every override and implementation of it, every call and method reference to it
 * and its doc links, in one operation. The compiled classes say which calls are its: a call names
 * its owner and descriptor, and the hierarchy says whether that reaches the method. They keep a
 * count per caller and target, not each call's line, so each file's uses are found in its source
 * and edited only where the count of uses of the name there is exactly what the classes record and
 * none of them is another method of the name. A file or method body where the two disagree, or where
 * another method of the name is called too, is listed for the caller to edit, never guessed at.
 */
final class GraphRename {

    private GraphRename() {}

    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_BRIDGE = 0x0040;
    private static final int ACC_SYNTHETIC = 0x1000;

    /** The most line numbers one file lists. */
    static final int LISTED_LINES = 40;

    /** The widest quoted line, in characters. */
    static final int QUOTE_WIDTH = 160;

    private static final Set<String> OBJECT_METHODS = Set.of("equals", "hashCode", "toString", "clone", "finalize");

    private static final Set<String> KEYWORDS = Set.of(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "true",
            "false",
            "null",
            "var",
            // Not record: Java restricts it only as a type name, and it is no Kotlin keyword. A
            // method may be called record. yield stays: an unqualified call of it does not compile.
            "yield",
            "fun",
            "val",
            "when",
            "object",
            "is",
            "in",
            "as",
            "typealias");

    /** One class's facts and the module directory its index came from. */
    private record Loaded(ClassFacts facts, String prefix) {}

    /** One use the compiled classes record: whether it is the family's, a declaration, its line, arity and count. */
    private record Rec(boolean mine, boolean decl, int line, int arity, int count) {}

    /** What the compiled classes record in one file, and whether it declares the family's method or another. */
    private static final class Tally {
        final List<Rec> recs = new ArrayList<>();
        boolean declaresFamily;
        boolean declaresOther;

        int family() {
            return recs.stream().filter(Rec::mine).mapToInt(Rec::count).sum();
        }
    }

    /**
     * Renames {@code address} ({@code Type.method}, every overload, or {@code Type.method(int,
     * String)} for one) to {@code to} under {@code root}; returns the answer.
     */
    static String rename(
            Path root, CodeGraph graph, List<GraphBuild.ClassIndex> indexes, String address, String to, int budget)
            throws IOException {
        if (!identifier(to)) throw new IllegalArgumentException("'" + to + "' is not a method name");
        String refused = edited(indexes);
        if (refused != null) return refused;
        Map<String, Loaded> classes = new LinkedHashMap<>();
        for (GraphBuild.ClassIndex c : indexes) {
            for (ClassFacts cf : FactsFormat.read(c.index()).classes().values()) {
                classes.putIfAbsent(cf.name(), new Loaded(cf, c.modulePrefix()));
            }
        }
        Hierarchy h = new Hierarchy(classes);
        Target target = target(classes, address);
        if (target.error() != null) return RenameHints.withUnbuilt(target.error(), unbuilt(graph, indexes, null));
        if (target.name().equals(to)) return "'" + address + "' is already named " + to + ".";
        Map<String, Set<String>> family = family(classes, h, target);
        String clash = clash(classes, h, family, target.name(), to);
        if (clash != null) return clash;
        Run run = new Run(root, graph, classes, h, target, family, to);
        run.tally();
        run.unchecked(indexes);
        run.edit();
        if (run.annotated > run.overriding) {
            return "Not renamed: " + display(target) + " overrides a method outside the workspace (@Override with no"
                    + " supertype here declaring it), so renaming it would break that override.";
        }
        if (run.edited.isEmpty() && run.unsettled.isEmpty() && run.unchecked.isEmpty()) {
            return "Nothing renamed: the compiled classes record no declaration of " + display(target) + " in a source"
                    + " file.";
        }
        run.docLinks();
        run.prose = RenameMentions.renameAll(
                root, run.old, to, run.familyTypes, run.edited, run.changedLines, run.textFiles);
        run.mentions = RenameMentions.find(run.root, run.old, run.familyTypes, run.edited, run.textFiles);
        for (Map.Entry<String, String> e : run.edited.entrySet()) {
            Files.writeString(root.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
        }
        return Budget.lines(run.answer(), budget, "Raise the budget for every line");
    }

    /** One rename's working state: what the classes record per file, and the edits chosen from it. */
    private static final class Run {
        final Path root;
        final CodeGraph graph;
        final Map<String, Loaded> classes;
        final Hierarchy h;
        final Target target;
        final Map<String, Set<String>> family;
        final String old;
        final String to;
        final Map<String, List<String>> byName = new HashMap<>();
        final Set<String> jvmFiles = new TreeSet<>();
        final Set<String> familyTypes = new TreeSet<>();
        final Map<String, Tally> tallies = new TreeMap<>();
        final Set<String> unplaced = new TreeSet<>();
        final Map<String, List<String>> unchecked = new TreeMap<>();
        final Map<String, String> edited = new TreeMap<>();
        final Map<String, List<Integer>> changedLines = new TreeMap<>();
        final Map<String, List<String>> unsettled = new TreeMap<>();
        /** Files edited from the compile's reference index; their doc links were renamed with them. */
        final Set<String> indexed = new TreeSet<>();
        /** Prose that still names the old method: comment lines in edited files, doc lines naming its type. */
        List<String> mentions = List.of();

        final Set<String> textFiles = new TreeSet<>();

        int overriding;
        int annotated;
        int calls;
        int declarations;
        int docs;
        int prose;

        Run(
                Path root,
                CodeGraph graph,
                Map<String, Loaded> classes,
                Hierarchy h,
                Target target,
                Map<String, Set<String>> family,
                String to) {
            this.root = root;
            this.graph = graph;
            this.classes = classes;
            this.h = h;
            this.target = target;
            this.family = family;
            this.old = target.name();
            this.to = to;
            for (GraphNode n : graph.nodes()) {
                String rel = n.sourceFile();
                if (RenameMentions.prose(rel)) textFiles.add(rel);
                if (rel.isEmpty() || !Extractors.isJvmSource(rel) || !jvmFiles.add(rel)) continue;
                byName.computeIfAbsent(rel.substring(rel.lastIndexOf('/') + 1), k -> new ArrayList<>())
                        .add(rel);
            }
            for (Loaded l : classes.values()) {
                if (h.supers(l.facts().name()).stream().anyMatch(family::containsKey)) {
                    familyTypes.add(simple(l.facts().name()));
                }
            }
        }

        private @Nullable String rel(Loaded l) {
            String file = l.facts().sourceFile();
            return file == null
                    ? null
                    : GraphBuild.find(byName, l.prefix(), pkg(l.facts().name()), file);
        }

        /** Files every declaration and call of the name the classes record under its source file. */
        void tally() {
            for (Loaded l : classes.values()) {
                ClassFacts cf = l.facts();
                String rel = rel(l);
                for (MethodFacts m : cf.methods()) {
                    if ((m.access() & ACC_BRIDGE) != 0) continue;
                    boolean synthetic = (m.access() & ACC_SYNTHETIC) != 0;
                    if (synthetic && !m.name().startsWith("lambda$")) continue;
                    List<Rec> uses = new ArrayList<>();
                    if (!synthetic && m.name().equals(old)) {
                        boolean mine = family.getOrDefault(cf.name(), Set.of()).contains(m.desc());
                        uses.add(new Rec(
                                mine, true, m.firstLine(), paramNames(m.desc()).size(), 1));
                        if (rel != null) {
                            Tally t = tallies.computeIfAbsent(rel, k -> new Tally());
                            if (mine) t.declaresFamily = true;
                            else t.declaresOther = true;
                        }
                        if (mine && overrides(h, cf, m)) overriding++;
                    }
                    for (CallSite cs : m.calls()) {
                        if (!cs.name().equals(old) && !cs.name().equals(old + "$default")) continue;
                        int arity =
                                cs.name().equals(old) ? paramNames(cs.desc()).size() : -2;
                        uses.add(new Rec(calls(h, family, cs, old), false, cs.line(), arity, Math.max(1, cs.count())));
                    }
                    if (uses.isEmpty()) continue;
                    if (rel == null) {
                        if (uses.stream().anyMatch(Rec::mine))
                            unplaced.add(cf.name().replace('/', '.'));
                        continue;
                    }
                    tallies.computeIfAbsent(rel, k -> new Tally()).recs.addAll(uses);
                }
            }
        }

        /** Quotes the uses of the name in modules that may call the method but have no compiled classes. */
        void unchecked(List<GraphBuild.ClassIndex> indexes) {
            String ownerRel = rel(Objects.requireNonNull(classes.get(target.owner())));
            Set<String> reaching = ownerRel == null ? null : reaching(root, graph.modules(), ownerRel);
            for (String module : unbuilt(graph, indexes, reaching)) {
                String prefix = module.substring(0, module.lastIndexOf(' '));
                String set = module.substring(module.lastIndexOf(' ') + 1);
                for (String rel : jvmFiles) {
                    if (!inModule(graph.modules(), rel, prefix, set)) continue;
                    String text = read(root, rel);
                    if (text == null || !text.contains(old)) continue;
                    JvmTokens tokens = JvmTokens.of(text);
                    boolean namesFamily = familyTypes.stream().anyMatch(tokens::has);
                    List<Integer> uses = tokens.uses(old).stream()
                            .filter(i -> mayCall(tokens, i, familyTypes, namesFamily))
                            .toList();
                    if (!uses.isEmpty()) unchecked.put(rel, quote(text, tokens, uses));
                }
            }
        }

        /** Chooses each file's edits; nothing is written yet. */
        void edit() {
            for (Map.Entry<String, Tally> e : tallies.entrySet()) {
                Tally t = e.getValue();
                if (t.family() == 0) continue;
                String rel = e.getKey();
                String text = read(root, rel);
                if (text == null || rel.endsWith(".groovy")) {
                    unsettled.put(rel, List.of(text == null ? "    (unreadable)" : "    (Groovy: edit by hand)"));
                    continue;
                }
                JvmTokens tokens = JvmTokens.of(text);
                if (rel.endsWith(".java") && fromIndex(rel, text, t, tokens)) continue;
                List<Integer> chosen = new ArrayList<>();
                List<Integer> left = new ArrayList<>();
                settle(tokens, tokens.uses(old), t, chosen, left);
                if (!left.isEmpty()) unsettled.put(rel, quote(text, tokens, left));
                if (chosen.isEmpty()) continue;
                for (int i : chosen) {
                    if (!tokens.declares(i)) {
                        calls++;
                    } else {
                        declarations++;
                        if (tokens.overrideMarked(i)) annotated++;
                    }
                }
                edited.put(rel, tokens.replace(text, chosen, old.length(), to));
                changedLines.put(
                        rel, chosen.stream().map(tokens::line).distinct().toList());
            }
        }

        /**
         * Edits {@code rel} from the reference index its compile wrote: every span whose symbol is one
         * of the family's methods, declarations, calls and doc links alike, exactly where javac
         * resolved them. False, and nothing chosen, when the file has no index describing it as it is,
         * or the index holds fewer calls of the family than the classes record (then the token path
         * decides, as it does without an index).
         */
        boolean fromIndex(String rel, String text, Tally t, JvmTokens tokens) {
            List<ReferenceIndex.Span> spans = ReferenceIndex.of(root, graph.modules(), rel, text);
            if (spans == null) return false;
            Set<String> symbols = new HashSet<>();
            family.forEach((owner, descs) -> descs.forEach(d -> symbols.add(ReferenceIndex.method(owner, old, d))));
            long recorded = t.recs.stream()
                    .filter(r -> r.mine() && !r.decl())
                    .mapToLong(Rec::count)
                    .sum();
            ReferenceIndex.Edit e = ReferenceIndex.rename(spans, text, symbols, old, to, recorded);
            if (e == null) return false;
            if (e.lines().isEmpty()) return true;
            declarations += e.declarations().size();
            calls += e.calls();
            docs += e.docs();
            for (int at : e.declarations()) {
                int token = tokens.tokenAt(at);
                if (token >= 0 && tokens.overrideMarked(token)) annotated++;
            }
            edited.put(rel, e.text());
            changedLines.put(rel, e.lines());
            indexed.add(rel);
            return true;
        }

        /** Renames the doc links to the method in every source not already edited from its index. */
        void docLinks() {
            for (String rel : jvmFiles) {
                if (indexed.contains(rel)) continue;
                String text = edited.containsKey(rel) ? edited.get(rel) : read(root, rel);
                if (text == null || !text.contains("#" + old)) continue;
                Tally t = tallies.get(rel);
                boolean bare = t != null && t.declaresFamily && !t.declaresOther;
                JvmTokens.DocEdit doc = JvmTokens.of(text).docLinks(text, old, to, familyTypes, bare);
                if (doc.count() == 0) continue;
                docs += doc.count();
                edited.put(rel, doc.text());
                List<Integer> lines = new ArrayList<>(changedLines.getOrDefault(rel, List.of()));
                lines.addAll(doc.lines());
                changedLines.put(rel, lines.stream().distinct().sorted().toList());
            }
        }

        /**
         * What to run next: the tests of the modules the rename edited, by directory, not the whole
         * workspace, whose unrelated failures an agent would otherwise chase. A single-module
         * project, or an edit at the workspace root, is tested whole.
         */
        String testNext() {
            return EditedTests.next(graph.modules(), edited.keySet());
        }

        List<String> answer() {
            List<String> lines = new ArrayList<>();
            lines.add("Renamed " + display(target) + " to " + to + ": " + count(declarations, "declaration") + ", "
                    + count(calls, "call") + (docs == 0 ? "" : ", " + count(docs, "doc link"))
                    + (prose == 0 ? "" : ", " + count(prose, "comment or doc mention")) + ", in "
                    + count(edited.size(), "file") + ".");
            for (Map.Entry<String, List<Integer>> e : changedLines.entrySet()) {
                lines.add("  " + e.getKey() + ":" + numbers(e.getValue()));
            }
            lines.addAll(RenameMentions.lines(mentions, old, display(target)));
            if (unsettled.isEmpty() && unplaced.isEmpty() && unchecked.isEmpty()) {
                lines.add("Every use the compiled classes record is renamed; no search needed. " + testNext());
            }
            if (!unchecked.isEmpty()) {
                lines.add("Not checked: these modules may call it but have no compiled classes. Each line below uses a "
                        + old + "; edit the ones that call " + display(target) + ":");
                unchecked.forEach((rel, quoted) -> {
                    lines.add("  " + rel);
                    lines.addAll(quoted);
                });
            }
            if (!unsettled.isEmpty() || !unplaced.isEmpty()) {
                lines.add("Not renamed: the lines below also use another " + old + ", or hold a use the classes count"
                        + " differently (a method reference in its own class, a finally block). Edit the ones that"
                        + " use " + display(target) + " to " + to + ", then build and test:");
                unsettled.forEach((rel, quoted) -> {
                    lines.add("  " + rel);
                    lines.addAll(quoted);
                });
                for (String c : unplaced) lines.add("  " + c + ": its source file was not found");
            }
            return lines;
        }
    }

    /**
     * Chooses which of a file's {@code uses} to rename: the file's when {@link #pick} can tell them
     * apart there, else method body by method body. A body it cannot leaves its uses in {@code left}.
     */
    private static void settle(
            JvmTokens tokens, List<Integer> uses, Tally t, List<Integer> chosen, List<Integer> left) {
        List<Integer> whole = pick(tokens, uses, t.recs);
        if (whole != null) {
            chosen.addAll(whole);
            return;
        }
        Map<Integer, List<Integer>> found = new TreeMap<>();
        for (int i : uses)
            found.computeIfAbsent(tokens.region(tokens.line(i)), k -> new ArrayList<>())
                    .add(i);
        Map<Integer, List<Rec>> recorded = new TreeMap<>();
        for (Rec r : t.recs)
            recorded.computeIfAbsent(tokens.region(r.line()), k -> new ArrayList<>())
                    .add(r);
        for (Map.Entry<Integer, List<Rec>> e : recorded.entrySet()) {
            if (e.getValue().stream().noneMatch(Rec::mine)) continue;
            List<Integer> here = found.getOrDefault(e.getKey(), List.of());
            List<Integer> picked = pick(tokens, here, e.getValue());
            if (picked != null) chosen.addAll(picked);
            else if (!here.isEmpty()) left.addAll(here);
            else left.add(-e.getKey() - 2);
        }
    }

    /**
     * The family's uses among {@code uses}, or {@code null} when the source and the classes disagree
     * there: every use is the family's when the counts match and no other method of the name is
     * used; else, with no method reference among them, the declarations and the calls of each
     * argument count are the family's when that count's tally is the family's alone and matches.
     */
    static @Nullable List<Integer> pick(JvmTokens tokens, List<Integer> uses, List<Rec> recs) {
        int mine = recs.stream().filter(Rec::mine).mapToInt(Rec::count).sum();
        int other = recs.stream().filter(r -> !r.mine()).mapToInt(Rec::count).sum();
        if (mine == 0) return List.of();
        if (other == 0) return uses.size() == mine ? uses : null;
        Map<Integer, int[]> byArity = new TreeMap<>();
        for (Rec r : recs) {
            int key = r.arity() < 0 ? r.arity() : r.decl() ? -r.arity() - 10 : r.arity();
            byArity.computeIfAbsent(key, k -> new int[2])[r.mine() ? 0 : 1] += r.count();
        }
        Map<Integer, List<Integer>> found = new TreeMap<>();
        for (int i : uses) {
            int a = tokens.arity(i);
            if (a < 0) return null;
            found.computeIfAbsent(tokens.declares(i) ? -a - 10 : a, k -> new ArrayList<>())
                    .add(i);
        }
        if (!byArity.keySet().containsAll(found.keySet())) return null;
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : byArity.entrySet()) {
            int[] c = e.getValue();
            List<Integer> f = found.getOrDefault(e.getKey(), List.of());
            if (e.getKey() < 0 && e.getKey() > -10 || f.size() != c[0] + c[1] || c[0] > 0 && c[1] > 0) return null;
            if (c[0] > 0) out.addAll(f);
        }
        return out;
    }

    /** {@code L<n>| text} for each use left, at most {@link #LISTED_LINES}. */
    private static List<String> quote(String text, JvmTokens tokens, List<Integer> left) {
        List<String> source = text.lines().toList();
        Set<Integer> lines = new TreeSet<>();
        for (int i : left) lines.add(i >= 0 ? tokens.line(i) : tokens.regionStart(-i - 2));
        List<String> out = new ArrayList<>();
        for (int n : lines) {
            if (out.size() == LISTED_LINES) {
                out.add("    …");
                break;
            }
            if (n < 1 || n > source.size()) continue;
            String line = source.get(n - 1).strip();
            if (line.length() > QUOTE_WIDTH) line = line.substring(0, QUOTE_WIDTH) + "…";
            out.add("    L" + n + "| " + line);
        }
        return out;
    }

    /** Why the classes cannot answer for every source yet, or {@code null} when they can. */
    private static @Nullable String edited(List<GraphBuild.ClassIndex> indexes) {
        Set<String> edited = new TreeSet<>();
        for (GraphBuild.ClassIndex c : indexes) edited.addAll(c.stale());
        if (edited.isEmpty()) return null;
        List<String> shown = new ArrayList<>(edited).subList(0, Math.min(edited.size(), 5));
        return "Not renamed: " + count(edited.size(), "source") + " edited since the last build ("
                + String.join(", ", shown) + (edited.size() > shown.size() ? ", …" : "") + "). The compiled"
                + " classes are what find every use: build first (run kind=build, or jk build), then rename.";
    }

    /**
     * The module source sets ({@code prefix set}) that hold JVM sources but no compiled classes,
     * among {@code reaching} (module prefixes; {@code null} for every module).
     */
    private static List<String> unbuilt(
            CodeGraph graph, List<GraphBuild.ClassIndex> indexes, @Nullable Set<String> reaching) {
        Set<String> have = new TreeSet<>();
        for (GraphBuild.ClassIndex c : indexes) {
            String file = c.index().getFileName().toString();
            have.add(c.modulePrefix() + " " + (file.startsWith("test") ? "test" : "main"));
        }
        Modules modules = graph.modules();
        Set<String> missing = new TreeSet<>();
        for (GraphNode n : graph.nodes()) {
            String rel = n.sourceFile();
            if (rel.isEmpty() || !Extractors.isJvmSource(rel) || rel.contains("/resources/")) continue;
            Modules.SourceSet set = modules.sourceSet(rel);
            if (set == Modules.SourceSet.OTHER) continue;
            Modules.Module m = modules.of(rel);
            String prefix = m == null ? "" : m.prefix();
            if (reaching != null && !reaching.contains(prefix)) continue;
            String key = prefix + " " + (set == Modules.SourceSet.TEST ? "test" : "main");
            if (!have.contains(key)) missing.add(key);
        }
        return List.copyOf(missing);
    }

    /**
     * True when the use at {@code token}, in a file with no compiled classes, may be the family's: a
     * call through a type that is not one of {@code familyTypes} cannot be, and a declaration or an
     * unqualified call can only be in a file that names one of them.
     */
    private static boolean mayCall(JvmTokens tokens, int token, Set<String> familyTypes, boolean namesFamily) {
        String qualifier = tokens.qualifier(token);
        if (qualifier == null || tokens.declares(token)) return namesFamily;
        boolean typeName = !qualifier.isEmpty() && Character.isUpperCase(qualifier.charAt(0));
        return !typeName || familyTypes.contains(qualifier);
    }

    private static boolean inModule(Modules modules, String rel, String prefix, String set) {
        Modules.Module m = modules.of(rel);
        if (!(m == null ? "" : m.prefix()).equals(prefix)) return false;
        Modules.SourceSet s = modules.sourceSet(rel);
        return s != Modules.SourceSet.OTHER && (s == Modules.SourceSet.TEST ? "test" : "main").equals(set);
    }

    /**
     * The modules that can call into the one holding {@code rel}: it and every module that depends
     * on it, directly or through another, as their manifests say; {@code null} when a manifest
     * is not TOML, so every module counts.
     */
    static @Nullable Set<String> reaching(Path root, Modules modules, String rel) {
        Modules.Module home = modules.of(rel);
        if (home == null) return null;
        Map<String, String> prefixByName = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (Modules.Module m : modules.list()) {
            try {
                ManifestOutline outline = ManifestOutline.read(ManifestPaths.manifestIn(root.resolve(m.prefix())));
                if (outline.name() != null) prefixByName.put(outline.name(), m.prefix());
                for (String dep : outline.workspaceDependencies()) {
                    String name = dep.substring(dep.lastIndexOf(Dependency.WORKSPACE_GROUP_SEPARATOR) + 1);
                    dependents.computeIfAbsent(name, k -> new TreeSet<>()).add(m.prefix());
                }
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }
        Map<String, String> nameByPrefix = new HashMap<>();
        prefixByName.forEach((name, prefix) -> nameByPrefix.put(prefix, name));
        Set<String> out = new LinkedHashSet<>(List.of(home.prefix()));
        List<String> todo = new ArrayList<>(out);
        for (int i = 0; i < todo.size(); i++) {
            String name = nameByPrefix.get(todo.get(i));
            for (String p : dependents.getOrDefault(name, Set.of())) {
                if (out.add(p)) todo.add(p);
            }
        }
        return out;
    }

    /** The method an address names, or why there is none. */
    private record Target(
            String owner,
            String name,
            Set<String> descs,
            @Nullable String error) {}

    private static Target target(Map<String, Loaded> classes, String address) {
        String wanted = address.strip();
        String params = null;
        int paren = wanted.indexOf('(');
        if (paren > 0) {
            int close = wanted.lastIndexOf(')');
            params = wanted.substring(paren + 1, close > paren ? close : wanted.length())
                    .strip();
            wanted = wanted.substring(0, paren);
        }
        int dot = wanted.lastIndexOf('.');
        if (dot <= 0 || dot == wanted.length() - 1) {
            return new Target("", "", Set.of(), "Pass Type.method, or Type.method(int,String) for one overload.");
        }
        String owner = wanted.substring(0, dot);
        String name = wanted.substring(dot + 1);
        List<String> matches = new ArrayList<>();
        for (Loaded l : classes.values()) {
            String dotted = l.facts().name().replace('/', '.').replace('$', '.');
            if (!(dotted.equals(owner) || dotted.endsWith("." + owner))) continue;
            if (declared(l.facts(), name).isEmpty()) continue;
            matches.add(l.facts().name());
        }
        if (matches.isEmpty()) {
            List<String> declaring = classes.values().stream()
                    .filter(l -> !declared(l.facts(), name).isEmpty())
                    .map(l -> l.facts().name())
                    .toList();
            return new Target("", name, Set.of(), RenameHints.noMethod(wanted, owner, name, declaring));
        }
        if (matches.size() > 1) {
            List<String> shown =
                    matches.stream().map(m -> m.replace('/', '.')).sorted().toList();
            return new Target(
                    "",
                    name,
                    Set.of(),
                    "'" + wanted + "' names " + matches.size() + " types; pass one: " + String.join(", ", shown));
        }
        String cls = matches.getFirst();
        List<MethodFacts> overloads =
                declared(Objects.requireNonNull(classes.get(cls)).facts(), name);
        Set<String> descs = new TreeSet<>();
        for (MethodFacts m : overloads) {
            if (params == null || paramsMatch(params, m.desc())) descs.add(m.desc());
        }
        if (descs.isEmpty()) {
            List<String> shown = overloads.stream()
                    .map(m -> name + "(" + String.join(",", paramNames(m.desc())) + ")")
                    .toList();
            return new Target(
                    cls,
                    name,
                    Set.of(),
                    "No overload " + name + "(" + params + ") on " + simple(cls) + "; it declares "
                            + String.join(", ", shown) + ".");
        }
        if (OBJECT_METHODS.contains(name)) {
            return new Target(cls, name, descs, "Not renamed: " + name + " overrides Object's.");
        }
        return new Target(cls, name, descs, null);
    }

    private static List<MethodFacts> declared(ClassFacts cf, String name) {
        return cf.methods().stream()
                .filter(m -> m.name().equals(name) && (m.access() & (ACC_BRIDGE | ACC_SYNTHETIC)) == 0)
                .toList();
    }

    /** True when the written parameter types name {@code desc}'s: simple names, a lone capital a type variable. */
    static boolean paramsMatch(String written, String desc) {
        List<String> want = written.isBlank() ? List.of() : splitParams(written);
        List<String> have = paramNames(desc);
        if (want.size() != have.size()) return false;
        for (int i = 0; i < want.size(); i++) {
            String w = want.get(i);
            if (w.equals(have.get(i))) continue;
            if (w.matches("[A-Z][A-Z0-9]?") && !have.get(i).contains("[") || w.matches("[A-Z][A-Z0-9]?\\[]+")) continue;
            return false;
        }
        return true;
    }

    private static List<String> splitParams(String written) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char ch : written.toCharArray()) {
            if (ch == '<') depth++;
            else if (ch == '>') depth--;
            else if (ch == ',' && depth == 0) {
                out.add(normalise(cur.toString()));
                cur.setLength(0);
                continue;
            }
            if (depth == 0 && ch != '>') cur.append(ch);
        }
        out.add(normalise(cur.toString()));
        return out;
    }

    /** {@code final java.util.List<X> xs} → {@code List}; {@code String...} → {@code String[]}. */
    private static String normalise(String param) {
        String p = param.strip().replace("...", "[]").replaceAll("\\s*\\[\\s*]", "[]");
        String[] words = p.split("\\s+");
        String type = words[0].equals("final") && words.length > 1 ? words[1] : words[0];
        if (words.length > 1 && !words[0].equals("final") && words[0].startsWith("@") && words.length > 1)
            type = words[1];
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    /** {@code (Ljava/lang/String;[I)V} → {@code [String, int[]]}. */
    static List<String> paramNames(String desc) {
        List<String> out = new ArrayList<>();
        int i = 1;
        while (i < desc.length() && desc.charAt(i) != ')') {
            int dims = 0;
            while (desc.charAt(i) == '[') {
                dims++;
                i++;
            }
            String t;
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                t = simple(desc.substring(i + 1, end));
                i = end + 1;
            } else {
                t = switch (c) {
                    case 'Z' -> "boolean";
                    case 'B' -> "byte";
                    case 'C' -> "char";
                    case 'S' -> "short";
                    case 'I' -> "int";
                    case 'J' -> "long";
                    case 'F' -> "float";
                    case 'D' -> "double";
                    default -> String.valueOf(c);
                };
                i++;
            }
            out.add(t + "[]".repeat(dims));
        }
        return out;
    }

    /** Workspace supertypes, transitively, of a class, itself included. */
    private static final class Hierarchy {
        private final Map<String, Loaded> classes;
        private final Map<String, Set<String>> up = new HashMap<>();

        Hierarchy(Map<String, Loaded> classes) {
            this.classes = classes;
        }

        Set<String> supers(String cls) {
            Set<String> had = up.get(cls);
            if (had != null) return had;
            Set<String> out = new LinkedHashSet<>();
            List<String> todo = new ArrayList<>(List.of(cls));
            for (int i = 0; i < todo.size(); i++) {
                String c = todo.get(i);
                Loaded l = classes.get(c);
                if (l == null || !out.add(c)) continue;
                if (l.facts().superName() != null) todo.add(l.facts().superName());
                todo.addAll(l.facts().interfaces());
            }
            up.put(cls, out);
            return out;
        }
    }

    /**
     * Every class and descriptor of {@code old} that dispatch joins to the target's: two
     * declarations of one descriptor that a class inherits both of are one method, and a bridge
     * joins the descriptor it carries to the one it calls. Private and static methods join nothing.
     */
    private static Map<String, Set<String>> family(Map<String, Loaded> classes, Hierarchy h, Target target) {
        String old = target.name();
        Map<String, Set<String>> family = new HashMap<>();
        family.put(target.owner(), new TreeSet<>(target.descs()));
        Map<String, List<MethodFacts>> declaring = new HashMap<>();
        for (Loaded l : classes.values()) {
            List<MethodFacts> ms = l.facts().methods().stream()
                    .filter(m -> m.name().equals(old) && (m.access() & (ACC_PRIVATE | ACC_STATIC)) == 0)
                    .toList();
            if (!ms.isEmpty()) declaring.put(l.facts().name(), ms);
        }
        MethodFacts start = declared(
                        Objects.requireNonNull(classes.get(target.owner())).facts(), old)
                .stream()
                .filter(m -> target.descs().contains(m.desc()))
                .findFirst()
                .orElse(null);
        if (start != null && (start.access() & (ACC_PRIVATE | ACC_STATIC)) != 0) return family;
        for (boolean grew = true; grew; ) {
            grew = false;
            for (String x : declaring.keySet()) {
                for (MethodFacts m : declaring.get(x)) {
                    if ((m.access() & ACC_BRIDGE) == 0) continue;
                    for (CallSite cs : m.calls()) {
                        if (!cs.owner().equals(x) || !cs.name().equals(old)) continue;
                        Set<String> f = family.computeIfAbsent(x, k -> new TreeSet<>());
                        if (f.contains(m.desc()) || f.contains(cs.desc())) {
                            grew |= f.add(m.desc());
                            grew |= f.add(cs.desc());
                        }
                    }
                }
            }
            for (String x : classes.keySet()) {
                Set<String> supers = h.supers(x);
                Map<String, List<String>> byDesc = new HashMap<>();
                for (String s : supers) {
                    for (MethodFacts m : declaring.getOrDefault(s, List.of())) {
                        byDesc.computeIfAbsent(m.desc(), k -> new ArrayList<>()).add(s);
                    }
                }
                for (Map.Entry<String, List<String>> d : byDesc.entrySet()) {
                    if (d.getValue().size() < 2) continue;
                    boolean any = d.getValue().stream()
                            .anyMatch(s -> family.getOrDefault(s, Set.of()).contains(d.getKey()));
                    if (!any) continue;
                    for (String s : d.getValue())
                        grew |= family.computeIfAbsent(s, k -> new TreeSet<>()).add(d.getKey());
                }
            }
        }
        family.values().removeIf(Set::isEmpty);
        return family;
    }

    /** True when the call reaches the family: a class it names inherits a family descriptor of its name. */
    private static boolean calls(Hierarchy h, Map<String, Set<String>> family, CallSite cs, String old) {
        if (cs.name().equals(old + "$default")) {
            return h.supers(cs.owner()).stream().anyMatch(family::containsKey);
        }
        for (String s : h.supers(cs.owner())) {
            if (family.getOrDefault(s, Set.of()).contains(cs.desc())) return true;
        }
        return false;
    }

    /** True when {@code m} overrides a declaration in a workspace supertype, directly or through a bridge. */
    private static boolean overrides(Hierarchy h, ClassFacts cf, MethodFacts m) {
        if ((m.access() & (ACC_PRIVATE | ACC_STATIC)) != 0) return false;
        Set<String> descs = new TreeSet<>(List.of(m.desc()));
        for (MethodFacts b : cf.methods()) {
            if ((b.access() & ACC_BRIDGE) == 0 || !b.name().equals(m.name())) continue;
            for (CallSite cs : b.calls()) {
                if (cs.name().equals(m.name()) && cs.desc().equals(m.desc())) descs.add(b.desc());
            }
        }
        for (String s : h.supers(cf.name())) {
            if (s.equals(cf.name())) continue;
            Loaded l = h.classes.get(s);
            if (l != null
                    && l.facts().methods().stream()
                            .anyMatch(o -> o.name().equals(m.name()) && descs.contains(o.desc()))) {
                return true;
            }
        }
        return false;
    }

    /** Why {@code to} cannot be the new name, or {@code null}: a related class has it with the same parameters. */
    private static @Nullable String clash(
            Map<String, Loaded> classes, Hierarchy h, Map<String, Set<String>> family, String old, String to) {
        for (Map.Entry<String, Set<String>> f : family.entrySet()) {
            Set<String> params = new TreeSet<>();
            for (String d : f.getValue()) params.add(d.substring(0, d.indexOf(')') + 1));
            for (Loaded l : classes.values()) {
                String y = l.facts().name();
                if (!h.supers(y).contains(f.getKey()) && !h.supers(f.getKey()).contains(y)) continue;
                for (MethodFacts m : l.facts().methods()) {
                    if (m.name().equals(to)
                            && params.contains(m.desc().substring(0, m.desc().indexOf(')') + 1))) {
                        return "Not renamed: " + y.replace('/', '.') + " already declares " + to + "("
                                + String.join(",", paramNames(m.desc())) + "), which " + old + " would collide with.";
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable String read(Path root, String rel) {
        try {
            Path file = root.resolve(rel).normalize();
            if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file)) return null;
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String display(Target t) {
        return simple(t.owner()) + "." + t.name();
    }

    private static String simple(String internal) {
        String s = internal.substring(internal.lastIndexOf('/') + 1);
        return s.substring(s.lastIndexOf('$') + 1);
    }

    private static String pkg(String internal) {
        int slash = internal.lastIndexOf('/');
        return slash < 0 ? "" : internal.substring(0, slash);
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String numbers(List<Integer> lines) {
        List<String> out = new ArrayList<>();
        for (int n : lines) {
            if (out.size() == LISTED_LINES) {
                out.add("…");
                break;
            }
            out.add(String.valueOf(n));
        }
        return String.join(",", out);
    }

    static boolean identifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0)) || KEYWORDS.contains(name)) return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
        }
        return true;
    }
}
