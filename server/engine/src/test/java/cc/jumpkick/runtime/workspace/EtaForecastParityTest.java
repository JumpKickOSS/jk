// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.BuildGraph;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The forecast and the executor have to describe the same build. Each case here pins one place they
 * did not, every one of which showed up as the same symptom — a suite priced for a runner count no
 * build would hand it.
 */
class EtaForecastParityTest {

    /**
     * Width is the widest simultaneously-ready wave, not the count of dirty modules. A spine edit
     * makes a whole dependency chain dirty, and the executor still runs it one module at a time —
     * so each module gets the whole machine, not a thirteenth of it.
     */
    @Test
    void a_dependency_chain_is_one_module_wide() {
        Path a = Path.of("/w/a");
        Path b = Path.of("/w/b");
        Path c = Path.of("/w/c");
        Map<Path, Set<Path>> chain = Map.of(a, Set.of(), b, Set.of(a), c, Set.of(b));

        assertThat(BuildGraph.maxReadyWidth(Set.of(a, b, c), chain))
                .as("three dirty modules, but never two at once")
                .isEqualTo(1);
    }

    @Test
    void independent_modules_are_as_wide_as_they_are_many() {
        Path a = Path.of("/w/a");
        Path b = Path.of("/w/b");
        Path c = Path.of("/w/c");
        Map<Path, Set<Path>> none = Map.of(a, Set.of(), b, Set.of(), c, Set.of());

        assertThat(BuildGraph.maxReadyWidth(Set.of(a, b, c), none)).isEqualTo(3);
    }

    /** Edges that leave the dirty set are not barriers: a clean prereq is already built. */
    @Test
    void a_clean_prereq_does_not_serialize_the_dirty_set() {
        Path clean = Path.of("/w/clean");
        Path x = Path.of("/w/x");
        Path y = Path.of("/w/y");
        Map<Path, Set<Path>> edges = Map.of(x, Set.of(clean), y, Set.of(clean));

        assertThat(BuildGraph.maxReadyWidth(Set.of(x, y), edges)).isEqualTo(2);
    }
}
