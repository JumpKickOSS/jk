// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Modules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * One rename's answer, whatever it renames (a method, a field, a type, a package, a type moved): the
 * edits it applied, each one a use the compiler bound; the uses it did not apply, as candidates with
 * an id, the grade of the edge that found them and the reason, applied only on request ({@code
 * also=c1,c3}); the hazards, a change in behaviour no compile error shows, each with the pin that
 * keeps the old behaviour; and what the proof after the edit checks.
 *
 * <p>Grades, as the graph's edges carry them: {@code bound}, a compiler resolved the use to the
 * symbol (javac's reference index, or the compiled classes where the file's uses are exactly the
 * ones they count); {@code declared}, a file whose format names the symbol by its full name (a
 * services file, Spring's imports and factories, {@code jk.toml}); {@code inferred}, a match by name
 * (a template's bean path, a string, a Kotlin or Groovy source javac did not index). Only bound
 * edits are applied without being asked.
 */
final class RenamePlan {

    /** Candidates quoted one per line before the rest are counted. */
    static final int SHOWN = 40;

    /** The widest quoted line. */
    static final int QUOTE_WIDTH = 160;

    /** How a candidate was found: the graph's edge grades. */
    enum Grade {
        BOUND,
        DECLARED,
        INFERRED;

        String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One change to the tree. */
    sealed interface Change permits LineChange, Insert, Move {
        String rel();
    }

    /** Line {@code line} of {@code rel}, which reads {@code expected}, becomes {@code replacement}. */
    record LineChange(String rel, int line, String expected, String replacement) implements Change {}

    /** {@code text} (one or more whole lines, no trailing newline) inserted before line {@code line} of {@code rel}. */
    record Insert(String rel, int line, String text) implements Change {}

    /** {@code rel} moves to {@code to}. */
    record Move(String rel, String to) implements Change {}

    /**
     * A use not applied: {@code must} when the code breaks or misbehaves unless it changes (a
     * template's bean path, a services file), else a judgment call (a message key, a SQL column, a
     * test named after a type); its grade, why it was found, where, the line quoted, and the changes
     * {@code also} makes for it ({@code hint} instead when no edit fits, as a test class to rename in a
     * call of its own).
     */
    record Candidate(
            boolean must,
            Grade grade,
            String reason,
            String rel,
            int line,
            String quote,
            List<Change> changes,
            @Nullable String hint) {}

    /** A change in behaviour no compile error shows, and the pin that keeps the old behaviour ({@code also=h1}). */
    record Hazard(String text, List<Change> pin) {}

    final String subject;
    final String to;

    /** Each file's text before the rename, as read; a file the rename creates is absent. */
    final Map<String, String> before = new TreeMap<>();

    /** Each changed file's text after the rename, by its new path. */
    final Map<String, String> after = new TreeMap<>();

    /** Old path → new path for every file the rename moves. */
    final Map<String, String> moves = new TreeMap<>();

    /** Files moved byte for byte, unedited (the resources under a renamed package's path): old path → new. */
    final Map<String, String> rawMoves = new TreeMap<>();

    /** Lines a bound edit inserted (an import), by the file's old path: each {@code {line, count}}. */
    final Map<String, List<int[]>> inserted = new TreeMap<>();

    /** Where line {@code line} of {@code rel} as it was stands after the plan's inserts. */
    int shifted(String rel, int line) {
        int out = line;
        for (int[] at : inserted.getOrDefault(rel, List.of())) {
            if (line >= at[0]) out += at[1];
        }
        return out;
    }

    /** The changed lines, by the file's new path. */
    final Map<String, Set<Integer>> lines = new TreeMap<>();

    final List<Candidate> candidates = new ArrayList<>();
    final List<Hazard> hazards = new ArrayList<>();

    /** Lines said after the edits and before the candidates (what else was renamed, refusals of parts). */
    final List<String> notes = new ArrayList<>();

    /** Symbol before → symbol after, for the proof: each new symbol must have exactly the old one's references. */
    final Map<String, String> symbols = new LinkedHashMap<>();

    /** The bound edits, by kind word ({@code declaration}, {@code use}, {@code doc link}, …). */
    final Map<String, Integer> counts = new LinkedHashMap<>();

    /** The module and test sets with no compiled classes that may hold uses: the answer is not complete. */
    final List<String> unseen = new ArrayList<>();

    /** The line that says what to test next, or {@code null}. */
    @Nullable
    String next;

    RenamePlan(String subject, String to) {
        this.subject = subject;
        this.to = to;
    }

    void count(String kind, int n) {
        if (n > 0) counts.merge(kind, n, Integer::sum);
    }

    int edits() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Records {@code rel}'s new text, read as {@code original}, and the lines that changed. */
    void edit(String rel, String original, String text, Set<Integer> changed) {
        before.putIfAbsent(rel, original);
        String target = moves.getOrDefault(rel, rel);
        after.put(target, text);
        lines.computeIfAbsent(target, k -> new TreeSet<>()).addAll(changed);
    }

    /** Moves {@code rel} (read as {@code original}) to {@code to}, carrying any edit made to it. */
    void move(String rel, String original, String to) {
        before.putIfAbsent(rel, original);
        String text = after.remove(rel);
        Set<Integer> had = lines.remove(rel);
        moves.put(rel, to);
        after.put(to, text == null ? original : text);
        if (had != null) lines.put(to, had);
        else lines.computeIfAbsent(to, k -> new TreeSet<>());
    }

    /** The text {@code rel} has under this plan so far: edited, else {@code original}. */
    String current(String rel, String original) {
        String text = after.get(moves.getOrDefault(rel, rel));
        return text == null ? original : text;
    }

    boolean empty() {
        return after.isEmpty() && rawMoves.isEmpty() && candidates.isEmpty() && hazards.isEmpty();
    }

    /** The candidates and hazards in the order their ids are given: must before judgment, then by file and line. */
    List<Candidate> ordered() {
        // One candidate per line: two searches matching one line would make edits of the same line.
        Map<String, Candidate> first = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            boolean moves = c.changes().stream().anyMatch(ch -> ch instanceof Move);
            first.putIfAbsent(c.rel() + ":" + c.line() + (c.changes().isEmpty() || moves ? c.reason() : ""), c);
        }
        List<Candidate> out = new ArrayList<>(first.values());
        out.sort(Comparator.comparing((Candidate c) -> !c.must())
                .thenComparing(Candidate::rel)
                .thenComparingInt(Candidate::line)
                .thenComparing(Candidate::reason));
        return out;
    }

    /**
     * The answer: what was renamed and where, the candidates by id, the hazards by id. {@code proof}
     * is the line the check after the edit adds, when it ran ({@code null} before).
     */
    List<String> render(Modules modules) {
        List<String> out = new ArrayList<>();
        int edits = edits();
        if (edits > 0 || !after.isEmpty()) {
            List<String> parts = new ArrayList<>();
            counts.forEach((k, n) -> parts.add(n + " " + k + (n == 1 ? "" : plural(k))));
            String what = parts.isEmpty() ? "" : String.join(", ", parts) + ", ";
            out.add("Renamed " + subject + " to " + to + ": " + what + "in " + files(after.size())
                    + (moves.isEmpty() ? "" : " (" + moved(moves.size()) + ")") + "; each one javac or the"
                    + " compiled classes bound.");
            for (Map.Entry<String, Set<Integer>> e : lines.entrySet()) {
                String from = moves.entrySet().stream()
                        .filter(m -> m.getValue().equals(e.getKey()))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null);
                out.add("  " + (from == null ? "" : from + " -> ") + e.getKey()
                        + (e.getValue().isEmpty() ? "" : ":" + numbers(e.getValue())));
            }
            if (!rawMoves.isEmpty()) {
                out.add("  " + rawMoves.size() + " resource" + (rawMoves.size() == 1 ? "" : "s")
                        + " moved with the package, unedited");
            }
        } else {
            out.add("Nothing renamed for " + subject + ": no use javac or the compiled classes bound.");
        }
        out.addAll(notes);
        List<Candidate> ordered = ordered();
        List<Candidate> must = ordered.stream().filter(Candidate::must).toList();
        List<Candidate> may = ordered.stream().filter(c -> !c.must()).toList();
        int id = 0;
        if (!must.isEmpty()) {
            out.add("Not applied, and should change too (" + grades(must) + "); apply with also=" + ids(1, must.size())
                    + ":");
            id = list(out, must, id);
        }
        // Hazards and the next step before the judgment calls: a long list must not crowd them out.
        for (int i = 0; i < hazards.size(); i++) {
            Hazard h = hazards.get(i);
            out.add("h" + (i + 1) + " hazard: " + h.text()
                    + (h.pin().isEmpty() ? "" : " also=h" + (i + 1) + " pins it."));
        }
        if (next != null) out.add(next);
        if (!unseen.isEmpty() && (next == null || !next.contains("Not complete"))) {
            // Calls made where nothing compiled are not renamed or proven: the rename is not done.
            out.add("Not checked: these modules may call it but have no compiled classes. The rename is not done until"
                    + " the uses above in " + String.join(", ", unseen) + " are applied or "
                    + (unseen.size() == 1 ? "it compiles." : "they compile."));
        }
        if (!may.isEmpty()) {
            out.add("Judgment, left as they are unless chosen (also=c" + (id + 1) + ",…):");
            grouped(out, may, id);
        }
        return out;
    }

    /** Judgment candidates; three or more of one reason in one file share a line: {@code c9-c18 … :25,26,… (10)}. */
    private static void grouped(List<String> out, List<Candidate> set, int from) {
        int id = from;
        int shown = 0;
        for (int i = 0; i < set.size(); ) {
            Candidate c = set.get(i);
            int j = i;
            while (j + 1 < set.size()
                    && set.get(j + 1).rel().equals(c.rel())
                    && set.get(j + 1).reason().equals(c.reason())) {
                j++;
            }
            int n = j - i + 1;
            if (shown == SHOWN) {
                out.add("  +" + (set.size() - i) + " more (also=c" + (id + 1) + "-c" + (from + set.size())
                        + " still applies them by number)");
                return;
            }
            if (n >= 3) {
                Set<Integer> at = new TreeSet<>();
                for (int k = i; k <= j; k++) at.add(set.get(k).line());
                out.add("  c" + (id + 1) + "-c" + (id + n) + " " + c.grade().word() + ", " + c.reason() + ": " + c.rel()
                        + ":" + numbers(at) + " (" + n + ") | " + clip(c.quote()));
            } else {
                for (int k = i; k <= j; k++) {
                    Candidate d = set.get(k);
                    out.add("  c" + (id + 1 + k - i) + " " + d.grade().word() + ", " + d.reason() + ": " + d.rel()
                            + ":" + d.line() + (d.quote().isEmpty() ? "" : " | " + clip(d.quote()))
                            + (d.hint() == null ? "" : "  (" + d.hint() + ")"));
                }
            }
            id += n;
            shown++;
            i = j + 1;
        }
    }

    private static int list(List<String> out, List<Candidate> set, int from) {
        int id = from;
        for (Candidate c : set) {
            id++;
            if (id - from > SHOWN) {
                out.add("  +" + (set.size() - SHOWN) + " more (also=" + (from + SHOWN + 1) + "-" + (from + set.size())
                        + " still applies them by number)");
                return from + set.size();
            }
            out.add("  c" + id + " " + c.grade().word() + ", " + c.reason() + ": " + c.rel() + ":" + c.line()
                    + (c.quote().isEmpty() ? "" : " | " + clip(c.quote()))
                    + (c.hint() == null ? "" : "  (" + c.hint() + ")"));
        }
        return id;
    }

    private static String grades(List<Candidate> set) {
        Set<String> words = new TreeSet<>();
        for (Candidate c : set) words.add(c.grade().word());
        return String.join(", ", words);
    }

    private static String ids(int from, int to) {
        if (from == to) return "c" + from;
        if (to - from == 1) return "c" + from + ",c" + to;
        return "c" + from + "-c" + to;
    }

    static String clip(String line) {
        String s = line.strip();
        return s.length() > QUOTE_WIDTH ? s.substring(0, QUOTE_WIDTH) + "…" : s;
    }

    private static String plural(String kind) {
        return kind.endsWith("s") ? "es" : "s";
    }

    private static String files(int n) {
        return n + (n == 1 ? " file" : " files");
    }

    private static String moved(int n) {
        return n + (n == 1 ? " moved" : " moved");
    }

    static String numbers(Set<Integer> lines) {
        List<String> out = new ArrayList<>();
        for (int n : lines) {
            if (out.size() == GraphRename.LISTED_LINES) {
                out.add("…");
                break;
            }
            out.add(String.valueOf(n));
        }
        return String.join(",", out);
    }
}
