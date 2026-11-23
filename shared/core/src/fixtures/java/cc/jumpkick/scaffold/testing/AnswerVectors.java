// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold.testing;

import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldDefault;
import cc.jumpkick.form.FieldKind;
import cc.jumpkick.form.Option;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The answer vectors a template is scaffolded with, generated from its form so that a new option
 * demands a new vector by itself: the defaults, then a pairwise set in which every option of every
 * field appears at least once, and every two options of two different fields appear together at
 * least once (not the full product).
 *
 * <p>A {@code choice} contributes one dimension of its options, a {@code bool} one of
 * {@code false, true}, and a {@code multi} one {@code false, true} dimension per option, so each
 * option is picked and left out somewhere. A {@code text}, {@code search} or {@code path} field has
 * no options to enumerate and keeps its default; one with no default cannot be answered here and is
 * refused. Generation is deterministic (a greedy cover in field and option order), so the vectors
 * and their labels are stable between runs and machines, and a snapshot can be keyed by label.
 */
public final class AnswerVectors {

    /** The label of the vector that answers nothing a default answers. */
    public static final String DEFAULTS = "defaults";

    private AnswerVectors() {}

    /**
     * One vector: its label (the answers that differ from the defaults, in field order, or
     * {@link #DEFAULTS}) and the answers, typed as the form takes them ({@code String},
     * {@code Boolean}, {@code List<String>}). The defaults vector answers only the fields that have
     * no default.
     */
    public record Vector(String label, Map<String, Object> answers) {
        public Vector {
            answers = Map.copyOf(answers);
        }
    }

    /** One axis of the cover: a field, or one option of a {@code multi}, and the values it takes. */
    private record Dim(Field field, @Nullable String option, List<Object> values) {}

    /** A vector and the value index it gives each axis. */
    private record Row(Vector vector, int[] values) {}

    /**
     * The vectors for {@code fields}, the template-dependent fields of a new-project form (the layout
     * choice and the template's asks), in form order.
     *
     * @throws IllegalArgumentException for a field with no options to enumerate and no default
     */
    public static List<Vector> of(List<Field> fields) {
        return rows(fields, dims(fields)).stream().map(Row::vector).toList();
    }

    /**
     * Those of {@link #of} that every value of every field appears in at least once: the defaults,
     * then, while a value is missing, the earliest vector that adds the most missing values (a
     * {@code multi} counts each option picked and left out). In the order {@link #of} gives them.
     *
     * @throws IllegalArgumentException as {@link #of}
     */
    public static List<Vector> eachValue(List<Field> fields) {
        List<Dim> dims = dims(fields);
        List<Row> all = rows(fields, dims);
        List<Row> rows = new ArrayList<>(all);
        Set<List<Integer>> missing = new LinkedHashSet<>();
        for (int k = 0; k < dims.size(); k++) {
            for (int v = 0; v < dims.get(k).values().size(); v++) missing.add(List.of(k, v));
        }
        Set<Row> picked = new LinkedHashSet<>();
        while (!rows.isEmpty()) {
            Row best = rows.getFirst();
            int bestGain = gain(best, missing);
            for (Row r : rows) {
                int gain = gain(r, missing);
                if (gain > bestGain) {
                    best = r;
                    bestGain = gain;
                }
            }
            if (bestGain == 0 && !picked.isEmpty()) break;
            rows.remove(best);
            for (int k = 0; k < best.values().length; k++) missing.remove(List.of(k, best.values()[k]));
            picked.add(best);
        }
        return all.stream().filter(picked::contains).map(Row::vector).toList();
    }

    /** How many of {@code missing} the row's values add. */
    private static int gain(Row row, Set<List<Integer>> missing) {
        int gain = 0;
        for (int k = 0; k < row.values().length; k++) {
            if (missing.contains(List.of(k, row.values()[k]))) gain++;
        }
        return gain;
    }

    private static List<Dim> dims(List<Field> fields) {
        List<Dim> dims = new ArrayList<>();
        for (Field f : fields) {
            switch (f.kind()) {
                case CHOICE -> dims.add(new Dim(f, null, List.copyOf(ids(f))));
                case BOOL -> dims.add(new Dim(f, null, List.of(false, true)));
                case MULTI -> {
                    for (Option o : f.options()) dims.add(new Dim(f, o.id(), List.of(false, true)));
                }
                case TEXT, SEARCH, PATH -> {
                    if (!(f.defaultValue() instanceof FieldDefault.Literal)) {
                        throw new IllegalArgumentException(f.key() + " is a "
                                + f.kind().id() + " field with no default: the corpus has no answer to give it");
                    }
                }
            }
        }
        return dims;
    }

