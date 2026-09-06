// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.guard.schema.Lane;
import java.util.EnumMap;
import java.util.Map;

/**
 * Kind → evaluator. A kind with no evaluator loads (the schema is complete on day one) and reports
 * {@code unsupported}, never {@code clean}.
 */
public final class Evaluators {

    private static final Map<Kind, Evaluator> BY_KIND = new EnumMap<>(Kind.class);

    static {
        installBuiltins();
    }

    private static void installBuiltins() {
        BY_KIND.put(Kind.FORBID, new ForbidEvaluator());
        BY_KIND.put(Kind.TEXT, new TextEvaluator());
        BY_KIND.put(Kind.VOCABULARY, new VocabularyEvaluator());
    }

    /** Test seam: drop every registration and reinstall the shipped evaluators. */
    public static synchronized void restoreDefaults() {
        BY_KIND.clear();
        installBuiltins();
    }

    private Evaluators() {}

    public static synchronized void register(Kind kind, Evaluator evaluator) {
        BY_KIND.put(kind, evaluator);
    }

    public static synchronized Evaluator forKind(Kind kind) {
        Evaluator e = BY_KIND.get(kind);
        if (e != null) return e;
        return (rule, ctx) -> Evaluation.unsupported("kind " + kind.id() + " has no evaluator yet");
    }

    public static synchronized boolean supported(Kind kind) {
        return BY_KIND.containsKey(kind);
    }

    /** The lane a rule runs in; the kind's default unless one of its keys moves it. */
    public static Lane laneOf(Rule rule) {
        return switch (rule.kind()) {
            case LAYERS -> {
                String edges = rule.table().isString("edges")
                        ? String.valueOf(rule.table().getString("edges"))
                        : "manifest";
                yield edges.equals("manifest") ? Lane.MODEL : Lane.WORKSPACE;
            }
            case CYCLES -> {
                if (rule.table().contains("over")) yield Lane.MODEL;
                yield Boolean.TRUE.equals(rule.table().getBoolean("across-modules")) ? Lane.WORKSPACE : Lane.MODULE;
            }
            case METRIC -> {
                String measure = String.valueOf(rule.table().getString("measure"));
                if (measure.startsWith("coverage.") || measure.equals("jar-size") || measure.equals("native-size"))
                    yield Lane.OUTPUT;
                if (measure.equals("methods")
                        || measure.equals("params")
                        || measure.equals("public-members")
                        || measure.equals("cyclomatic")) yield Lane.MODULE;
                yield Lane.TREE;
            }
            default -> rule.kind().lane();
        };
    }
}
