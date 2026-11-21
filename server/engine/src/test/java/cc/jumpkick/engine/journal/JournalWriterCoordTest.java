// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.runtime.ShadowManifests;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The coordinate a run is journaled under is read from the project's own files. Admission names
 * every job this way before the job starts, so a Maven-only checkout's shadow is never rendered
 * just to name it: on a large reactor that rendering is most of {@code jk import}'s wall time.
 */
class JournalWriterCoordTest {

    @TempDir
    Path dir;

    @Test
    void aMavenOnlyCheckoutIsNamedFromItsPomWithoutRenderingTheShadow() throws Exception {
        ShadowManifests.install(lockDir -> List.of());
        Files.writeString(dir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>app</artifactId><version>1.0.0</version>
                </project>
                """);

        assertThat(JournalWriter.coordOf(dir.toString())).isEqualTo("com.example:app");
        assertThat(ManifestPaths.shadowManifestPath(dir)).doesNotExist();
    }

    @Test
    void aJkProjectIsNamedFromItsManifest() throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                name = "lib"
                group = "com.example"
                version = "1.0.0"
                java = 25
                """);
        Files.writeString(dir.resolve("pom.xml"), """
                <project><groupId>org.other</groupId><artifactId>ignored</artifactId></project>
                """);

        assertThat(JournalWriter.coordOf(dir.toString())).isEqualTo("com.example:lib");
    }
}
