// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.schema.ExtractorVocabulary;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/** The closed templates of {@code generated}: rows in, the lines of a block out. */
final class Templates {

    private Templates() {}

    /** One parsed template: its name and the single argument. */
    record Spec(String name, List<String> columns, String arg) {}

    static @Nullable String problem(@Nullable TomlTable spec) {
        return ExtractorVocabulary.templateProblem(spec);
    }

    static Spec parse(TomlTable spec) {
        String name = spec.keySet().iterator().next();
        Object v = spec.get(name);
        List<String> columns = new ArrayList<>();
        if (v instanceof TomlArray a) for (int i = 0; i < a.size(); i++) columns.add(String.valueOf(a.get(i)));
        return new Spec(name, columns, v instanceof String s ? s : "");
    }

    /** The block's lines, without the markers. */
    static List<String> render(Spec t, Extraction x) {
        List<String> out = new ArrayList<>();
        switch (t.name()) {
            case "table" -> {
                out.add("| " + String.join(" | ", t.columns()) + " |");
                StringBuilder sep = new StringBuilder("|");
                for (int i = 0; i < t.columns().size(); i++) sep.append("---|");
                out.add(sep.toString());
                for (Extraction.Row r : x.rows()) {
                    List<String> cells = new ArrayList<>();
                    for (String c : t.columns()) cells.add(cell(r.column(c)));
                    out.add("| " + String.join(" | ", cells) + " |");
                }
            }
            case "list" -> {
                for (Extraction.Row r : x.rows()) out.add("- " + (t.arg().isEmpty() ? r.key() : r.column(t.arg())));
            }
            case "arrow-chain" -> out.add(String.join(" -> ", x.keyList()));
            case "toml-array" -> {
                List<String> quoted = new ArrayList<>();
                for (String k : x.keyList())
                    quoted.add("\"" + k.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
                out.add(t.arg() + " = [" + String.join(", ", quoted) + "]");
            }
            case "code-block" -> {
                out.add("```" + t.arg());
                out.addAll(x.keyList());
                out.add("```");
            }
            default -> throw new IllegalArgumentException("unknown template " + t.name());
        }
        return out;
    }

    private static String cell(String v) {
        return v.replace("\r", "").replace("\n", " ").replace("|", "\\|").strip();
    }
}
