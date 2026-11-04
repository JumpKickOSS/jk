// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestOutlineTest {

    @Test
    void readsTheNameAndWorkspaceDependenciesPastATableNoInstalledPluginOwns(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("jk.toml");
        Files.writeString(file, """
                name = "app"

                [dependencies]
                core.workspace = true
                api = { workspace = true }
                guava = "33.5.0-jre"

                [test-dependencies]
                fixtures.workspace = true

                [intellij]
                plugin-id = "x"
                """);
        assertThat(ManifestOutline.read(file)).isEqualTo(new ManifestOutline("app", Set.of("api", "core", "fixtures")));
    }
}
