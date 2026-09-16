// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class JUnitLineConflictTest {

    private static Lockfile.Artifact row(String ga, String version) {
        return new Lockfile.Artifact(
                ga + ":jar:", version, "central+https://repo", null, null, List.of(Scope.TEST), List.of(), null, null);
    }

    @Test
    void an_exact_pin_holding_one_artifact_back_names_both_versions_and_the_pin() {
        List<Lockfile.Artifact> lock = List.of(
                row("org.junit.jupiter:junit-jupiter", "6.1.3"),
                row("org.junit.jupiter:junit-jupiter-api", "5.0.0"),
                row("org.junit.jupiter:junit-jupiter-engine", "6.1.3"),
                row("org.junit.platform:junit-platform-launcher", "6.1.3"),
                row("org.opentest4j:opentest4j", "1.3.0"));
        List<Dependency> declared = List.of(
                new Dependency("org.junit.jupiter:junit-jupiter", VersionSelector.parse("6.1.3")),
                new Dependency("org.junit.jupiter:junit-jupiter-api", VersionSelector.parse("=5.0.0")));

        JUnitLineConflict.Conflict c = Objects.requireNonNull(JUnitLineConflict.describe(declared, lock));

        assertThat(c.coordinate()).isEqualTo("org.junit.jupiter:junit-jupiter-api");
        assertThat(c.text())
                .startsWith("Two versions of the org.junit.jupiter line on the test classpath:")
                .contains("5.0.0: org.junit.jupiter:junit-jupiter-api (declared =5.0.0 in [test-dependencies])")
                .contains("6.1.3: org.junit.jupiter:junit-jupiter (declared 6.1.3 in [test-dependencies]),"
                        + " org.junit.jupiter:junit-jupiter-engine")
                .doesNotContain("opentest4j");
    }

    @Test
    void one_version_per_line_is_no_conflict() {
        List<Lockfile.Artifact> lock = List.of(
                row("org.junit.jupiter:junit-jupiter", "6.1.3"),
                row("org.junit.jupiter:junit-jupiter-api", "6.1.3"),
                row("org.junit.platform:junit-platform-launcher", "6.1.3"));

        assertThat(JUnitLineConflict.describe(List.of(), lock)).isNull();
    }

    @Test
    void without_a_pin_the_odd_version_out_is_the_coordinate_to_ask_about() {
        List<Lockfile.Artifact> lock = List.of(
                row("org.junit.platform:junit-platform-launcher", "6.1.3"),
                row("org.junit.platform:junit-platform-engine", "1.9.0"),
                row("org.junit.platform:junit-platform-commons", "6.1.3"));

        JUnitLineConflict.Conflict c = Objects.requireNonNull(JUnitLineConflict.describe(List.of(), lock));

        assertThat(c.coordinate()).isEqualTo("org.junit.platform:junit-platform-engine");
    }

    @Test
    void lock_names_drop_type_and_classifier() {
        assertThat(JUnitLineConflict.groupArtifact("org.junit.jupiter:junit-jupiter-api:jar:"))
                .isEqualTo("org.junit.jupiter:junit-jupiter-api");
        assertThat(JUnitLineConflict.groupArtifact("g:a")).isEqualTo("g:a");
    }
}
