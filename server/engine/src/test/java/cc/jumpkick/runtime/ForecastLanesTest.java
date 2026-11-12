// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForecastLanesTest {

    @TempDir
    Path root;

    private JkBuild manifest;

    @BeforeEach
    void manifest() throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "g"
                name = "n"
                version = "1"
                """);
        manifest = JkBuildParser.parse(root.resolve("jk.toml"));
    }

    private BuildGraph.BuildUnit unit(String name) {
        return new BuildGraph.BuildUnit(root.resolve(name), manifest, "g:" + name, BuildGraph.Origin.MODULE);
    }

    private Path dir(String name) {
        return root.resolve(name);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void every_module_is_visited_once_after_its_prereqs(int lanes) {
        // a ← b ← d, a ← c ← d, c ← e: a diamond with a tail.
        List<BuildGraph.BuildUnit> units = List.of(unit("a"), unit("b"), unit("c"), unit("d"), unit("e"));
        Map<Path, Set<Path>> order = new LinkedHashMap<>();
        order.put(dir("b"), Set.of(dir("a")));
        order.put(dir("c"), Set.of(dir("a")));
        order.put(dir("d"), Set.of(dir("b"), dir("c")));
        order.put(dir("e"), Set.of(dir("c"), root.resolve("outside")));
        List<Path> visited = Collections.synchronizedList(new ArrayList<>());
        ForecastLanes.walk(units, order, lanes, u -> visited.add(u.dir()));
        assertThat(visited).containsExactlyInAnyOrder(dir("a"), dir("b"), dir("c"), dir("d"), dir("e"));
        for (var e : order.entrySet()) {
            for (Path prereq : e.getValue()) {
                if (!visited.contains(prereq)) continue; // outside the graph
                assertThat(visited.indexOf(prereq)).isLessThan(visited.indexOf(e.getKey()));
            }
        }
    }

    @Test
    void a_chain_module_starts_while_an_unrelated_slow_one_still_runs() throws Exception {
        // Walked in waves, b waited for the slowest module of a's wave; here it waits for a alone.
        List<BuildGraph.BuildUnit> units = List.of(unit("a"), unit("slow"), unit("b"));
        Map<Path, Set<Path>> order = Map.of(dir("b"), Set.of(dir("a")));
        CountDownLatch bRan = new CountDownLatch(1);
        Map<Path, Boolean> slowSawB = new ConcurrentHashMap<>();
        ForecastLanes.walk(units, order, 2, u -> {
            if (u.dir().equals(dir("b"))) bRan.countDown();
            if (u.dir().equals(dir("slow"))) {
                try {
                    slowSawB.put(u.dir(), bRan.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        assertThat(slowSawB).containsEntry(dir("slow"), true);
    }

    @Test
    void a_failed_visit_is_raised_and_its_dependents_never_start() {
        List<BuildGraph.BuildUnit> units = List.of(unit("a"), unit("b"), unit("c"), unit("d"));
        Map<Path, Set<Path>> order = Map.of(dir("b"), Set.of(dir("a")), dir("d"), Set.of(dir("c")));
        Set<Path> visited = ConcurrentHashMap.newKeySet();
        assertThatThrownBy(() -> ForecastLanes.walk(units, order, 4, u -> {
                    if (u.dir().equals(dir("a"))) throw new IllegalStateException("boom");
                    visited.add(u.dir());
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        assertThat(visited).doesNotContain(dir("b"));
    }

    @Test
    void modules_a_cycle_leaves_unreachable_are_not_visited() {
        List<BuildGraph.BuildUnit> units = List.of(unit("a"), unit("x"), unit("y"));
        Map<Path, Set<Path>> order = Map.of(dir("x"), Set.of(dir("y")), dir("y"), Set.of(dir("x")));
        Set<Path> visited = ConcurrentHashMap.newKeySet();
        ForecastLanes.walk(units, order, 4, u -> visited.add(u.dir()));
        assertThat(visited).containsExactly(dir("a"));
    }
}
