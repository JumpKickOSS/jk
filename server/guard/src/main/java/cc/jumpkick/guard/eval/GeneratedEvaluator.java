// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.GeneratedMarkers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.tomlj.TomlTable;

/**
 * {@code generated}: the block between two markers in a file is rendered from a source of truth by a
 * closed template, and the file must carry exactly that rendering. One violation per rule — the
 * block — whose detail is the diff; the sanctioned fix is to paste the rendering, never to edit the
 * source of truth to match the file.
 */
final class GeneratedEvaluator implements Evaluator {

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) throws IOException {
        TomlTable t = rule.table();
        Extractors.Spec source = Extractors.parse(t.getTable("source"));
        TomlTable templateTable = t.getTable("template");
        if (source == null || templateTable == null || Templates.problem(templateTable) != null)
            return Evaluation.failed("source or template names nothing known");
        Templates.Spec template = Templates.parse(templateTable);
        String into = String.valueOf(t.getString("into"));
        String marker = markerName(rule);
        Path file = ctx.root().resolve(into);
        if (!Files.isRegularFile(file)) return Evaluation.failed(into + " does not exist");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        GeneratedMarkers.Block block = GeneratedMarkers.find(lines, marker);
        if (block == null)
            return Evaluation.failed(into + " has no `" + marker + ":start` … `" + marker + ":end` markers");
        Extraction x;
        try {
            x = Extractors.extract(source, rule, ctx);
        } catch (ExtractorException e) {
            return Evaluation.failed("source: " + e.getMessage());
        }
        Map<String, Long> population = Map.of("rows", (long) x.rows().size());
        if (x.rows().isEmpty())
            return new Evaluation(Outcome.BLIND, population, List.of(), x.label() + " yielded no rows to render");
        List<String> rendered = Templates.render(template, x);
        if (rendered.equals(block.body())) return Evaluation.of(population, List.of());
        String detail = "the `" + marker + "` block in " + into + " is not what " + x.label() + " renders ("
                + x.rows().size() + " rows); replace it with the rendering:\n" + diff(block.body(), rendered);
        return Evaluation.of(
                population, List.of(Observation.site("block:" + marker, into, block.startLine() + 1, detail)));
    }

    static String markerName(Rule rule) {
        return rule.table().isString("markers") ? String.valueOf(rule.table().getString("markers")) : rule.id();
    }

    /** A line diff, {@code -} for the file's line and {@code +} for the rendering's, capped so a message stays a message. */
    static String diff(List<String> have, List<String> want) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        int cap = 200;
        while ((i < have.size() || j < want.size()) && out.size() < cap) {
            if (i < have.size() && j < want.size() && have.get(i).equals(want.get(j))) {
                i++;
                j++;
                continue;
            }
            // the file's line survives further down the rendering: the rendering inserted before it
            int ahead = j < want.size() && i < have.size()
                    ? want.subList(j, want.size()).indexOf(have.get(i))
                    : -1;
            int behind = i < have.size() && j < want.size()
                    ? have.subList(i, have.size()).indexOf(want.get(j))
                    : -1;
            if (i >= have.size() || (ahead >= 0 && (behind < 0 || ahead <= behind))) {
                out.add("+ " + want.get(j++));
            } else {
                out.add("- " + have.get(i++));
            }
        }
        int rest = (have.size() - i) + (want.size() - j);
        if (rest > 0) out.add("… " + rest + " more line(s)");
        return String.join("\n", out);
    }
}
