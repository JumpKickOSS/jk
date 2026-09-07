// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * {@code parity}: two extractions must agree. The set differences are the violations, the element is
 * the fingerprint; {@code direction = "left-in-right"} asks only that the left be contained. Either
 * side empty is {@code blind}: a parity over nothing proves nothing.
 */
final class ParityEvaluator implements Evaluator {

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) throws IOException {
        Extractors.Spec left = Extractors.parse(rule.table().getTable("left"));
        Extractors.Spec right = Extractors.parse(rule.table().getTable("right"));
        if (left == null || right == null) return Evaluation.failed("left or right names no known extractor");
        String direction =
                rule.table().isString("direction") ? String.valueOf(rule.table().getString("direction")) : "both";
        Extraction l;
        Extraction r;
        try {
            l = Extractors.extract(left, rule, ctx);
        } catch (ExtractorException e) {
            return Evaluation.failed("left: " + e.getMessage());
        }
        try {
            r = Extractors.extract(right, rule, ctx);
        } catch (ExtractorException e) {
            return Evaluation.failed("right: " + e.getMessage());
        }
        Map<String, Long> population = new LinkedHashMap<>();
        population.put("left", (long) l.rows().size());
        population.put("right", (long) r.rows().size());
        if (l.rows().isEmpty() || r.rows().isEmpty()) {
            return new Evaluation(
                    Outcome.BLIND,
                    population,
                    List.of(),
                    (l.rows().isEmpty() ? l.label() : r.label()) + " yielded nothing, so there is nothing to compare");
        }
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);
        List<Observation> out = new ArrayList<>();
        Set<String> lk = l.keys();
        Set<String> rk = r.keys();
        for (String e : new TreeSet<>(lk)) {
            if (rk.contains(e)) continue;
            add(out, allowUsed, rule, e, l, r, "left-only");
        }
        if (direction.equals("both")) {
            for (String e : new TreeSet<>(rk)) {
                if (lk.contains(e)) continue;
                add(out, allowUsed, rule, e, r, l, "right-only");
            }
        }
        List<String> stale = new ArrayList<>();
        for (var e : allowUsed.entrySet())
            if (!e.getValue()) stale.add(e.getKey().in());
        if (!stale.isEmpty()) {
            return new Evaluation(
                    Outcome.STALE_ALLOW, population, out, "allow entries matched nothing: " + String.join(", ", stale));
        }
        return Evaluation.of(population, out);
    }

    private static void add(
            List<Observation> out,
            Map<Allow, Boolean> allowUsed,
            Rule rule,
            String element,
            Extraction in,
            Extraction notIn,
            String side) {
        Allow a = allowing(rule.allow(), element);
        if (a != null) {
            allowUsed.put(a, true);
            return;
        }
        out.add(Observation.site(
                side + ":" + element,
                in.file(),
                0,
                "`" + element + "` is in " + in.label() + " and not in " + notIn.label()));
    }

    private static @Nullable Allow allowing(List<Allow> allow, String element) {
        for (Allow a : allow) if (a.in().equals(element) || Rule.globMatches(a.in(), element)) return a;
        return null;
    }
}
