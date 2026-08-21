// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-2267: deleting a module-root {@code jk-plugin.toml} must count as manifest drift while its
 * copy still sits under classes/ — otherwise the jar keeps describing a plugin that no longer
 * exists until a clean build (the JK-2174 orphan, regressed when extra-resources' reconciliation
 * was dropped).
 */
class PluginManifestOrphanTest {

    @Test
    void deleted_manifest_with_a_classes_copy_is_out_of_sync(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("mod"));
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Files.writeString(classes.resolve("jk-plugin.toml"), "[plugin]\nid = \"old\"\n");

        assertThat(TaskForecaster.pluginManifestOutOfSync(module, classes))
                .as("orphaned classes copy with no source manifest")
                .isTrue();

        Files.delete(classes.resolve("jk-plugin.toml"));
        assertThat(TaskForecaster.pluginManifestOutOfSync(module, classes))
                .as("neither side present — in sync")
                .isFalse();
    }
}
