// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.codegraph.extract.Extractors;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.MethodFacts;
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
import java.util.regex.Pattern;
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

    private static final Pattern COMPILE_STATIC = Pattern.compile("@(?:groovy\\.transform\\.)?CompileStatic\\b");

    private static final Set<String> OBJECT_METHODS = Set.of("equals", "hashCode", "toString", "clone", "finalize");

    /** One class's facts and the module directory its index came from. */
    record Loaded(ClassFacts facts, String prefix, String sourceSet) {}

    /** Every class set's facts, by internal name: what finds every use of a method. */
    static Map<String, Loaded> classes(List<GraphBuild.ClassIndex> indexes) throws IOException {
        Map<String, Loaded> classes = new LinkedHashMap<>();
        for (GraphBuild.ClassIndex c : indexes) {
            Path index = c.index();
            if (index == null) continue;
            for (ClassFacts cf : FactsFormat.read(index).classes().values()) {
                classes.putIfAbsent(cf.name(), new Loaded(cf, c.modulePrefix(), c.sourceSet()));
            }
        }
        return classes;
    }

    /**
     * Every class and descriptor dispatch joins to method {@code name} with {@code descs} of internal
     * name {@code owner}: its overrides and implementations, and the declarations it implements.
     */
    static Map<String, Set<String>> familyOf(
            Map<String, Loaded> classes, String owner, String name, Set<String> descs) {
        return family(classes, new Hierarchy(classes), new Target(owner, name, descs, null));
    }

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
     * Plans the rename of {@code address} ({@code Type.method}, every overload, or {@code
     * Type.method(int, String)} for one) to {@code to}: the edits the compiled classes and javac's
     * index bind, and as candidates the uses they cannot tell apart, the uses in modules with no
     * compiled classes and the calls dynamic Groovy makes by name. Nothing is written here; a
     * refusal is thrown as {@link RenameRefusal}.
     */
    static RenamePlan rename(GraphService.Workspace w, CodeGraph graph, String address, String to) throws IOException {
        Path root = w.root();
        List<GraphBuild.ClassIndex> indexes = w.indexes();
        if (!RenameNames.identifier(to)) throw new IllegalArgumentException("'" + to + "' is not a method name");
        String refused = edited(indexes);
        if (refused != null) throw new RenameRefusal(refused);
        Map<String, Loaded> classes = new LinkedHashMap<>();
        for (GraphBuild.ClassIndex c : indexes) {
            // The engine indexes every set for a rename: its compiled classes are what find every use.
            Path index = Objects.requireNonNull(c.index(), "a rename reads every class set's index");
            for (ClassFacts cf : FactsFormat.read(index).classes().values()) {
                classes.putIfAbsent(cf.name(), new Loaded(cf, c.modulePrefix(), c.sourceSet()));
            }
        }
        Hierarchy h = new Hierarchy(classes);
        Target target = target(classes, address);
        if (target.error() != null) {
            throw new RenameRefusal(RenameHints.withUnbuilt(target.error(), RenameReach.unbuilt(graph, indexes, null)));
        }
        if (target.name().equals(to)) throw new RenameRefusal("'" + address + "' is already named " + to + ".");
        Map<String, Set<String>> family = family(classes, h, target);
        String clash = clash(classes, h, family, target.name(), to);
        if (clash != null) throw new RenameRefusal(clash);
        Run run = new Run(root, graph, classes, h, target, family, to);
        run.tally();
        run.unchecked(indexes, w.uncompiled(), w.reach());
        run.edit();
        run.dynamicGroovy(w.reach());
        if (run.annotated > run.overriding) {
            throw new RenameRefusal("Not renamed: " + display(target) + " overrides a method outside the workspace"
                    + " (@Override with no supertype here declaring it), so renaming it would break that override.");
        }
        if (run.edited.isEmpty() && run.cands.isEmpty()) {
            throw new RenameRefusal("Nothing renamed: the compiled classes record no declaration of " + display(target)
                    + " in a source file.");
        }
        run.docLinks();
        run.prose = RenameMentions.renameAll(
                root, run.old, to, run.familyTypes, run.edited, run.changedLines, run.textFiles);
        run.mentions = RenameMentions.find(run.root, run.old, run.familyTypes, run.edited, run.textFiles);
        RenamePlan plan = new RenamePlan(display(target), to);
        for (Map.Entry<String, String> e : run.edited.entrySet()) {
            String original = read(root, e.getKey());
            if (original == null) continue;
            plan.edit(
                    e.getKey(),
                    original,
                    e.getValue(),
                    new TreeSet<>(run.changedLines.getOrDefault(e.getKey(), List.of())));
        }
        plan.count("declaration", run.declarations);
        plan.count("call", run.calls);
        plan.count("doc link", run.docs);
        plan.count("comment or doc mention", run.prose);
        plan.notes.addAll(RenameMentions.lines(run.mentions, run.old, display(target)));
        for (String c : run.unplaced) plan.notes.add("  " + c + ": a caller whose source file was not found");
        plan.candidates.addAll(run.cands);
        family.forEach((owner, descs) -> descs.forEach(
                d -> plan.symbols.put(ReferenceIndex.method(owner, run.old, d), ReferenceIndex.method(owner, to, d))));
        boolean complete = run.unseen.isEmpty();
        if (run.cands.isEmpty() && run.unplaced.isEmpty()) {
            plan.next = EditedTests.renamed(graph.modules(), run.edited.keySet(), complete);
        }
        plan.unseen.addAll(GraphService.names(run.unseen));
        return plan;
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
        /** The sets with no compiled classes among the modules that can reach the method. */
        final List<GraphService.Uncompiled> unseen = new ArrayList<>();

        final Map<String, String> edited = new TreeMap<>();
        final Map<String, List<Integer>> changedLines = new TreeMap<>();
        final Map<String, List<String>> unsettled = new TreeMap<>();
        /** The uses not edited, as candidates with the edit {@code also} makes. */
        final List<RenamePlan.Candidate> cands = new ArrayList<>();
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
                    : ClassSources.find(
                            byName,
                            ClassSources.own(graph.modules(), l.prefix(), l.sourceSet()),
                            pkg(l.facts().name()),
                            file);
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
                                mine,
                                true,
                                m.firstLine(),
                                RenameNames.paramNames(m.desc()).size(),
                                1));
                        if (rel != null) {
                            Tally t = tallies.computeIfAbsent(rel, k -> new Tally());
                            if (mine) t.declaresFamily = true;
                            else t.declaresOther = true;
                        }
                        if (mine && overrides(h, cf, m)) overriding++;
                    }
                    for (CallSite cs : m.calls()) {
                        if (!cs.name().equals(old) && !cs.name().equals(old + "$default")) continue;
                        int arity = cs.name().equals(old)
                                ? RenameNames.paramNames(cs.desc()).size()
                                : -2;
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

        /**
         * Keeps the {@code uncompiled} sets of the modules that can call the method ({@link #reach}),
         * and quotes the uses of the name in those sets and in the modules among them that have no
         * class index.
         */
        void unchecked(
                List<GraphBuild.ClassIndex> indexes, List<GraphService.Uncompiled> uncompiled, ModuleReach edges) {
            Set<String> reaching = reach(edges);
            for (GraphService.Uncompiled u : uncompiled) {
                if (reaching == null || reaching.contains(u.prefix())) unseen.add(u);
            }
            List<String> unbuilt = RenameReach.unbuilt(graph, indexes, reaching);
            Modules modules = graph.modules();
            for (String rel : jvmFiles) {
                boolean unread = unseen.stream().anyMatch(u -> u.holds(modules, rel))
                        || unbuilt.stream().anyMatch(set -> RenameReach.inModule(modules, rel, set));
                if (!unread) continue;
                String text = read(root, rel);
                if (text == null || !text.contains(old)) continue;
                JvmTokens tokens = JvmTokens.of(text);
                boolean namesFamily = familyTypes.stream().anyMatch(tokens::has);
                List<Integer> uses = tokens.uses(old).stream()
                        .filter(i -> mayCall(tokens, i, familyTypes, namesFamily))
                        .toList();
                if (!uses.isEmpty()) {
                    unchecked.put(rel, quote(text, tokens, uses));
                    candidates(
                            rel,
                            text,
                            tokens,
                            List.of(),
                            uses,
                            "in a set with no compiled classes; edit it if it calls " + display(target),
                            true);
                }
            }
        }

        /**
         * The modules that can call the method: those that see a module declaring it or a method it
         * overrides, since a call through the supertype runs it too; {@code null} for every module,
         * as when a supertype the workspace's classes do not hold may declare it.
         */
        @Nullable
        Set<String> reach(ModuleReach modules) {
            Set<String> homes = new TreeSet<>();
            for (String type : family.keySet()) {
                homes.add(Objects.requireNonNull(classes.get(type)).prefix());
                for (String s : h.supers(type)) {
                    ClassFacts cf = Objects.requireNonNull(classes.get(s)).facts();
                    List<String> up = new ArrayList<>(cf.interfaces());
                    String superName = cf.superName();
                    if (superName != null) up.add(superName);
                    for (String u : up) {
                        if (!classes.containsKey(u) && ModuleReach.outsideMayDeclare(u, old)) return null;
                    }
                }
            }
            return modules.reaching(homes, graph.modules());
        }

        /**
         * Quotes, for review, the uses of the name in each Groovy source that can call the method and
         * is not {@code @CompileStatic}: it binds its calls at run time, by name, so its classes count
         * none of them, and a rename that skipped them would leave calls that fail when they run.
         */
        void dynamicGroovy(ModuleReach edges) {
            Set<String> reaching = reach(edges);
            Modules modules = graph.modules();
            for (String rel : jvmFiles) {
                if (!rel.endsWith(".groovy") || edited.containsKey(rel) || unsettled.containsKey(rel)) continue;
                if (unchecked.containsKey(rel)) continue;
                Modules.Module m = modules.of(rel);
                if (reaching != null && !reaching.contains(m == null ? "" : m.prefix())) continue;
                String text = read(root, rel);
                if (text == null
                        || !text.contains(old)
                        || COMPILE_STATIC.matcher(text).find()) continue;
                JvmTokens tokens = JvmTokens.of(text);
                List<Integer> uses = tokens.uses(old).stream()
                        .filter(i -> !tokens.declares(i))
                        .toList();
                if (!uses.isEmpty()) {
                    unsettled.put(rel, quote(text, tokens, uses));
                    candidates(rel, text, tokens, List.of(), uses, "dynamic Groovy, bound by name at run time", true);
                }
            }
        }

        /**
         * One candidate per line of {@code rel} holding a use in {@code left}: the line as {@code
         * chosen} leaves it, and the line with those uses renamed too; a body with no use found is a
         * candidate with no edit.
         */
        void candidates(
                String rel,
                String text,
                JvmTokens tokens,
                List<Integer> chosen,
                List<Integer> left,
                String reason,
                boolean must) {
            List<Integer> found = left.stream().filter(i -> i >= 0).toList();
            String partly = tokens.replace(text, chosen, old.length(), to);
            List<Integer> both = new ArrayList<>(chosen);
            both.addAll(found);
            String fully = tokens.replace(text, both, old.length(), to);
            String[] was = partly.split("\n", -1);
            String[] now = fully.split("\n", -1);
            Set<Integer> lines = new TreeSet<>();
            for (int i : found) lines.add(tokens.line(i));
            for (int n : lines) {
                if (n < 1 || n > was.length || n > now.length) continue;
                cands.add(new RenamePlan.Candidate(
                        must,
                        RenamePlan.Grade.INFERRED,
                        reason,
                        rel,
                        n,
                        was[n - 1],
                        List.of(new RenamePlan.LineChange(rel, n, was[n - 1], now[n - 1])),
                        null));
            }
            for (int i : left) {
                if (i >= 0) continue;
                int line = tokens.regionStart(-i - 2);
                cands.add(new RenamePlan.Candidate(
                        false,
                        RenamePlan.Grade.INFERRED,
                        "the classes record a use in this body that its source does not spell as " + old,
                        rel,
                        line,
                        line <= was.length ? was[line - 1] : "",
                        List.of(),
                        "edit by hand"));
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
                    if (text != null) {
                        JvmTokens g = JvmTokens.of(text);
                        candidates(
                                rel,
                                text,
                                g,
                                List.of(),
                                g.uses(old),
                                "Groovy source the classes count, edited by hand",
                                true);
                    }
                    continue;
                }
                JvmTokens tokens = JvmTokens.of(text);
                if (rel.endsWith(".java") && fromIndex(rel, text, t, tokens)) continue;
                List<Integer> chosen = new ArrayList<>();
                List<Integer> left = new ArrayList<>();
                settle(tokens, tokens.uses(old), t, chosen, left);
                if (!left.isEmpty()) {
                    unsettled.put(rel, quote(text, tokens, left));
                    candidates(
                            rel,
                            text,
                            tokens,
                            chosen,
                            left,
                            "another " + old + " is used here too, or the classes" + " count it differently",
                            false);
                }
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
    static @Nullable String edited(List<GraphBuild.ClassIndex> indexes) {
        Set<String> edited = new TreeSet<>();
        for (GraphBuild.ClassIndex c : indexes) edited.addAll(c.stale());
        if (edited.isEmpty()) return null;
        List<String> shown = new ArrayList<>(edited).subList(0, Math.min(edited.size(), 5));
        return "Not renamed: " + count(edited.size(), "source") + " edited since the last build ("
                + String.join(", ", shown) + (edited.size() > shown.size() ? ", …" : "") + "). The compiled"
                + " classes are what find every use: build first (run kind=build, or jk build), then rename.";
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
            return new Target(
                    "",
                    "",
                    Set.of(),
                    "No type, package, method or field '" + wanted + "'. " + RenameHints.FORMS
                            + " Type.method(int,String) names one overload.");
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
                    .map(m -> name + "(" + String.join(",", RenameNames.paramNames(m.desc())) + ")")
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
        List<String> have = RenameNames.paramNames(desc);
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
                                + String.join(",", RenameNames.paramNames(m.desc())) + "), which " + old
                                + " would collide with.";
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
}
