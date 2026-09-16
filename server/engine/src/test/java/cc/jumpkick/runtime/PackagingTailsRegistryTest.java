// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TaskNames#PACKAGING_TAILS} names the steps that require only {@code package-jar} and so
 * run <em>beside</em> {@code run-tests} instead of after it. Two things read that set — the
 * scheduler's cost model prices {@code prefix + max(test, tail)} from it, and the reader of
 * {@code jk explain} believes the result — so a tail the set forgets is silently priced as if it
 * were serial with the suite, which is the exact mistake the level executor used to make for real.
 *
 * <p>The set cannot simply be derived from the planner, because {@code PlannerTails} adds its
 * leaves under per-module conditions ({@code assembly = true}, {@code [native] enabled}, …). What
 * can be checked is the property that puts a step in the set at all: its {@code requires()} is
 * {@code package-jar} and nothing more.
 */
class PackagingTailsRegistryTest {

    @TempDir
    Path tmp;

    @Test
    void every_named_tail_requires_only_the_jar() {
        Path cache = tmp.resolve("cache");
        Path lock = tmp.resolve("jk-lock.toml");
        Map<String, Task> built = Map.of(
                TaskNames.PACKAGE_ASSEMBLY, PlannerTails.assemblyStep(cache, lock),
                TaskNames.PACKAGE_SOURCES, PlannerTails.sourcesStep(cache),
                TaskNames.PACKAGE_JAVADOC, PlannerTails.javadocStep(cache),
                TaskNames.NATIVE_IMAGE,
                        PlannerNative.nativeStep(tmp, cache, lock, tmp.resolve("jdks"), null, null, List.of()));

        // package-minified needs a full Inputs to construct, so it is asserted by count rather
        // than built: add a sixth tail and this line fails until someone checks it here too.
        assertThat(TaskNames.PACKAGING_TAILS).hasSize(5).containsAll(built.keySet());

        for (var e : built.entrySet()) {
            assertThat(TaskNames.PACKAGING_TAILS)
                    .as("%s requires only the jar, so it overlaps the suite and must be a known tail", e.getKey())
                    .contains(e.getKey());
            assertThat(e.getValue().name()).isEqualTo(e.getKey());
            assertThat(e.getValue().requires())
                    .as(
                            "%s must not gain an edge on run-tests — that is the serialization this models away",
                            e.getKey())
                    .containsExactly(TaskNames.PACKAGE_JAR);
        }
    }

    /**
     * {@code run-tests} and {@code package-jar} are deliberately NOT tails: the suite is the other
     * branch, and the jar is the prefix both branches wait on. Putting either in the set would
     * double-count it.
     */
    @Test
    void the_suite_and_the_jar_are_not_tails() {
        assertThat(TaskNames.PACKAGING_TAILS)
                .doesNotContain(TaskNames.RUN_TESTS, TaskNames.PACKAGE_JAR, TaskNames.COMPILE_TEST);
    }
}
