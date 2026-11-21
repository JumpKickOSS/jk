// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.validate;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.TestClassFacts;
import cc.jumpkick.guard.schema.Lane;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** The validations each lane runs beside its rules, and how they present. */
public final class EngineValidations {

    /** What {@code jk guard explain} says about a validation. */
    public record Info(String code, String lanes, String what, String why) {}

    public static final List<Info> ALL = List.of(new Info(
            TierPartition.CODE,
            "model, module",
            "the root's tag suites ([test.suites.*]) partition the tag vocabulary, and every @Tag the project wrote is a tag a tier owns",
            "a tag no tier runs is a test that never executes; a tag no tier owns runs in the fast tier by default"));

    /** Reserved codes: a user rule may not take one. */
    public static final Set<String> CODES = Set.of(TierPartition.CODE);

    private EngineValidations() {}

    public static boolean applies(Lane lane) {
        return lane == Lane.MODEL || lane == Lane.MODULE;
    }

    /** The model lane: the partition itself. */
    public static List<Fault> model(Path root) {
        return TierPartition.partitionFaults(TierPartition.table(root));
    }

    /**
     * One module lane: its compiled tests' tags against the table, a supertype or composed annotation
     * the tests do not hold read by {@code outside}, by internal name; {@code fromSources} says which
     * of those the workspace compiled, the only ones whose tags the ownership arm judges.
     */
    public static List<Fault> module(
            Path root,
            String module,
            @Nullable FactsIndex tests,
            Function<String, Optional<ClassFacts>> outside,
            Predicate<String> fromSources) {
        if (tests == null) return List.of();
        TestClassFacts written =
                new TestClassFacts(List.of(tests), n -> fromSources.test(n) ? outside.apply(n) : Optional.empty());
        return TierPartition.tagFaults(
                TierPartition.table(root), tests, new TestClassFacts(List.of(tests), outside), written, module);
    }

    public static String render(Fault f) {
        StringBuilder sb = new StringBuilder("GUARD ").append(f.code()).append("  engine validation\n");
        sb.append("  Observed: ").append(f.observed()).append('\n');
        sb.append("  Instead:  ").append(f.instead()).append('\n');
        for (Info i : ALL)
            if (i.code().equals(f.code()))
                sb.append("  Why:      ").append(i.why()).append('\n');
        sb.append("  Explain:  jk guard explain ").append(f.code());
        return sb.toString();
    }
}
