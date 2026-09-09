// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one evaluator found: the outcome before baseline reconciliation, the population it
 * examined (by unit: {@code classes}, {@code files}, …), every violation as an observation, and a
 * note explaining a non-clean outcome in the rule's words. {@code bites} is the evidence the rule
 * can fire at all — an owner site, a current site, a {@code hit} that matched — without which a clean
 * pass is a rule looking at nothing (PRD §4.6, must bite).
 */
public record Evaluation(
        Outcome outcome, Map<String, Long> population, List<Observation> observations, String note, boolean bites) {

    public Evaluation {
        population = Map.copyOf(new TreeMap<>(population));
        observations = List.copyOf(observations);
    }

    /**
     * Without bite evidence spelled out: a rule that examined something bites by default. The kinds
     * whose clean pass proves nothing on its own ({@code forbid}, {@code text}) set it explicitly.
     */
    public Evaluation(Outcome outcome, Map<String, Long> population, List<Observation> observations, String note) {
        this(outcome, population, observations, note, !observations.isEmpty() || examined(population) > 0);
    }

    /** The same evaluation with bite evidence decided by the evaluator. */
    public Evaluation withBite(boolean bite) {
        return new Evaluation(outcome, population, observations, note, bite);
    }

    static long examined(Map<String, Long> population) {
        long n = 0;
        for (long v : population.values()) n += v;
        return n;
    }

    /** Clean or violations, decided by whether anything was observed; blind when nothing was examined. */
    public static Evaluation of(Map<String, Long> population, List<Observation> observations) {
        long examined = 0;
        for (long n : population.values()) examined += n;
        if (examined == 0) return new Evaluation(Outcome.BLIND, population, List.of(), "the rule examined nothing");
        return new Evaluation(
                observations.isEmpty() ? Outcome.CLEAN : Outcome.VIOLATIONS, population, observations, "");
    }

    public static Evaluation unsupported(String note) {
        return new Evaluation(Outcome.UNSUPPORTED, Map.of(), List.of(), note);
    }

    /**
     * A module-lane rule over test classes in a module that has none: clean, nothing examined, no
     * bite evidence. Not {@link #notEvaluated}: the inputs were produced, there is simply nothing in
     * this module for the rule to have an opinion on, and a workspace has modules like that.
     */
    public static Evaluation noTestClasses() {
        return new Evaluation(
                Outcome.CLEAN, Map.of("test-classes", 0L), List.of(), "this module has no test classes", false);
    }

    public static Evaluation notEvaluated(String note) {
        return new Evaluation(Outcome.NOT_EVALUATED, Map.of(), List.of(), note);
    }

    public static Evaluation ownerMissing(String note) {
        return new Evaluation(Outcome.OWNER_MISSING, Map.of(), List.of(), note);
    }

    public static Evaluation failed(String note) {
        return new Evaluation(Outcome.SCANNER_FAILED, Map.of(), List.of(), note);
    }
}
