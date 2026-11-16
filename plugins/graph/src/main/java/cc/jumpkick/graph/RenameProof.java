// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Modules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The check after a rename's edit, once the engine has compiled the modules it touched: every
 * renamed symbol's new name has exactly the references its old name had, at the same lines (in each
 * moved file's new path), and the old name has none left. What javac resolves after the edit is
 * the proof; a rename whose proof fails, or whose modules do not compile, is undone whole.
 */
final class RenameProof {

    private RenameProof() {}

    /** References shown when the sets differ. */
    private static final int SHOWN = 5;

    /** Every workspace Java source a build compiles: the files whose index can hold a reference. */
    static List<String> javaFiles(CodeGraph graph) {
        Set<String> out = new TreeSet<>();
        Modules modules = graph.modules();
        for (GraphNode n : graph.nodes()) {
            String rel = n.sourceFile();
            if (!rel.endsWith(".java") || rel.contains("/resources/")) continue;
            if (ClassSources.set(modules, rel) == null) continue;
            out.add(rel);
        }
        return List.copyOf(out);
    }

    /**
     * Each of {@code symbols}' references in {@code files}, as {@code rel \t line} → count; a file
     * whose index does not describe it as it stands goes to {@code unindexed}.
     */
    static Map<String, Map<String, Integer>> refs(
            Path root, Modules modules, Collection<String> files, Set<String> symbols, List<String> unindexed) {
        Map<String, Map<String, Integer>> out = new HashMap<>();
        for (String s : symbols) out.put(s, new TreeMap<>());
        Map<String, List<Path>> dirs = new HashMap<>();
        for (String rel : files) {
            Modules.Module m = modules.of(rel);
            String prefix = m == null ? "" : m.prefix();
            Path file = ReferenceIndex.file(
                    dirs.computeIfAbsent(prefix, p -> ReferenceIndex.dirs(root, p)), rel.substring(prefix.length()));
            String text = read(root.resolve(rel));
            String index = file == null ? null : read(file);
            List<ReferenceIndex.Span> spans = text == null || index == null
                    ? null
                    : ReferenceIndex.of(index.lines().toList(), text);
            if (spans == null || index == null) {
                unindexed.add(rel);
                continue;
            }
            String body = index;
            if (symbols.stream().noneMatch(s -> body.contains("\n=" + s + "\n"))) continue;
            for (ReferenceIndex.Span sp : spans) {
                Map<String, Integer> at = out.get(sp.symbol());
                if (at != null) at.merge(rel + "\t" + sp.line(), 1, Integer::sum);
            }
        }
        return out;
    }

    /**
     * The references the new symbols must have after the edit: each old symbol's now, at the same
     * lines, under each file's path after {@code moves}.
     */
    static List<String[]> expected(Path root, Modules modules, Collection<String> files, RenamePlan plan) {
        Map<String, String> symbols = plan.symbols;
        Map<String, String> moves = plan.moves;
        Map<String, Map<String, Integer>> now = refs(root, modules, files, symbols.keySet(), new ArrayList<>());
        List<String[]> out = new ArrayList<>();
        symbols.forEach((old, renamed) -> now.getOrDefault(old, Map.of()).forEach((at, n) -> {
            int tab = at.indexOf('\t');
            String was = at.substring(0, tab);
            String rel = moves.getOrDefault(was, was);
            String line = String.valueOf(plan.shifted(was, Integer.parseInt(at.substring(tab + 1))));
            for (int i = 0; i < n; i++) out.add(new String[] {renamed, rel, line});
        }));
        // The imports a move added are new references by design: any count there is expected.
        plan.inserted.forEach((was, blocks) -> {
            for (int[] b : blocks) {
                for (int k = 0; k < b[1]; k++) {
                    out.add(new String[] {"*", moves.getOrDefault(was, was), String.valueOf(b[0] + k)});
                }
            }
        });
        return out;
    }

