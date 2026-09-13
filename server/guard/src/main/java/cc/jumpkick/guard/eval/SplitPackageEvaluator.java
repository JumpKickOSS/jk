// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code split-package}: one module owns each package. Read from every module's index in the
 * workspace lane; a package two modules both compile is a violation unless an {@code allow} names
 * the package with the reason it is legal. The fingerprint carries the module pair, so a split that
 * gains a third module is a new site; an allow whose package is no longer split is stale, red.
 */
final class SplitPackageEvaluator implements Evaluator {

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) {
        Map<String, Set<String>> modulesByPackage = new TreeMap<>();
        for (var e : WorkspaceModel.classModules(ctx.root(), ctx.modules()).entrySet()) {
            String pkg = Descriptors.packageOf(e.getKey());
            if (!rule.applies(e.getValue())) continue;
            modulesByPackage.computeIfAbsent(pkg, k -> new TreeSet<>()).add(e.getValue());
        }
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);
        List<Observation> sites = new ArrayList<>();
        for (var e : modulesByPackage.entrySet()) {
            if (e.getValue().size() < 2) continue;
            Allow a = null;
            for (Allow candidate : rule.allow()) {
                if (candidate.in().equals(e.getKey()) || Rule.globMatches(candidate.in(), e.getKey())) a = candidate;
            }
            if (a != null) {
                allowUsed.put(a, true);
                continue;
            }
            sites.add(Observation.site(
                    e.getKey() + " | " + String.join(" + ", e.getValue()),
                    null,
                    0,
                    e.getKey() + " is declared by " + String.join(" + ", e.getValue())
                            + " — one module must own it, or an allow entry must say why not"));
        }
        Map<String, Long> population = Map.of("packages", (long) modulesByPackage.size());
        if (modulesByPackage.isEmpty())
            return new Evaluation(Outcome.BLIND, population, List.of(), "no module index to read");
        List<String> stale = new ArrayList<>();
        for (var e : allowUsed.entrySet())
            if (!e.getValue()) stale.add(e.getKey().in());
        if (!stale.isEmpty()) {
            return new Evaluation(
                    Outcome.STALE_ALLOW,
                    population,
                    sites,
                    "allow entries name packages that are no longer split: " + String.join(", ", stale));
        }
        return Evaluation.of(population, sites);
    }
}
