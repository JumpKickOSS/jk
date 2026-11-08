// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.FeatureSelection;
import cc.jumpkick.model.Scope;
import cc.jumpkick.wire.protocol.WhyReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk why} on a row only the test roots jk adds reach (the Jupiter engine beside a declared
 * Jupiter API, the Platform launcher) names the rule that added the root, then the path from it.
 */
class GraphOpsAddedRootsWhyTest {

    private static final String SOURCE = "central+https://repo.maven.apache.org/maven2/";

    @TempDir
    Path dir;

    @BeforeEach
    void workspace() throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "root"
                version = "1.0.0"

                [workspace]
                modules = ["svc"]
                """);
        Files.createDirectories(dir.resolve("svc"));
        Files.writeString(dir.resolve("svc/jk.toml"), """
                group = "com.example"
                name = "svc"
                version = "1.0.0"

                [test-dependencies]
                junit-jupiter-api = { group = "org.junit.jupiter", version = "5.12.2" }
                """);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        row(
                                "org.junit.jupiter:junit-jupiter-api",
                                "5.12.2",
                                "org.opentest4j:opentest4j:jar:@1.3.0",
                                "org.junit.platform:junit-platform-commons:jar:@1.12.2"),
                        row(
                                "org.junit.jupiter:junit-jupiter-engine",
                                "5.12.2",
                                "org.junit.jupiter:junit-jupiter-api:jar:@5.12.2",
                                "org.junit.platform:junit-platform-engine:jar:@1.12.2"),
                        row(
                                "org.junit.platform:junit-platform-launcher",
                                "1.12.2",
                                "org.junit.platform:junit-platform-engine:jar:@1.12.2"),
                        row(
                                "org.junit.platform:junit-platform-engine",
                                "1.12.2",
                                "org.opentest4j:opentest4j:jar:@1.3.0",
                                "org.junit.platform:junit-platform-commons:jar:@1.12.2"),
                        row("org.junit.platform:junit-platform-commons", "1.12.2"),
                        row("org.opentest4j:opentest4j", "1.3.0")));
        Files.writeString(LockPaths.lockFile(dir), LockfileWriter.render(lock));
    }

    @Test
    void an_engine_jk_added_names_the_rule_and_is_the_root_of_its_path() {
        WhyReport report = why("junit-jupiter-engine");

        assertThat(report.error()).isNull();
        assertThat(report.matchNames()).containsExactly("org.junit.jupiter:junit-jupiter-engine");
        assertThat(report.paths()).containsExactly("org.junit.jupiter:junit-jupiter-engine@5.12.2");
        assertThat(report.rootReasonOf(0))
                .isEqualTo("junit-jupiter-api 5.12.2 is on the test graph without an engine (Surefire parity)");
        assertThat(report.rootOf(0)).isEqualTo("com.example:svc");
        assertThat(WhyReport.decode(report.encode()).rootReasonOf(0)).isEqualTo(report.rootReasonOf(0));
        assertThat(WhyReport.addedByJkLine(requireNonNull(report.rootReasonOf(0)), report.rootOf(0)))
                .isEqualTo("added by jk for com.example:svc: junit-jupiter-api 5.12.2 is on the test graph"
                        + " without an engine (Surefire parity)");
    }

    @Test
    void a_row_reached_through_added_roots_lists_each_with_its_rule() {
        WhyReport report = why("org.junit.platform:junit-platform-engine");

        assertThat(report.paths())
                .containsExactlyInAnyOrder(
                        "org.junit.jupiter:junit-jupiter-engine@5.12.2>org.junit.platform:junit-platform-engine@1.12.2",
                        "org.junit.platform:junit-platform-launcher@1.12.2"
                                + ">org.junit.platform:junit-platform-engine@1.12.2");
        for (int p = 0; p < report.paths().size(); p++) {
            String expected = report.paths().get(p).startsWith("org.junit.platform:junit-platform-launcher")
                    ? "jk test runs every suite through the JUnit Platform launcher"
                    : "junit-jupiter-api 5.12.2 is on the test graph without an engine (Surefire parity)";
            assertThat(report.rootReasonOf(p)).isEqualTo(expected);
        }
    }

    @Test
    void a_declared_root_carries_no_rule() {
        WhyReport report = why("junit-jupiter-api");

        assertThat(report.paths()).containsExactly("org.junit.jupiter:junit-jupiter-api@5.12.2");
        assertThat(report.rootReasonOf(0)).isNull();
    }

    private WhyReport why(String query) {
        return GraphOps.why(dir, query, FeatureSelection.DEFAULTS, project -> {
            throw new IllegalStateException("no repositories in this test");
        });
    }

    private static Lockfile.Artifact row(String ga, String version, String... deps) {
        return new Lockfile.Artifact(
                ga + ":jar:", version, SOURCE, "sha256:00", null, List.of(Scope.TEST), List.of(deps));
    }
}
