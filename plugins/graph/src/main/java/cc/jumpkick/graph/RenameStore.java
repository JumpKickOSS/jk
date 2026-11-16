// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * What a rename keeps between its calls, under the graph's output directory ({@code target/graph/rename/}):
 * the files as they were before its last write ({@code undo/}), so a rename whose proof fails is
 * undone whole; its candidates and pins ({@code pending}), so {@code also=c1,h1} applies them in a
 * later call; and what the proof checks ({@code proof}), whose first line names the modules to
 * compile, the one line the engine reads.
 */
final class RenameStore {

    private RenameStore() {}

    static final String DIR = "rename";
    static final String PROOF = "proof";
    static final String PENDING = "pending";
    static final String UNDO = "undo";

    static Path dir(Path out) {
        return out.resolve(DIR);
    }

    /**
     * Writes {@code plan}'s files under {@code root}, each atomically, then deletes the files moved
     * away; the files as they were go to {@code undo/} before anything is touched, and a failure part
     * way puts them back.
     */
    static void write(Path root, Path out, RenamePlan plan) throws IOException {
        Path undo = dir(out).resolve(UNDO);
        clear(undo);
        Files.createDirectories(undo);
        List<String> manifest = new ArrayList<>();
        int n = 0;
        for (Map.Entry<String, String> e : plan.before.entrySet()) {
            Path blob = undo.resolve("f" + n++);
            Files.writeString(blob, e.getValue(), StandardCharsets.UTF_8);
            manifest.add("restore\t" + e.getKey() + "\t" + blob.getFileName());
        }
        for (String rel : plan.after.keySet()) {
            if (!plan.before.containsKey(rel)) manifest.add("delete\t" + rel);
        }
        for (Map.Entry<String, String> e : plan.rawMoves.entrySet()) {
            manifest.add("unmove\t" + e.getValue() + "\t" + e.getKey());
        }
        AtomicWrites.replace(undo.resolve("manifest"), String.join("\n", manifest) + "\n");
        try {
            for (Map.Entry<String, String> e : plan.after.entrySet()) {
                Path target = inside(root, e.getKey());
                Files.createDirectories(target.getParent());
                AtomicWrites.replace(target, e.getValue());
            }
            for (String rel : plan.moves.keySet()) {
                if (!plan.after.containsKey(rel)) Files.deleteIfExists(inside(root, rel));
            }
            for (Map.Entry<String, String> e : plan.rawMoves.entrySet()) {
                Path to = inside(root, e.getValue());
                Files.createDirectories(to.getParent());
                Files.move(inside(root, e.getKey()), to);
            }
            Set<String> emptied = new TreeSet<>(plan.moves.keySet());
            emptied.addAll(plan.rawMoves.keySet());
            pruneEmptyDirs(root, emptied);
        } catch (IOException | RuntimeException failed) {
            undo(root, out);
            throw failed;
        }
    }

