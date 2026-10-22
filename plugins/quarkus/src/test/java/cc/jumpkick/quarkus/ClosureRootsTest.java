// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.quarkus.ClosureRoots.PomDependency;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Only the artifacts no other locked POM reaches are declared to Quarkus as direct. */
class ClosureRootsTest {

    private static LockedClosure.Artifact locked(String ga) {
        String[] parts = ga.split(":");
        return new LockedClosure.Artifact(
                parts[0], parts[1], "3.40.1", Path.of("/store/repos/central/" + parts[1] + ".jar"), false);
    }

    @Test
    void an_extension_reached_through_another_is_not_a_root() {
        var closure = LockedClosure.of(List.of(
                locked("io.quarkus:quarkus-jdbc-postgresql"),
                locked("io.quarkus:quarkus-datasource"),
                locked("io.quarkus:quarkus-devservices"),
                locked("org.postgresql:postgresql")));
        Map<String, List<PomDependency>> poms = Map.of(
                "quarkus-jdbc-postgresql",
                        List.of(
                                new PomDependency("io.quarkus", "quarkus-datasource", "", false),
                                new PomDependency("org.postgresql", "postgresql", "compile", false)),
                "quarkus-datasource",
                        List.of(new PomDependency("io.quarkus", "quarkus-devservices", "compile", false)));

        var reached = ClosureRoots.reachedFromAnother(closure, a -> poms.getOrDefault(a.artifact(), List.of()));

        assertThat(reached)
                .containsExactlyInAnyOrder(
                        "io.quarkus:quarkus-datasource", "io.quarkus:quarkus-devservices", "org.postgresql:postgresql")
                .doesNotContain("io.quarkus:quarkus-jdbc-postgresql");
    }

    @Test
    void optional_and_test_dependencies_do_not_reach() {
        var closure = LockedClosure.of(List.of(locked("a:root"), locked("a:opt"), locked("a:testonly")));
        var reached = ClosureRoots.reachedFromAnother(
                closure,
                a -> a.artifact().equals("root")
                        ? List.of(
                                new PomDependency("a", "opt", "", true),
                                new PomDependency("a", "testonly", "test", false))
                        : List.of());

        assertThat(reached).isEmpty();
    }
}
