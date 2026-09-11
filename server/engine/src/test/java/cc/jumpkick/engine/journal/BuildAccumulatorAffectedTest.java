// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.test.AffectedTests;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The affected-tests report accumulates on the request's accumulator — one per invocation — so a
 * later run starts from nothing instead of merging into a previous run's rows.
 */
class BuildAccumulatorAffectedTest {

    private static AffectedTests slice(String module, String className) {
        return AffectedTests.ranked(
                List.of(new AffectedTests.ModuleRow(module, "g:" + module, "dirty")),
                List.of(),
                List.of(new AffectedTests.Row(module, className, "test-src", 110)),
                1);
    }

    @Test
    void slices_merge_within_one_request() {
        BuildAccumulator a = new BuildAccumulator("test", "/ws", "g:ws", "cli");
        a.addAffected(slice("api", "com.acme.FooTest"));
        a.addAffected(slice("app", "com.acme.BarTest"));
        AffectedTests merged = requireNonNull(a.affected());
        assertThat(merged.classNames()).containsExactly("com.acme.FooTest", "com.acme.BarTest");
        assertThat(merged.modules()).hasSize(2);
        assertThat(merged.candidateCount()).isEqualTo(2);
    }

    @Test
    void a_fresh_accumulator_carries_nothing_from_earlier_runs() {
        BuildAccumulator first = new BuildAccumulator("test", "/ws", "g:ws", "cli");
        first.addAffected(
                AffectedTests.refused(new AffectedTests.Refuse("manifest", "jk.toml changed"), List.of(), List.of()));
        assertThat(requireNonNull(first.affected()).refused()).isTrue();

        BuildAccumulator second = new BuildAccumulator("test", "/ws", "g:ws", "cli");
        assertThat(second.affected()).isNull();
        second.addAffected(slice("api", "com.acme.FooTest"));
        assertThat(requireNonNull(second.affected()).refused()).isFalse();
        assertThat(requireNonNull(second.affected()).classNames()).containsExactly("com.acme.FooTest");
    }

    @Test
    void a_refuse_slice_wins_within_the_run() {
        BuildAccumulator a = new BuildAccumulator("test", "/ws", "g:ws", "cli");
        a.addAffected(AffectedTests.refused(
                new AffectedTests.Refuse("outside-selection", "dirty e2e test"), List.of(), List.of()));
        a.addAffected(slice("api", "com.acme.FooTest"));
        AffectedTests affected = requireNonNull(a.affected());
        assertThat(affected.refused()).isTrue();
        assertThat(requireNonNull(affected.refuse()).code()).isEqualTo("outside-selection");
    }
}
