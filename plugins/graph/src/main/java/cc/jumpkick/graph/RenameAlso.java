// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Modules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code also=…}: applies candidates and pins a rename returned, by id ({@code c2}, {@code h1}, a
 * range {@code c3-c7}) or by kind ({@code declared}, {@code inferred}, {@code must}, {@code hazards},
 * {@code all}), each line only while it still reads as it did; a line edited since is skipped and
 * named, never guessed at.
 */
final class RenameAlso {

    private RenameAlso() {}

    /** The ids {@code spec} chooses among {@code p}'s, after the agent has seen them listed. */
    static Set<String> chosen(RenameStore.Pending p, String spec) {
        return chosen(p, spec, false);
    }

    /**
     * The ids {@code spec} chooses among {@code p}'s; an unknown id is refused with the ids there are.
     * {@code unseen} (an {@code also} on the rename's own call, before any list was answered): a kind
     * chooses only the candidates that must change, and the judgment calls (a SQL column, a message key)
     * stay listed for a later call to choose by id. An agent that sends {@code also=all} before reading
     * the list renamed the column the task said to keep.
     */
    static Set<String> chosen(RenameStore.Pending p, String spec, boolean unseen) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : spec.split(",")) {
            String word = raw.strip().toLowerCase(Locale.ROOT);
            if (word.isEmpty()) continue;
            switch (word) {
                case "all" ->
                    p.grades().forEach((id, g) -> {
                        if (!unseen || !id.startsWith("c") || g[1].equals("must")) out.add(id);
                    });
                case "must" ->
                    p.grades().forEach((id, g) -> {
                        if (id.startsWith("c") && g[1].equals("must")) out.add(id);
                    });
                case "hazards", "pins" ->
                    p.grades().keySet().stream()
                            .filter(id -> id.startsWith("h"))
                            .forEach(out::add);
                case "bound", "declared", "inferred" ->
                    p.grades().forEach((id, g) -> {
                        if (g[0].equals(word) && (!unseen || g[1].equals("must"))) out.add(id);
                    });
                default -> {
                    int dash = word.indexOf('-');
                    if (dash > 0) {
                        String a = word.substring(0, dash);
                        String b = word.substring(dash + 1).replaceFirst("^[ch]", "");
                        char kind = a.charAt(0);
                        int from = Integer.parseInt(a.substring(1));
                        int to = Integer.parseInt(b);
                        for (int i = from; i <= to; i++) out.add(String.valueOf(kind) + i);
                    } else {
                        out.add(word);
                    }
                }
            }
        }
        for (String id : out) {
            if (!p.grades().containsKey(id)) {
                throw new IllegalArgumentException("also names " + id + ", which the last rename did not return; it"
                        + " returned " + String.join(",", p.grades().keySet()));
            }
        }
        return out;
    }

    /**
     * A plan of the changes {@code ids} make under {@code root}, read from disk now: what applies, and a
     * note for each change whose line no longer reads as recorded.
     */
    static RenamePlan plan(Path root, RenameStore.Pending p, Set<String> ids) throws IOException {
        RenamePlan plan = new RenamePlan(p.address(), p.to());
        Map<String, List<RenamePlan.Change>> byFile = new TreeMap<>();
        List<RenamePlan.Move> moves = new ArrayList<>();
        for (String id : ids) {
            for (RenamePlan.Change c : p.changes().getOrDefault(id, List.of())) {
                if (c instanceof RenamePlan.Move m) moves.add(m);
                else byFile.computeIfAbsent(c.rel(), k -> new ArrayList<>()).add(c);
            }
        }
        List<String> skipped = new ArrayList<>();
        int applied = 0;
        for (Map.Entry<String, List<RenamePlan.Change>> e : byFile.entrySet()) {
            String rel = e.getKey();
            Path file = RenameStore.inside(root, rel);
            if (!Files.isRegularFile(file)) {
                skipped.add(rel + " (gone)");
                continue;
            }
            String original = Files.readString(file, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(List.of(original.split("\n", -1)));
            // Bottom up, so an insert does not move the lines of the changes after it.
            List<RenamePlan.Change> changes = new ArrayList<>(e.getValue());
            // A line change before an insert at the same line: the insert pushes that line down.
            changes.sort(Comparator.comparingInt(RenameAlso::line)
                    .reversed()
                    .thenComparing(c -> c instanceof RenamePlan.Insert));
            Set<Integer> changed = new TreeSet<>();
            for (RenamePlan.Change c : changes) {
                if (c instanceof RenamePlan.LineChange l) {
                    int i = near(lines, l.line() - 1, l.expected());
                    if (i < 0) {
                        skipped.add(rel + ":" + l.line());
                        continue;
                    }
                    lines.set(i, l.replacement());
                    changed.add(i + 1);
                    applied++;
                } else if (c instanceof RenamePlan.Insert in) {
                    int i = Math.max(0, Math.min(lines.size(), in.line() - 1));
                    List<String> added = List.of(in.text().split("\n", -1));
                    lines.addAll(i, added);
                    applied++;
                    Set<Integer> shifted = new TreeSet<>();
                    for (int n : changed) shifted.add(n >= in.line() ? n + added.size() : n);
                    changed.clear();
                    changed.addAll(shifted);
                    for (int k = 0; k < added.size(); k++) changed.add(in.line() + k);
                }
            }
            if (!changed.isEmpty()) plan.edit(rel, original, String.join("\n", lines), changed);
        }
        for (RenamePlan.Move m : moves) {
            Path from = RenameStore.inside(root, m.rel());
            if (!Files.isRegularFile(from) || Files.exists(RenameStore.inside(root, m.to()))) {
                skipped.add(m.rel() + " (not moved)");
                continue;
            }
            plan.move(m.rel(), plan.current(m.rel(), Files.readString(from, StandardCharsets.UTF_8)), m.to());
            applied++;
        }
        plan.count("chosen change", applied);
        if (!skipped.isEmpty()) {
            plan.notes.add("Skipped, edited since the rename (apply by hand): " + String.join(", ", skipped));
        }
        return plan;
    }

    /**
     * Folds the candidates and pins {@code spec} chooses into {@code plan} before anything is written,
     * for {@code also} on the rename's own call ({@code also=declared}): the chosen ones are applied
     * with the bound edits and leave the lists. A kind chooses no judgment call here: none was listed yet.
     */
    static void fold(Path root, RenamePlan plan, String spec) throws IOException {
        List<RenamePlan.Candidate> ordered = plan.ordered();
        Map<String, String[]> grades = new LinkedHashMap<>();
        Map<String, List<RenamePlan.Change>> changes = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            RenamePlan.Candidate c = ordered.get(i);
            grades.put("c" + (i + 1), new String[] {c.grade().word(), c.must() ? "must" : "may"});
            changes.put("c" + (i + 1), c.changes());
        }
        for (int i = 0; i < plan.hazards.size(); i++) {
            grades.put("h" + (i + 1), new String[] {"hazard", "may"});
            changes.put("h" + (i + 1), plan.hazards.get(i).pin());
        }
        RenameStore.Pending p = new RenameStore.Pending(plan.subject, plan.to, grades, changes);
        Set<String> ids = chosen(p, spec, true);
        Map<String, List<RenamePlan.Change>> byFile = new TreeMap<>();
        List<RenamePlan.Move> moves = new ArrayList<>();
        for (String id : ids) {
            for (RenamePlan.Change c : changes.getOrDefault(id, List.of())) {
                if (c instanceof RenamePlan.Move m) moves.add(m);
                else byFile.computeIfAbsent(c.rel(), k -> new ArrayList<>()).add(c);
            }
        }
        int applied = 0;
        for (Map.Entry<String, List<RenamePlan.Change>> e : byFile.entrySet()) {
            String rel = e.getKey();
            String from = plan.moves.entrySet().stream()
                    .filter(m -> m.getValue().equals(rel))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(rel);
            String original = plan.before.containsKey(from) ? plan.before.get(from) : RenameText.read(root, from);
            if (original == null) continue;
            String current = plan.current(from, original);
            List<String> lines = new ArrayList<>(List.of(current.split("\n", -1)));
            List<RenamePlan.Change> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparingInt(RenameAlso::line)
                    .reversed()
                    .thenComparing(c -> c instanceof RenamePlan.Insert));
            Set<Integer> changed = new TreeSet<>();
            for (RenamePlan.Change c : list) {
                if (c instanceof RenamePlan.LineChange l) {
                    int i = near(lines, l.line() - 1, l.expected());
                    if (i < 0) continue;
                    lines.set(i, l.replacement());
                    changed.add(i + 1);
                    applied++;
                } else if (c instanceof RenamePlan.Insert in) {
                    int i = Math.max(0, Math.min(lines.size(), in.line() - 1));
                    List<String> added = List.of(in.text().split("\n", -1));
                    lines.addAll(i, added);
                    applied++;
                    for (int k = 0; k < added.size(); k++) changed.add(in.line() + k);
                }
            }
            plan.edit(from, original, String.join("\n", lines), changed);
        }
        for (RenamePlan.Move m : moves) {
            String original = RenameText.read(root, m.rel());
            if (original == null || Files.exists(RenameStore.inside(root, m.to()))) continue;
            plan.move(m.rel(), plan.current(m.rel(), original), m.to());
            applied++;
        }
        List<RenamePlan.Candidate> keep = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) if (!ids.contains("c" + (i + 1))) keep.add(ordered.get(i));
        List<RenamePlan.Hazard> keptHazards = new ArrayList<>();
        for (int i = 0; i < plan.hazards.size(); i++)
            if (!ids.contains("h" + (i + 1))) keptHazards.add(plan.hazards.get(i));
        plan.candidates.clear();
        plan.candidates.addAll(keep);
        plan.hazards.clear();
        plan.hazards.addAll(keptHazards);
        plan.count("chosen change", applied);
    }

    /** The answer for an {@code also} call: what it applied, in the shape a rename answers. */
    static List<String> answer(Modules modules, RenamePlan plan, Set<String> ids) {
        List<String> out = new ArrayList<>();
        out.add("Applied " + String.join(",", ids) + ": " + plan.edits() + " change" + (plan.edits() == 1 ? "" : "s")
                + " in " + plan.after.size() + (plan.after.size() == 1 ? " file." : " files."));
        for (Map.Entry<String, Set<Integer>> e : plan.lines.entrySet()) {
            out.add("  " + e.getKey() + (e.getValue().isEmpty() ? "" : ":" + RenamePlan.numbers(e.getValue())));
        }
        out.addAll(plan.notes);
        out.add(EditedTests.next(modules, plan.after.keySet(), true));
        return out;
    }

    /**
     * The index of the line reading {@code expected}: {@code at} itself, else the one such line within
     * a few lines of it (a pin applied in an earlier call inserted lines above); -1 for none or two.
     */
    static int near(List<String> lines, int at, String expected) {
        if (at >= 0 && at < lines.size() && lines.get(at).equals(expected)) return at;
        int found = -1;
        for (int i = Math.max(0, at - 5); i <= Math.min(lines.size() - 1, at + 5); i++) {
            if (!lines.get(i).equals(expected)) continue;
            if (found >= 0) return -1;
            found = i;
        }
        return found;
    }

    private static int line(RenamePlan.Change c) {
        return switch (c) {
            case RenamePlan.LineChange l -> l.line();
            case RenamePlan.Insert in -> in.line();
            case RenamePlan.Move m -> 0;
        };
    }
}