    private static List<Row> rows(List<Field> fields, List<Dim> dims) {
        int[] defaults = defaults(dims);
        List<Row> out = new ArrayList<>();
        out.add(new Row(new Vector(DEFAULTS, answers(fields, dims, defaults, true)), defaults));
        Set<String> seen = new LinkedHashSet<>();
        seen.add(key(defaults));
        for (int[] row : cover(dims)) {
            if (!seen.add(key(row))) continue;
            out.add(new Row(new Vector(label(fields, dims, row, defaults), answers(fields, dims, row, false)), row));
        }
        return out;
    }

    /** An answer as a label spells it: a list in brackets, anything else as its text. */
    public static String text(Object value) {
        if (value instanceof List<?> list) {
            return "[" + String.join(",", list.stream().map(String::valueOf).toList()) + "]";
        }
        return String.valueOf(value);
    }

    /**
     * Rows covering every pair of values of two different dimensions, and every value of a lone
     * dimension. Each row starts from the first pair still uncovered, then gives every other
     * dimension the value that covers the most uncovered pairs with what the row already holds; ties
     * go to the earlier value.
     */
    private static List<int[]> cover(List<Dim> dims) {
        int n = dims.size();
        List<int[]> rows = new ArrayList<>();
        if (n == 0) return rows;
        if (n == 1) {
            for (int v = 0; v < dims.getFirst().values().size(); v++) rows.add(new int[] {v});
            return rows;
        }
        Set<List<Integer>> uncovered = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int a = 0; a < dims.get(i).values().size(); a++) {
                    for (int b = 0; b < dims.get(j).values().size(); b++) uncovered.add(List.of(i, a, j, b));
                }
            }
        }
        while (!uncovered.isEmpty()) {
            List<Integer> first = uncovered.iterator().next();
            int[] row = new int[n];
            boolean[] set = new boolean[n];
            row[first.get(0)] = first.get(1);
            set[first.get(0)] = true;
            row[first.get(2)] = first.get(3);
            set[first.get(2)] = true;
            for (int k = 0; k < n; k++) {
                if (set[k]) continue;
                int best = 0;
                int bestGain = -1;
                for (int v = 0; v < dims.get(k).values().size(); v++) {
                    int gain = 0;
                    for (int o = 0; o < n; o++) {
                        if (!set[o]) continue;
                        if (uncovered.contains(o < k ? List.of(o, row[o], k, v) : List.of(k, v, o, row[o]))) gain++;
                    }
                    if (gain > bestGain) {
                        best = v;
                        bestGain = gain;
                    }
                }
                row[k] = best;
                set[k] = true;
            }
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) uncovered.remove(List.of(i, row[i], j, row[j]));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Each dimension's default value: the field's literal default, else (a required field with
     * none) its first value.
     */
    private static int[] defaults(List<Dim> dims) {
        int[] row = new int[dims.size()];
        for (int k = 0; k < dims.size(); k++) {
            Dim d = dims.get(k);
            Object value = d.field().defaultValue() instanceof FieldDefault.Literal literal ? literal.value() : null;
            if (d.option() != null) value = value instanceof List<?> picked && picked.contains(d.option());
            int at = value == null ? -1 : d.values().indexOf(value);
            row[k] = Math.max(at, 0);
        }
        return row;
    }

    private static Map<String, Object> answers(List<Field> fields, List<Dim> dims, int[] row, boolean onlyUndefaulted) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : fields) {
            if (onlyUndefaulted && f.defaultValue() instanceof FieldDefault.Literal) continue;
            List<String> picked = new ArrayList<>();
            boolean present = false;
            for (int k = 0; k < dims.size(); k++) {
                Dim d = dims.get(k);
                if (d.field() != f) continue;
                present = true;
                Object value = d.values().get(row[k]);
                if (d.option() == null) out.put(f.key(), value);
                else if (Boolean.TRUE.equals(value)) picked.add(d.option());
            }
            if (present && f.kind() == FieldKind.MULTI) out.put(f.key(), List.copyOf(picked));
        }
        return out;
    }

    private static String label(List<Field> fields, List<Dim> dims, int[] row, int[] defaults) {
        Map<String, Object> answers = answers(fields, dims, row, false);
        Map<String, Object> standard = answers(fields, dims, defaults, false);
        List<String> parts = new ArrayList<>();
        for (var e : answers.entrySet()) {
            if (!e.getValue().equals(standard.get(e.getKey()))) parts.add(e.getKey() + "=" + text(e.getValue()));
        }
        return String.join(" ", parts);
    }

    private static List<String> ids(Field f) {
        return f.options().stream().map(Option::id).toList();
    }

    private static String key(int[] row) {
        StringBuilder out = new StringBuilder();
        for (int v : row) out.append(v).append(',');
        return out.toString();
    }
}
