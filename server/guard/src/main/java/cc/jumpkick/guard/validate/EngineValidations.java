// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.validate;

import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.schema.Lane;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** The validations each lane runs beside its rules, and how they present. */
public final class EngineValidations {

    /** What {@code jk guard explain} says about a validation. */
    public record Info(String code, String lanes, String what, String why) {}

    public static final List<Info> ALL = List.of(new Info(
            TierPartition.CODE,
            "model, module",
            "the test-tag tier table ([test] + [profiles.*]) partitions its vocabulary, and every compiled @Tag is a tag a tier owns",
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

    /** One module lane: its compiled tests' tags against the table. */
    public static List<Fault> module(Path root, String module, @Nullable FactsIndex tests) {
        if (tests == null) return List.of();
        return TierPartition.tagFaults(TierPartition.table(root), tests, module);
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
