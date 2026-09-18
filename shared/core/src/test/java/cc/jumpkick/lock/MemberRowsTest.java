// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Scope;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A member partition replaces the workspace's row of its coordinate in the scopes it carries and
 * no other: a coordinate the member's main classpath pins to its own version is still read from the
 * workspace's row on the processor path.
 */
class MemberRowsTest {

    private static final String GUAVA = "com.google.guava:guava:jar:";

    @Test
    void a_main_partition_leaves_the_coordinate_s_processor_row_in_place() {
        Lockfile.Artifact mainPlain = row("33.5.0-android", Scope.MAIN);
        Lockfile.Artifact mainPartition = row("33.5.0-android", Scope.MAIN).withMembers(List.of("examples/business"));
        Lockfile.Artifact processorPlain = row("33.5.0-jre", Scope.PROCESSOR);
        Lockfile.Artifact other = new Lockfile.Artifact(
                "com.google.errorprone:error_prone_core:jar:",
                "2.46.0",
                "central+",
                "sha256:cc",
                "error_prone_core-2.46.0.jar",
                List.of(Scope.PROCESSOR),
                List.of());
        List<Lockfile.Artifact> all = List.of(mainPlain, mainPartition, processorPlain, other);

        assertThat(MemberRows.narrow(all, "examples/business"))
                .as("the member reads its own main row, and the processor row nobody partitioned")
                .containsExactly(mainPartition, processorPlain, other);
        assertThat(MemberRows.narrow(all, "examples/other"))
                .as("every other member reads the workspace's rows")
                .containsExactly(mainPlain, processorPlain, other);
    }

    @Test
    void a_partition_that_spans_a_scope_replaces_the_plain_row_of_that_scope() {
        Lockfile.Artifact mainPlain = row("1.0", Scope.MAIN);
        Lockfile.Artifact testPlain = row("1.0", Scope.TEST);
        Lockfile.Artifact both = row("2.0", Scope.MAIN, Scope.TEST).withMembers(List.of("lib"));

        assertThat(MemberRows.narrow(List.of(mainPlain, testPlain, both), "lib"))
                .containsExactly(both);
    }

    private static Lockfile.Artifact row(String version, Scope... scopes) {
        return new Lockfile.Artifact(
                GUAVA,
                version,
                "central+",
                "sha256:" + version,
                "guava-" + version + ".jar",
                List.of(scopes),
                List.of());
    }
}
