// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one evaluator found: the outcome before baseline reconciliation, the population it
 * examined (by unit: {@code classes}, {@code files}, …), every violation as an observation, and a
 * note explaining a non-clean outcome in the rule's words.
 */
public record Evaluation(Outcome outcome, Map<String, Long> population, List<Observation> observations, String note) {

    public Evaluation {
        population = Map.copyOf(new TreeMap<>(population));
        observations = List.copyOf(observations);
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
