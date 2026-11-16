// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Renames a package (its subpackages with it) or moves one type to another package, from javac's
 * reference index: each moved source's package line, every name javac resolved to a moved type
 * where it is written with its package ({@code import a.b.Owner;}, {@code a.b.Owner.of()}), the
 * on-demand imports and {@code module-info} lines naming the package, and an import added wherever
 * a use relied on sharing the old package. Sources and the resources under the package path move
 * directory for directory, in main, test and every suite.
 */
final class PackageMove {

    private PackageMove() {}

    private static final Pattern PACKAGE_LINE = Pattern.compile("(?m)^(\\s*package\\s+)([\\w.]+)(\\s*;|\\s*$)");
    private static final Pattern ON_DEMAND = Pattern.compile("(?m)^(\\s*import\\s+)([\\w.]+)(\\.\\*\\s*;)");
    private static final Pattern MODULE_LINE = Pattern.compile("(?m)\\b(exports|opens)(\\s+)([\\w.]+)");

    /** The package a Java source declares, or {@code ""}. */
    static String packageOf(String text) {
        Matcher m = PACKAGE_LINE.matcher(stripComments(text));
        return m.find() ? m.group(2) : "";
    }

    /** {@code text} with comments blanked to spaces, offsets kept. */
    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text);
        boolean block = false;
        boolean line = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char n = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (block) {
                if (c == '*' && n == '/') {
                    block = false;
                    out.setCharAt(i, ' ');
                    out.setCharAt(++i, ' ');
                } else if (c != '\n') {
                    out.setCharAt(i, ' ');
                }
            } else if (line) {
                if (c == '\n') line = false;
                else out.setCharAt(i, ' ');
            } else if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '/' && n == '*') {
                block = true;
                out.setCharAt(i, ' ');
            } else if (c == '/' && n == '/') {
                line = true;
                out.setCharAt(i, ' ');
            } else if (c == '"' || c == '\'') {
                quote = c;
            }
        }
        return out.toString();
    }

    /** {@code oldPkg} or a subpackage of it, renamed under {@code newPkg}; else {@code null}. */
    static @Nullable String renamed(String pkg, String oldPkg, String newPkg) {
        if (pkg.equals(oldPkg)) return newPkg;
        if (pkg.startsWith(oldPkg + ".")) return newPkg + pkg.substring(oldPkg.length());
        return null;
    }

    /**
     * The qualifier written before the span starting at {@code start}: {@code a.b} of {@code a.b.Owner}
     * (whitespace around the dots allowed), as its offsets and text without whitespace; {@code null}
     * when the name stands alone.
     */
    record Qualifier(int start, int end, String name) {}

    static @Nullable Qualifier qualifier(String text, int start) {
        int i = start - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) i--;
        if (i < 0 || text.charAt(i) != '.') return null;
        int dotEnd = i;
        List<String> parts = new ArrayList<>();
        int begin = -1;
        while (true) {
            int j = i - 1;
            while (j >= 0 && Character.isWhitespace(text.charAt(j))) j--;
            int wordEnd = j + 1;
            while (j >= 0 && Character.isJavaIdentifierPart(text.charAt(j))) j--;
            if (j + 1 == wordEnd) break;
            parts.addFirst(text.substring(j + 1, wordEnd));
            begin = j + 1;
            int k = j;
            while (k >= 0 && Character.isWhitespace(text.charAt(k))) k--;
            if (k < 0 || text.charAt(k) != '.') break;
            i = k;
        }
        if (begin < 0) return null;
        return new Qualifier(begin, dotEnd, String.join(".", parts));
    }

    /** One moved type: its old binary name and its new one. */
    record Moved(String oldBinary, String newBinary) {
        String oldPkg() {
            return pkgOf(oldBinary);
        }

        String newPkg() {
            return pkgOf(newBinary);
        }
    }

    static String pkgOf(String binary) {
        int dot = binary.lastIndexOf('.');
        return dot < 0 ? "" : binary.substring(0, dot);
    }

    static String simpleOf(String binary) {
        return binary.substring(Math.max(binary.lastIndexOf('.'), binary.lastIndexOf('$')) + 1);
    }

    // ---- package rename ----

    /** Renames package {@code oldPkg}, and its subpackages, to {@code newPkg}. */
    static RenamePlan renamePackage(SymbolRename.Ctx c, String oldPkg, String newPkg, List<String> textFiles)
            throws IOException {
        Map<String, String> moving = new TreeMap<>();
        List<String> otherLanguage = new ArrayList<>();
        for (String rel : c.jvmSources(null)) {
            String text = c.text(rel);
            if (text == null) continue;
            String pkg = packageOf(text);
            String to = renamed(pkg, oldPkg, newPkg);
            if (to == null) continue;
            if (!rel.endsWith(".java")) {
                otherLanguage.add(rel);
                continue;
            }
            moving.put(rel, to);
        }
        if (moving.isEmpty()) {
            throw new RenameRefusal("Not renamed: no Java source of the workspace declares package " + oldPkg + ".");
        }
        Map<String, Moved> types = new LinkedHashMap<>();
        for (GraphNode n : c.graph.nodes()) {
            if (!Decl.isType(n) || !(n.extra().get(Linker.QUALIFIED_NAME) instanceof String q)) continue;
            if (!moving.containsKey(n.sourceFile())) continue;
            String to = renamed(pkgOf(q), oldPkg, newPkg);
            if (to == null) continue;
            String newBinary = to + "." + q.substring(pkgOf(q).length() + 1);
            if (SymbolRename.typeExists(c.graph, newBinary)) {
                throw new RenameRefusal("Not renamed: " + newBinary + " already exists.");
            }
            types.put("T " + q, new Moved(q, newBinary));
        }
        RenamePlan plan = new RenamePlan("package " + oldPkg, newPkg);
        edits(c, plan, types, moving, oldPkg, newPkg);
        for (Map.Entry<String, String> e : moving.entrySet()) {
            String rel = e.getKey();
            String text = c.text(rel);
            if (text == null) continue;
            String target = movedPath(rel, packageOf(text), e.getValue());
            if (target == null) continue;
            if (Files.exists(RenameStore.inside(c.root, target))) {
                throw new RenameRefusal("Not renamed: " + target + " already exists.");
            }
            plan.move(rel, plan.current(rel, text), target);
        }
        resources(c, plan, oldPkg, newPkg);
        for (String rel : otherLanguage) {
            String text = c.text(rel);
            if (text == null) continue;
            Matcher m = PACKAGE_LINE.matcher(text);
            if (!m.find()) continue;
            int line = SymbolRename.lineOf(text, m.start(2));
            String old = text.split("\n", -1)[line - 1];
            String to = renamed(m.group(2), oldPkg, newPkg);
            String target = movedPath(rel, m.group(2), to == null ? m.group(2) : to);
            List<RenamePlan.Change> changes = new ArrayList<>();
            changes.add(
                    new RenamePlan.LineChange(rel, line, old, old.replace(m.group(2), to == null ? m.group(2) : to)));
            if (target != null) changes.add(new RenamePlan.Move(rel, target));
            plan.candidates.add(new RenamePlan.Candidate(
                    true,
                    RenamePlan.Grade.INFERRED,
                    (rel.endsWith(".groovy") ? "Groovy" : "Kotlin") + " source in the package",
                    rel,
                    line,
                    old,
                    changes,
                    target == null ? null : "moves to " + target));
        }
        Map<String, String> symbols = new LinkedHashMap<>();
        types.forEach((sym, m) -> symbols.put(sym, "T " + m.newBinary()));
        plan.symbols.putAll(symbols);
        SymbolRename.unindexedSources(c, plan, null, Map.of(oldPkg, newPkg));
        packageText(c, plan, oldPkg, newPkg, textFiles);
        return plan;
    }

    /** {@code rel}'s path once its package {@code from} becomes {@code to}, when it sits in its package directory; else {@code null}. */
    static @Nullable String movedPath(String rel, String from, String to) {
        String dir = from.replace('.', '/');
        int slash = rel.lastIndexOf('/');
        String parent = slash < 0 ? "" : rel.substring(0, slash);
        if (!(parent.equals(dir) || parent.endsWith("/" + dir))) return null;
        String root = parent.substring(0, parent.length() - dir.length());
        return root + to.replace('.', '/') + rel.substring(slash);
    }

    /** The resources under the package's path, in every resources directory of every module: moved as they are. */
    private static void resources(SymbolRename.Ctx c, RenamePlan plan, String oldPkg, String newPkg)
            throws IOException {
        String from = oldPkg.replace('.', '/');
        String to = newPkg.replace('.', '/');
        Set<Path> roots = new TreeSet<>();
        List<Modules.Module> modules = new ArrayList<>(c.modules.list());
        if (modules.isEmpty()) modules.add(new Modules.Module("", false));
        for (Modules.Module m : modules) {
            Path base = c.root.resolve(m.prefix());
            if (m.compact()) roots.add(base.resolve("resources"));
            Path src = base.resolve("src");
            if (!Files.isDirectory(src)) continue;
            PathUtil.forEachChild(src, (set, attrs) -> {
                if (attrs.isDirectory()) roots.add(set.resolve("resources"));
                return true;
            });
        }
        for (Path r : roots) {
            Path dir = r.resolve(from);
            if (!Files.isDirectory(dir)) continue;
            List<Path> found = new ArrayList<>();
            PathUtil.forEachRegularFile(dir, (f, attrs) -> found.add(f));
            found.sort(null);
            for (Path f : found) {
                String rel = c.root.relativize(f).toString().replace('\\', '/');
                String inside = r.relativize(f).toString().replace('\\', '/');
                String target =
                        c.root.relativize(r).toString().replace('\\', '/') + "/" + to + inside.substring(from.length());
                if (Files.exists(c.root.resolve(target))) {
                    throw new RenameRefusal("Not renamed: " + target + " already exists.");
                }
                plan.rawMoves.put(rel, target);
            }
        }
    }

    /** The package name in strings, config and class lists: declared in a class list, else inferred. */
    private static void packageText(
            SymbolRename.Ctx c, RenamePlan plan, String oldPkg, String newPkg, List<String> textFiles) {
        Set<String> indexed = c.indexed();
        String slashed = oldPkg.replace('.', '/');
        Map<String, String> names = new LinkedHashMap<>();
        names.put(oldPkg, newPkg);
        names.put(slashed, newPkg.replace('.', '/'));
        Pattern p = Pattern.compile(
                "(?<![\\w$.])(?:" + Pattern.quote(oldPkg) + "|" + Pattern.quote(slashed) + ")(?![\\w$])");
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
                        if (!rel.endsWith(".java") && !inString) return null;
                        return new RenameText.Verdict(true, RenamePlan.Grade.INFERRED, "package name in a string");
                    }
                    if (RenameText.doc(rel)) return new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, "doc");
                    if (RenameText.ext(rel).equals("xml")) {
                        return new RenameText.Verdict(true, RenamePlan.Grade.DECLARED, "package or class name in xml");
                    }
                    return new RenameText.Verdict(false, RenamePlan.Grade.INFERRED, RenameText.ext(rel) + " text");
                },
                Set.of());
        for (String rel : textFiles) {
            int at = rel.indexOf("META-INF/services/");
            if (at < 0) continue;
            String name = rel.substring(at + "META-INF/services/".length());
            String pkg = pkgOf(name);
            String to = renamed(pkg, oldPkg, newPkg);
            if (to == null) continue;
            String moved = rel.substring(0, at) + "META-INF/services/" + to + name.substring(pkg.length());
            plan.candidates.add(new RenamePlan.Candidate(
                    true,
                    RenamePlan.Grade.DECLARED,
                    "services file named after a moved type",
                    rel,
                    1,
                    "",
                    List.of(new RenamePlan.Move(rel, moved)),
                    "moves to " + moved));
        }
    }

    /**
     * The bound edits of a package rename or a type move in every indexed Java source: the package
     * line of each moved source; every span of a moved type ({@code types}, by symbol) written with
     * its old package, the package rewritten (and the simple name, when the move renames); on-demand
     * imports and {@code module-info} lines of a renamed package ({@code oldPkg}, {@code null} for a
     * type move); and an import of each moved type a source used without one, by sharing its package
     * or an on-demand import of it.
     */
    static void edits(
            SymbolRename.Ctx c,
            RenamePlan plan,
            Map<String, Moved> types,
            Map<String, String> moving,
            @Nullable String oldPkg,
            @Nullable String newPkg) {
        for (String rel : c.javaFiles) {
            List<ReferenceIndex.Span> spans = c.spans(rel);
            String text = c.text(rel);
            if (text == null) continue;
            String own = packageOf(text);
            String ownAfter = moving.getOrDefault(rel, own);
            List<SymbolRename.Splice> splices = new ArrayList<>();
            Set<String> imports = new TreeSet<>();
            if (moving.containsKey(rel)) {
                Matcher m = PACKAGE_LINE.matcher(stripComments(text));
                if (m.find()) {
                    splices.add(new SymbolRename.Splice(m.start(2), m.end(2), ownAfter));
                    plan.count("package line", 1);
                }
            }
            if (spans != null) {
                Set<String> imported = singleImports(text);
                Set<String> onDemand = onDemandImports(text);
                for (ReferenceIndex.Span sp : spans) {
                    Moved mv = types.get(sp.symbol());
                    String symPkg =
                            sp.symbol().startsWith("T ") ? pkgOf(sp.symbol().substring(2)) : null;
                    if (mv != null) {
                        boolean renames = !simpleOf(mv.oldBinary()).equals(simpleOf(mv.newBinary()));
                        Qualifier q = qualifier(text, sp.start());
                        if (q != null && q.name().equals(mv.oldPkg())) {
                            splices.add(new SymbolRename.Splice(q.start(), q.end(), mv.newPkg()));
                            plan.count("qualified name", 1);
                        } else if (q == null
                                && sp.kind() != 'd'
                                && sp.kind() != 'j'
                                && !mv.newPkg().equals(ownAfter)
                                && !imported.contains(mv.oldBinary().replace('$', '.'))
                                && !(oldPkg != null && onDemand.contains(mv.oldPkg()))
                                && !mv.oldBinary().contains("$")) {
                            imports.add(mv.newBinary());
                        }
                        boolean docQualified = q == null
                                && sp.kind() == 'j'
                                && !mv.newPkg().equals(ownAfter)
                                && !imported.contains(mv.oldBinary().replace('$', '.'))
                                && !(oldPkg != null && onDemand.contains(mv.oldPkg()))
                                && !mv.oldBinary().contains("$");
                        if (docQualified) {
                            // A {@link} that resolved by sharing the old package: written in full, it
                            // still resolves without an import that only a doc comment would use.
                            splices.add(new SymbolRename.Splice(
                                    sp.start(), sp.end(), mv.newBinary().replace('$', '.')));
                            plan.count("doc link", 1);
                        } else if (renames
                                && (sp.kind() != 'j' || text.startsWith(simpleOf(mv.oldBinary()), sp.start()))) {
                            splices.add(new SymbolRename.Splice(sp.start(), sp.end(), simpleOf(mv.newBinary())));
                            plan.count(sp.kind() == 'd' ? "declaration" : "use", 1);
                        }
                    } else if (moving.containsKey(rel)
                            && symPkg != null
                            && symPkg.equals(own)
                            && !own.equals(ownAfter)
                            && !types.containsKey(sp.symbol())
                            && !sp.symbol().contains("$")
                            && qualifier(text, sp.start()) == null
                            && !imported.contains(sp.symbol().substring(2))) {
                        // The moved source used a type of the package it leaves, unimported.
                        imports.add(sp.symbol().substring(2));
                    }
                }
            }
            if (oldPkg != null && newPkg != null) {
                String code = stripComments(text);
                Matcher od = ON_DEMAND.matcher(code);
                while (od.find()) {
                    String to = renamed(od.group(2), oldPkg, newPkg);
                    if (to == null) continue;
                    splices.add(new SymbolRename.Splice(od.start(2), od.end(2), to));
                    plan.count("on-demand import", 1);
                }
                if (rel.endsWith("module-info.java")) {
                    Matcher ml = MODULE_LINE.matcher(code);
                    while (ml.find()) {
                        String to = renamed(ml.group(3), oldPkg, newPkg);
                        if (to == null) continue;
                        splices.add(new SymbolRename.Splice(ml.start(3), ml.end(3), to));
                        plan.count("module-info line", 1);
                    }
                }
            }
            if (splices.isEmpty() && imports.isEmpty()) continue;
            Set<Integer> lines = new TreeSet<>();
            String edited = SymbolRename.apply(text, splices, lines);
            if (!imports.isEmpty()) {
                int at = SymbolRename.importAt(edited);
                List<String> ls = new ArrayList<>(List.of(edited.split("\n", -1)));
                List<String> added = imports.stream()
                        .map(i -> "import " + i.replace('$', '.') + ";")
                        .toList();
                boolean blank = at >= 2 && ls.get(at - 2).strip().startsWith("package ");
                List<String> block = new ArrayList<>();
                if (blank) block.add("");
                block.addAll(added);
                ls.addAll(at - 1, block);
                plan.inserted.computeIfAbsent(rel, k -> new ArrayList<>()).add(new int[] {at, block.size()});
                Set<Integer> shifted = new TreeSet<>();
                for (int n : lines) shifted.add(n >= at ? n + block.size() : n);
                for (int k = 0; k < block.size(); k++) if (!block.get(k).isEmpty()) shifted.add(at + k);
                lines = shifted;
                edited = String.join("\n", ls);
                plan.count("added import", added.size());
            }
            plan.edit(rel, text, edited, lines);
        }
    }

    /** The packages a source imports on demand ({@code import a.b.*;}). */
    static Set<String> onDemandImports(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = ON_DEMAND.matcher(stripComments(text));
        while (m.find()) out.add(m.group(2));
        return out;
    }

    /** The types a source imports one by one ({@code import a.b.Owner;}), dotted. */
    static Set<String> singleImports(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)\\s*;").matcher(stripComments(text));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    // ---- type move ----

    /**
     * Moves type {@code t} to {@code newBinary}'s package (renaming it when the simple name differs),
     * within its module or to {@code targetModule}: refused before anything is written when a use
     * would stop resolving (a package-private type or member used across the new package line, a
     * module that does not see the target).
     */
    static RenamePlan move(
            SymbolRename.Ctx c,
            SymbolRename.Type t,
            String newBinary,
            @Nullable String targetModule,
            List<String> textFiles)
            throws IOException {
        if (t.nested())
            throw new RenameRefusal(
                    "Not moved: " + t.binary().replace('$', '.') + " is nested; move the type that holds it.");
        if (SymbolRename.typeExists(c.graph, newBinary))
            throw new RenameRefusal("Not moved: " + newBinary + " already exists.");
        String text = c.text(t.rel());
        if (text == null || c.spans(t.rel()) == null) {
            throw new RenameRefusal("Not moved: " + t.rel() + " has no index as it stands; build, then move.");
        }
        String oldPkg = t.pkg();
        String newPkg = pkgOf(newBinary);
        String file = t.rel().substring(t.rel().lastIndexOf('/') + 1);
        if (!file.equals(t.simple() + ".java")) {
            throw new RenameRefusal("Not moved: " + t.rel() + " holds " + t.simple()
                    + " beside another top-level type; move that file's public type.");
        }
        List<ReferenceIndex.Span> ownSpans = Objects.requireNonNull(c.spans(t.rel()));
        String root = sourceRoot(t.rel(), oldPkg);
        String targetRoot = root;
        Modules.Module from = c.modules.of(t.rel());
        String fromPrefix = from == null ? "" : from.prefix();
        String toPrefix = targetModule == null
                ? fromPrefix
                : (targetModule.isEmpty() || targetModule.endsWith("/") ? targetModule : targetModule + "/");
        if (!toPrefix.equals(fromPrefix)) {
            String inside = root.substring(fromPrefix.length());
            targetRoot = toPrefix + inside;
        }
        String target = targetRoot + newPkg.replace('.', '/') + "/" + simpleOf(newBinary) + ".java";
        if (Files.exists(RenameStore.inside(c.root, target)))
            throw new RenameRefusal("Not moved: " + target + " already exists.");
        List<String> breaks = breaks(c, t, oldPkg, newPkg, fromPrefix, toPrefix, ownSpans);
        String symbol = "T " + t.binary();
        if (!breaks.isEmpty()) {
            List<String> distinct = breaks.stream().distinct().toList();
            List<String> shown = distinct.subList(0, Math.min(8, distinct.size()));
            throw new RenameRefusal("Not moved, nothing written: the move would break " + distinct.size() + " use"
                    + (distinct.size() == 1 ? "" : "s") + ":\n  " + String.join("\n  ", shown)
                    + (distinct.size() > shown.size() ? "\n  +" + (distinct.size() - shown.size()) + " more" : ""));
        }
        Map<String, Moved> types = new LinkedHashMap<>();
        types.put(symbol, new Moved(t.binary(), newBinary));
        Map<String, String> symbols = new LinkedHashMap<>();
        symbols.put(symbol, "T " + newBinary);
        for (Map.Entry<String, String> e :
                SymbolRename.typeSymbols(c, t.binary(), newBinary).entrySet())
            symbols.putIfAbsent(e.getKey(), e.getValue());
        RenamePlan plan = new RenamePlan(
                t.binary() + " (" + SymbolRename.kind(t) + ")",
                newBinary + (toPrefix.equals(fromPrefix) ? "" : " in " + toPrefix));
        Map<String, String> moving = new TreeMap<>();
        moving.put(t.rel(), newPkg);
        edits(c, plan, types, moving, null, null);
        plan.move(t.rel(), plan.current(t.rel(), text), target);
        plan.symbols.putAll(symbols);
        Map<String, String> words = new LinkedHashMap<>();
        if (!t.simple().equals(simpleOf(newBinary))) words.put(t.simple(), simpleOf(newBinary));
        SymbolRename.unindexedSources(
                c, plan, c.reaching(t.rel()), words.isEmpty() ? Map.of(t.binary(), newBinary) : words);
        SymbolRename.typeText(c, plan, t.binary(), newBinary, textFiles);
        return plan;
    }

    /**
     * What a move of {@code t} from {@code oldPkg} to {@code newPkg} (module {@code fromPrefix} to
     * {@code toPrefix}) would break: a use of a type or member that stops being visible across the
     * new package line, a module that does not see the target, a type the target does not see.
     */
    private static List<String> breaks(
            SymbolRename.Ctx c,
            SymbolRename.Type t,
            String oldPkg,
            String newPkg,
            String fromPrefix,
            String toPrefix,
            List<ReferenceIndex.Span> ownSpans)
            throws IOException {
        List<String> breaks = new ArrayList<>();
        Map<String, GraphRename.Loaded> classes = c.classes();
        boolean leavesPackage = !oldPkg.equals(newPkg);
        String symbol = "T " + t.binary();
        boolean typePublic = "public".equals(Decl.vis(t.node()));
        for (String rel : c.javaFiles) {
            if (rel.equals(t.rel())) continue;
            List<ReferenceIndex.Span> spans = c.spans(rel);
            String other = c.text(rel);
            if (spans == null || other == null) continue;
            String pkg = packageOf(other);
            boolean samePackageAfter = pkg.equals(newPkg);
            for (ReferenceIndex.Span sp : spans) {
                boolean ofType = sp.symbol().equals(symbol)
                        || sp.symbol().startsWith("T " + t.binary() + "$")
                        || sp.symbol().startsWith("F " + t.binary() + "#")
                        || sp.symbol().startsWith("M " + t.binary() + "#");
                if (!ofType) continue;
                if (leavesPackage && !samePackageAfter) {
                    if (sp.symbol().equals(symbol) && !typePublic) {
                        breaks.add(rel + ":" + sp.line() + " uses " + t.simple() + ", which is not public");
                        break;
                    }
                    if ((sp.symbol().startsWith("F ") || sp.symbol().startsWith("M "))
                            && SymbolRename.packagePrivate(classes, sp.symbol())) {
                        breaks.add(rel + ":" + sp.line() + " uses " + RenameProof.show(sp.symbol())
                                + ", which is package-private");
                    }
                }
            }
            if (!toPrefix.equals(fromPrefix)
                    && spans.stream().anyMatch(sp -> sp.symbol().equals(symbol))) {
                Set<String> seeing = c.w.reach().reaching(List.of(toPrefix), c.modules);
                String userModule = c.prefix(rel);
                if (seeing != null && !seeing.contains(userModule)) {
                    breaks.add(rel + " is in " + (userModule.isEmpty() ? "the root module" : userModule)
                            + ", which does not see " + toPrefix);
                }
            }
        }
        for (ReferenceIndex.Span sp : ownSpans) {
            String s = sp.symbol();
            if (leavesPackage
                    && (s.startsWith("F ") || s.startsWith("M "))
                    && !s.startsWith("F " + t.binary())
                    && !s.startsWith("M " + t.binary())
                    && pkgOf(s.substring(2, s.indexOf('#'))).equals(oldPkg)
                    && SymbolRename.packagePrivate(classes, s)) {
                breaks.add(t.rel() + ":" + sp.line() + " uses " + RenameProof.show(s) + ", which is package-private in "
                        + oldPkg);
            }
            if (!toPrefix.equals(fromPrefix) && s.startsWith("T ") && !s.startsWith("T " + t.binary())) {
                List<SymbolRename.Type> used = SymbolRename.types(c.graph, s.substring(2));
                for (SymbolRename.Type u : used) {
                    if (!u.binary().equals(s.substring(2))) continue;
                    String home = c.prefix(u.rel());
                    Set<String> seeing = c.w.reach().reaching(List.of(home), c.modules);
                    if (seeing != null && !seeing.contains(toPrefix)) {
                        breaks.add(t.simple() + " uses " + u.binary() + " of "
                                + (home.isEmpty() ? "the root module" : home) + ", which " + toPrefix
                                + " does not see");
                    }
                }
            }
        }
        if (!toPrefix.equals(fromPrefix) && c.w.reach().reaching(List.of(toPrefix), c.modules) == null) {
            breaks.add("the module graph is unknown here, so a move across modules cannot be checked");
        }
        return breaks;
    }

    /** The source root above a source in package {@code pkg}: {@code src/main/java/} of {@code src/main/java/a/b/X.java}. */
    static String sourceRoot(String rel, String pkg) {
        String dir = rel.substring(0, rel.lastIndexOf('/') + 1);
        String path = pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/";
        return dir.endsWith(path) ? dir.substring(0, dir.length() - path.length()) : dir;
    }

    static List<String> sortedKeys(Map<String, ?> m) {
        List<String> out = new ArrayList<>(m.keySet());
        out.sort(Comparator.naturalOrder());
        return out;
    }
}