    /** The check's answer, after which the rename is either settled or undone whole. */
    static String verify(Path root, Path out, CodeGraph graph) throws IOException {
        RenameStore.Proof p = RenameStore.readProof(out);
        if (p == null) return "";
        Map<String, Map<String, Integer>> want = new HashMap<>();
        for (String renamed : p.symbols().values()) want.put(renamed, new TreeMap<>());
        Set<String> free = new TreeSet<>();
        for (String[] r : p.refs()) {
            if (r[0].equals("*")) {
                free.add(r[1] + "\t" + r[2]);
                continue;
            }
            want.computeIfAbsent(r[0], k -> new TreeMap<>()).merge(r[1] + "\t" + r[2], 1, Integer::sum);
        }
        Set<String> all = new TreeSet<>(p.symbols().keySet());
        all.addAll(p.symbols().values());
        List<String> unindexed = new ArrayList<>();
        Map<String, Map<String, Integer>> have = refs(root, graph.modules(), javaFiles(graph), all, unindexed);
        List<String> wrong = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, String> e : p.symbols().entrySet()) {
            String old = e.getKey();
            String renamed = e.getValue();
            if (old.equals(renamed)) continue;
            Map<String, Integer> left = have.getOrDefault(old, Map.of());
            left.forEach((at, n) -> wrong.add(show(old) + " is still used at " + at.replace('\t', ':')));
            Map<String, Integer> w = want.getOrDefault(renamed, Map.of());
            Map<String, Integer> h = have.getOrDefault(renamed, Map.of());
            total += w.values().stream().mapToInt(Integer::intValue).sum();
            Set<String> keys = new TreeSet<>(w.keySet());
            keys.addAll(h.keySet());
            for (String at : keys) {
                int a = w.getOrDefault(at, 0);
                int b = h.getOrDefault(at, 0);
                String rel = at.substring(0, at.indexOf('\t'));
                if (a == b || b == 0 && unindexed.contains(rel) || a == 0 && free.contains(at)) continue;
                wrong.add(show(renamed) + " at " + at.replace('\t', ':') + ": " + b + " reference" + (b == 1 ? "" : "s")
                        + ", " + a + " expected" + (b > a ? " (something else now binds to the new name)" : ""));
            }
        }
        if (!wrong.isEmpty()) {
            List<String> restored = RenameStore.undo(root, out);
            List<String> lines = new ArrayList<>();
            lines.add("Rolled back, nothing is changed (" + files(restored.size()) + " restored): after the edit javac"
                    + " resolves the new name differently than it resolved the old one:");
            wrong.stream().limit(SHOWN).forEach(w -> lines.add("  " + w));
            if (wrong.size() > SHOWN) lines.add("  +" + (wrong.size() - SHOWN) + " more");
            return String.join("\n", lines);
        }
        RenameStore.settle(out);
        Set<String> notProven = new TreeSet<>();
        for (String[] r : p.refs()) {
            if (!r[0].equals("*") && unindexed.contains(r[1])) notProven.add(r[1]);
        }
        // A proof covers what compiled: sets that may call the old name and have no classes are named, never proven.
        String scope = p.unseen().isEmpty()
                ? "Proven: "
                : "Proven only where it compiled; not for " + String.join(", ", p.unseen())
                        + ", which may call it and has no compiled classes, so the rename is not done: ";
        if (p.symbols().isEmpty()) {
            return scope + "it compiles."
                    + (notProven.isEmpty() ? "" : " Not proven for " + String.join(", ", notProven) + ".");
        }
        String names = p.symbols().size() == 1
                ? show(p.symbols().values().iterator().next()) + " has the " + total + " reference"
                        + (total == 1 ? "" : "s") + " "
                        + show(p.symbols().keySet().iterator().next()) + " had"
                : "the " + p.symbols().size() + " renamed symbols have the " + total + " references they had";
        return scope + "it compiles, and " + names + ", at the same lines; nothing else binds to the new name."
                + (notProven.isEmpty()
                        ? ""
                        : " Not proven for " + notProven.size() + " file" + (notProven.size() == 1 ? "" : "s")
                                + " with no index as they stand: " + String.join(", ", notProven) + ".");
    }

    /** Undoes the last rename whose modules did not compile; the answer's line. */
    static String undo(Path root, Path out, String why) throws IOException {
        List<String> restored = RenameStore.undo(root, out);
        if (restored.isEmpty()) return "";
        return "Rolled back, nothing is changed (" + files(restored.size()) + " restored): after the edit "
                + relative(root, why)
                + ". If that line is a candidate above, rename again with also=<its id>; otherwise fix it first.";
    }

    private static String files(int n) {
        return n + (n == 1 ? " file" : " files");
    }

    /** {@code why} with the workspace's absolute paths and file URIs cut to root-relative ones. */
    static String relative(Path root, String why) {
        String out = why;
        List<String> roots = new ArrayList<>();
        roots.add(root.toAbsolutePath().normalize().toString());
        try {
            roots.add(root.toRealPath().toString());
        } catch (IOException | RuntimeException unresolved) {
            // The plain path is all there is.
        }
        for (String r : List.copyOf(roots)) {
            if (r.startsWith("/private/")) roots.add(r.substring("/private".length()));
        }
        for (String r : roots) out = out.replace("file://" + r + "/", "").replace(r + "/", "");
        // javac's line repeats the file it names: "A.java:3: A.java:3:47: error" becomes "A.java:3:47: error".
        return out.replaceAll("([\\w./-]+):(\\d+): \\1:\\2:", "$1:$2:");
    }

    /** {@code T a.b.Owner} → {@code a.b.Owner}; {@code F a.B#x} → {@code B.x}; {@code M a.B#m(I)V} → {@code B.m}. */
    static String show(String symbol) {
        String body = symbol.length() > 2 ? symbol.substring(2) : symbol;
        int hash = body.indexOf('#');
        if (hash < 0) return body.replace('$', '.');
        String owner = body.substring(0, hash);
        owner = owner.substring(Math.max(owner.lastIndexOf('.'), owner.lastIndexOf('$')) + 1);
        String member = body.substring(hash + 1);
        int paren = member.indexOf('(');
        return owner + "." + (paren < 0 ? member : member.substring(0, paren));
    }

    private static @Nullable String read(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
