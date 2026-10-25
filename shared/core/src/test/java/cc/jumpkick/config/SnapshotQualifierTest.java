// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A snapshot run reads every version as its declared one plus {@code -SNAPSHOT}; the files never change. */
class SnapshotQualifierTest {

    private static JkBuild parseAsSnapshot(Path manifest) throws Exception {
        return SessionContext.where(Session.defaults().withSnapshot(true), () -> JkBuildParser.parse(manifest));
    }

    private static Path write(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        return file;
    }

    @Test
    void a_standalone_module_gains_the_suffix_and_a_plain_run_does_not(@TempDir Path tmp) throws Exception {
        Path manifest = write(tmp.resolve("jk.toml"), """
                group = "ex"
                name = "money"
                version = "1.0.0"
                java = 25
                """);
        String before = Files.readString(manifest);

        assertThat(parseAsSnapshot(manifest).project().version()).isEqualTo("1.0.0-SNAPSHOT");
        assertThat(JkBuildParser.parse(manifest).project().version()).isEqualTo("1.0.0");
        assertThat(Files.readString(manifest)).as("jk.toml is never rewritten").isEqualTo(before);
    }

    @Test
    void a_version_already_a_snapshot_is_left_alone(@TempDir Path tmp) throws Exception {
        Path manifest = write(tmp.resolve("jk.toml"), """
                group = "ex"
                name = "money"
                version = "2.0.0-SNAPSHOT"
                java = 25
                """);

        assertThat(parseAsSnapshot(manifest).project().version()).isEqualTo("2.0.0-SNAPSHOT");
    }

    @Test
    void every_member_and_every_edge_onto_a_sibling_carries_the_suffix(@TempDir Path tmp) throws Exception {
        write(tmp.resolve("jk.toml"), """
                group = "ex"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["lib", "app"]
                """);
        write(tmp.resolve("lib/jk.toml"), "name = \"lib\"\n");
        Path app = write(tmp.resolve("app/jk.toml"), """
                name = "app"

                [dependencies]
                lib = { group = "ex", name = "lib", version = "1.0.0" }
                """);

        JkBuild snapshot = parseAsSnapshot(app);
        JkBuild release = JkBuildParser.parse(app);

        assertThat(snapshot.project().version()).isEqualTo("1.0.0-SNAPSHOT");
        assertThat(release.project().version()).isEqualTo("1.0.0");
        assertThat(versionOfLib(snapshot)).isEqualTo("1.0.0-SNAPSHOT");
        assertThat(versionOfLib(release)).isEqualTo("1.0.0");
    }

    private static String versionOfLib(JkBuild build) {
        Dependency lib = build.dependencies().of(Scope.MAIN).stream()
                .filter(d -> d.module().equals("ex:lib"))
                .findFirst()
                .orElseThrow();
        return ((VersionSelector.Exact) lib.version()).version();
    }
}
