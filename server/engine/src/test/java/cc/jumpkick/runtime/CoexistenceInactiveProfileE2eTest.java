// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.PomReactorScan;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: a reactor whose {@code pom.xml} lists one module at the top level and another only
 * in a profile Maven does not activate here. The root builds the active module alone; the inactive
 * module has no shadow, and a build entered there is a configuration refusal — one line naming
 * the profile and the remedies, exit {@link Exit#CONFIG} — not a crash.
 */
@Tag("integration")
class CoexistenceInactiveProfileE2eTest {

    @BeforeAll
    static void engineShadowSource() {
        ShadowManifests.install();
    }

    @Test
    void a_module_only_an_inactive_profile_lists_is_refused_with_the_profile_named(@TempDir Path tmp) throws Exception {
        Path root = writeReactor(tmp.resolve("reactor"));
        Path core = root.resolve("core");
        Path extra = root.resolve("extra");
        Path cache = Files.createDirectories(Path.of("build/test-cache/coexistence"));

        assertThat(PomReactorScan.profilesListing(root, extra)).containsExactly("extras");
        assertThat(PomReactorScan.profilesListing(root, core)).isEmpty();

        // Reading the inactive module's manifest is the typed refusal, one line long.
        assertThatThrownBy(() -> ManifestPaths.manifestIn(extra))
                .isInstanceOf(ShadowManifests.NotBuiltHere.class)
                .hasMessageContaining("profile `extras`")
                .hasMessageContaining("jk import pom.xml")
                .hasMessageContaining("[workspace] modules")
                .extracting(Throwable::getMessage)
                .asString()
                .doesNotContain("\n");

        // A workspace build entered in that directory reports the same line and exits 2.
        WorkspaceResult refused = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(extra, cache, null, 0, null, true, false, 2, null, false, true),
                new WorkspaceBuildListener() {});
        assertThat(refused.success()).isFalse();
        assertThat(refused.exitCode()).isEqualTo(Exit.CONFIG);
        assertThat(refused.errors()).singleElement().asString().contains("profile `extras`");
        assertThat(ManifestPaths.shadowManifestPath(extra)).doesNotExist();

        // The root builds what Maven would build here: the top-level module, and nothing else.
        WorkspaceResult built = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(root, cache, null, 0, null, true, false, 2, null, false, true),
                new WorkspaceBuildListener() {});
        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();
        assertThat(built.modules())
                .extracting(m -> m.dir().getFileName().toString())
                .containsExactly("core");
        assertThat(Files.readString(ManifestPaths.manifestIn(root))).contains("modules = [\"core\"]");
        assertThat(ManifestPaths.shadowManifestPath(extra)).doesNotExist();
        assertThat(root.resolve("jk.toml")).doesNotExist();
        assertThat(extra.resolve("jk.toml")).doesNotExist();
    }

    /** {@code core} at the top level, {@code extra} only in the {@code extras} profile; one class each. */
    private static Path writeReactor(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>reactor</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <maven.compiler.release>25</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <modules>
                    <module>core</module>
                  </modules>
                  <profiles>
                    <profile>
                      <id>extras</id>
                      <modules>
                        <module>extra</module>
                      </modules>
                    </profile>
                  </profiles>
                </project>
                """);
        for (String module : new String[] {"core", "extra"}) {
            Path dir = Files.createDirectories(root.resolve(module));
            Files.writeString(dir.resolve("pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                        <groupId>com.example</groupId>
                        <artifactId>reactor</artifactId>
                        <version>1.0.0</version>
                      </parent>
                      <artifactId>%s</artifactId>
                    </project>
                    """.formatted(module));
            Files.writeString(
                    Files.createDirectories(dir.resolve("src/main/java/com/example"))
                            .resolve("Marker.java"),
                    """
                    package com.example;

                    public final class Marker {}
                    """);
        }
        return root;
    }
}