    /** Puts back the files the last write changed, as {@code undo/} holds them; the paths it restored. */
    static List<String> undo(Path root, Path out) throws IOException {
        Path undo = dir(out).resolve(UNDO);
        Path manifest = undo.resolve("manifest");
        if (!Files.isRegularFile(manifest)) return List.of();
        List<String> done = new ArrayList<>();
        Set<String> deleted = new TreeSet<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t");
            if (f.length >= 2 && f[0].equals("delete")) {
                Files.deleteIfExists(inside(root, f[1]));
                deleted.add(f[1]);
            } else if (f.length == 3 && f[0].equals("unmove")) {
                Path from = inside(root, f[1]);
                if (Files.isRegularFile(from)) {
                    Path back = inside(root, f[2]);
                    Files.createDirectories(back.getParent());
                    Files.move(from, back);
                    deleted.add(f[1]);
                }
            }
        }
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t");
            if (f.length == 3 && f[0].equals("restore")) {
                Path target = inside(root, f[1]);
                Files.createDirectories(target.getParent());
                AtomicWrites.replace(target, Files.readString(undo.resolve(f[2]), StandardCharsets.UTF_8));
                done.add(f[1]);
            }
        }
        pruneEmptyDirs(root, deleted);
        clear(undo);
        Files.deleteIfExists(dir(out).resolve(PROOF));
        // The candidates were for the edit just undone: also=<id> now plans the rename again with them.
        Files.deleteIfExists(dir(out).resolve(PENDING));
        return done;
    }

    /** Forgets the undo record: the rename was proven, so it is the tree now. */
    static void settle(Path out) throws IOException {
        clear(dir(out).resolve(UNDO));
        Files.deleteIfExists(dir(out).resolve(PROOF));
    }

    // ---- proof ----

    /**
     * What the check after the edit needs: the module directories to compile (the first line, which
     * the engine reads), each symbol's name before and after, and the references each new symbol must
     * have: the old symbol's, at the same lines, in each file's new path.
     */
    static void writeProof(Path out, Set<String> modules, Map<String, String> symbols, List<String[]> refs)
            throws IOException {
        writeProof(out, modules, symbols, refs, List.of());
    }

    /**
     * As above, with {@code unseen}: the sets that may call what was renamed and have no compiled
     * classes, which neither the edit nor the proof covers.
     */
    static void writeProof(
            Path out, Set<String> modules, Map<String, String> symbols, List<String[]> refs, List<String> unseen)
            throws IOException {
        StringBuilder s = new StringBuilder("modules\t");
        s.append(String.join(
                ",", modules.stream().map(p -> p.isEmpty() ? "." : p).toList()));
        s.append('\n');
        if (!unseen.isEmpty())
            s.append("unseen\t").append(String.join(",", unseen)).append('\n');
        symbols.forEach(
                (a, b) -> s.append("sym\t").append(a).append('\t').append(b).append('\n'));
        for (String[] r : refs)
            s.append("ref\t")
                    .append(r[0])
                    .append('\t')
                    .append(r[1])
                    .append('\t')
                    .append(r[2])
                    .append('\n');
        Path dir = Files.createDirectories(dir(out));
        AtomicWrites.replace(dir.resolve(PROOF), s.toString());
    }

    /** What {@link #writeProof} wrote, or {@code null}. */
    record Proof(List<String> modules, Map<String, String> symbols, List<String[]> refs, List<String> unseen) {}

    static @Nullable Proof readProof(Path out) throws IOException {
        Path file = dir(out).resolve(PROOF);
        if (!Files.isRegularFile(file)) return null;
        List<String> modules = new ArrayList<>();
        Map<String, String> symbols = new LinkedHashMap<>();
        List<String[]> refs = new ArrayList<>();
        List<String> unseen = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            switch (f[0]) {
                case "modules" -> {
                    if (f.length > 1 && !f[1].isEmpty()) {
                        for (String m : f[1].split(",")) modules.add(m.equals(".") ? "" : m);
                    }
                }
                case "sym" -> symbols.put(f[1], f[2]);
                case "unseen" -> unseen.addAll(List.of(f[1].split(",")));
                case "ref" -> refs.add(new String[] {f[1], f[2], f[3]});
                default -> {}
            }
        }
        return new Proof(modules, symbols, refs, unseen);
    }

    // ---- pending candidates ----

    /** Stores {@code plan}'s candidates and hazards for a later {@code also}, keyed by the call that made them. */
    static void writePending(
            Path out, String address, String to, List<RenamePlan.Candidate> ordered, List<RenamePlan.Hazard> hazards)
            throws IOException {
        StringBuilder s = new StringBuilder("call\t")
                .append(esc(address))
                .append('\t')
                .append(esc(to))
                .append('\n');
        for (int i = 0; i < ordered.size(); i++) {
            RenamePlan.Candidate c = ordered.get(i);
            String id = "c" + (i + 1);
            s.append("grade\t")
                    .append(id)
                    .append('\t')
                    .append(c.grade().word())
                    .append('\t')
                    .append(c.must() ? "must" : "may")
                    .append('\n');
            for (RenamePlan.Change ch : c.changes()) s.append(change(id, ch));
        }
        for (int i = 0; i < hazards.size(); i++) {
            String id = "h" + (i + 1);
            s.append("grade\t").append(id).append("\thazard\tmay\n");
            for (RenamePlan.Change ch : hazards.get(i).pin()) s.append(change(id, ch));
        }
        Path dir = Files.createDirectories(dir(out));
        AtomicWrites.replace(dir.resolve(PENDING), s.toString());
    }

    private static String change(String id, RenamePlan.Change ch) {
        return switch (ch) {
            case RenamePlan.LineChange l ->
                "L\t" + id + "\t" + esc(l.rel()) + "\t" + l.line() + "\t" + esc(l.expected()) + "\t"
                        + esc(l.replacement()) + "\n";
            case RenamePlan.Insert in ->
                "I\t" + id + "\t" + esc(in.rel()) + "\t" + in.line() + "\t" + esc(in.text()) + "\n";
            case RenamePlan.Move m -> "M\t" + id + "\t" + esc(m.rel()) + "\t0\t" + esc(m.to()) + "\n";
        };
    }

    /** The candidates a pending record holds: their call, and for each id its grade, tier and changes. */
    record Pending(
            String address, String to, Map<String, String[]> grades, Map<String, List<RenamePlan.Change>> changes) {}

    static @Nullable Pending readPending(Path out) throws IOException {
        Path file = dir(out).resolve(PENDING);
        if (!Files.isRegularFile(file)) return null;
        String address = null;
        String to = null;
        Map<String, String[]> grades = new LinkedHashMap<>();
        Map<String, List<RenamePlan.Change>> changes = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            switch (f[0]) {
                case "call" -> {
                    address = unesc(f[1]);
                    to = unesc(f[2]);
                }
                case "grade" -> grades.put(f[1], new String[] {f[2], f[3]});
                case "L" ->
                    changes.computeIfAbsent(f[1], k -> new ArrayList<>())
                            .add(new RenamePlan.LineChange(
                                    unesc(f[2]), Integer.parseInt(f[3]), unesc(f[4]), unesc(f[5])));
                case "I" ->
                    changes.computeIfAbsent(f[1], k -> new ArrayList<>())
                            .add(new RenamePlan.Insert(unesc(f[2]), Integer.parseInt(f[3]), unesc(f[4])));
                case "M" ->
                    changes.computeIfAbsent(f[1], k -> new ArrayList<>())
                            .add(new RenamePlan.Move(unesc(f[2]), unesc(f[4])));
                default -> {}
            }
        }
        if (address == null || to == null) return null;
        return new Pending(address, to, grades, changes);
    }

    static void clearPending(Path out) throws IOException {
        Files.deleteIfExists(dir(out).resolve(PENDING));
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    static String unesc(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            out.append(
                    switch (n) {
                        case 't' -> '\t';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        default -> n;
                    });
        }
        return out.toString();
    }

    /** {@code rel} under {@code root}; a path that would leave it is refused. */
    static Path inside(Path root, String rel) {
        Path base = root.toAbsolutePath().normalize();
        Path p = base.resolve(rel).normalize();
        if (!p.startsWith(base)) throw new IllegalArgumentException("outside the workspace: " + rel);
        return p;
    }

    /** Removes the directories a move emptied, up to the first that still holds something. */
    private static void pruneEmptyDirs(Path root, Set<String> movedAway) {
        Path base = root.toAbsolutePath().normalize();
        for (String rel : movedAway) {
            Path dir = base.resolve(rel).normalize().getParent();
            while (dir != null && dir.startsWith(base) && !dir.equals(base)) {
                boolean[] any = {false};
                try {
                    PathUtil.forEachChild(dir, (child, attrs) -> {
                        any[0] = true;
                        return false;
                    });
                } catch (IOException gone) {
                    break;
                }
                if (any[0]) break;
                try {
                    Files.delete(dir);
                } catch (IOException kept) {
                    break;
                }
                dir = dir.getParent();
            }
        }
    }

    private static void clear(Path dir) throws IOException {
        if (Files.isDirectory(dir)) PathUtil.deleteRecursivelyOrThrow(dir);
    }

    /** The module prefixes holding {@code rels}. */
    static Set<String> modules(Modules modules, Iterable<String> rels) {
        Set<String> out = new TreeSet<>();
        for (String rel : rels) {
            Modules.Module m = modules.of(rel);
            out.add(m == null ? "" : m.prefix());
        }
        return out;
    }
}
