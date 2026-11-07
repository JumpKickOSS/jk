// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk why} on a row a conditional dependency added names the extension and the condition. */
class GraphOpsConditionalWhyTest {

    private static final String SOURCE = "central+https://repo.maven.apache.org/maven2/";

    @Test
    void a_conditional_row_names_its_declarer_and_condition_beside_the_path(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                quarkus-rest = { group = "io.quarkus", version = "3.40.1" }
                quarkus-kotlin = { group = "io.quarkus", version = "3.40.1" }
                """);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        row("quarkus-rest", List.of("io.quarkus:quarkus-rest-kotlin:jar:@3.40.1")),
                        row("quarkus-kotlin", List.of()),
                        row("quarkus-rest-kotlin", List.of())
                                .withAddedBy(List.of("io.quarkus:quarkus-rest when io.quarkus:quarkus-kotlin"))));
        Files.writeString(LockPaths.lockFile(dir), LockfileWriter.render(lock));

        WhyReport report = GraphOps.why(dir, "quarkus-rest-kotlin", FeatureSelection.DEFAULTS, project -> {
            throw new IllegalStateException("no repositories in this test");
        });

        assertThat(report.error()).isNull();
        assertThat(report.matchNames()).containsExactly("io.quarkus:quarkus-rest-kotlin");
        assertThat(report.addedByOf(0)).containsExactly("io.quarkus:quarkus-rest when io.quarkus:quarkus-kotlin");
        assertThat(report.paths()).anySatisfy(p -> assertThat(p)
                .isEqualTo("io.quarkus:quarkus-rest@3.40.1>io.quarkus:quarkus-rest-kotlin@3.40.1"));
        assertThat(WhyReport.decode(report.encode()).addedByOf(0)).isEqualTo(report.addedByOf(0));
    }

    private static Lockfile.Artifact row(String artifact, List<String> deps) {
        return new Lockfile.Artifact(
                "io.quarkus:" + artifact + ":jar:", "3.40.1", SOURCE, "sha256:00", null, List.of(Scope.MAIN), deps);
    }
}
