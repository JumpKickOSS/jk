// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A deleted module-root {@code jk-plugin.toml} (or leftover flattened catalog files) must count
 * as resource drift while copies still sit under classes/ — otherwise the jar keeps describing
 * a plugin that no longer exists until a clean build.
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

    @Test
    void leftover_flattened_catalog_is_out_of_sync_and_stripped(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("mod"));
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path catalog = Files.createDirectories(classes.resolve(Path.of("cc", "jumpkick", "plugin", "manifest")));
        Path toml = catalog.resolve("spring-boot.jk-plugin.toml");
        Path scaffold = Files.createDirectories(catalog.resolve("spring-boot").resolve("scaffold"));
        Files.writeString(toml, "[plugin]\nid = \"spring-boot\"\n");
        Files.writeString(scaffold.resolve("Hello.java.tmpl"), "class Hello {}");
        Path keepClass = catalog.resolve("Keep.class");
        Files.write(keepClass, new byte[] {0});

        assertThat(TaskForecaster.flattenedPluginCatalogPresent(classes)).isTrue();
        assertThat(TaskForecaster.mainResourcesOutOfSync(module, false, classes))
                .isTrue();

        assertThat(PlannerResources.stripFlattenedPluginCatalog(classes, name -> {})).isTrue();
        assertThat(toml).doesNotExist();
        assertThat(scaffold).doesNotExist();
        assertThat(keepClass).exists();
        assertThat(TaskForecaster.flattenedPluginCatalogPresent(classes)).isFalse();
        assertThat(PlannerResources.stripFlattenedPluginCatalog(classes, name -> {})).isFalse();
    }

    @Test
    void a_user_resource_sharing_the_package_is_neither_drift_nor_stripped(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path catalog = Files.createDirectories(classes.resolve(Path.of("cc", "jumpkick", "plugin", "manifest")));
        // Not one of jk's BUILT_IN manifests: a user project legitimately shipping a resource
        // here must reach the jar, and its presence must not re-run resources every build.
        Path userToml = catalog.resolve("my-own.jk-plugin.toml");
        Files.writeString(userToml, "[plugin]\nid = \"my-own\"\n");
        Path userDir = Files.createDirectories(catalog.resolve("data"));
        Files.writeString(userDir.resolve("table.txt"), "x\n");

        assertThat(TaskForecaster.flattenedPluginCatalogPresent(classes)).isFalse();
        assertThat(PlannerResources.stripFlattenedPluginCatalog(classes, name -> {})).isFalse();
        assertThat(userToml).exists();
        assertThat(userDir.resolve("table.txt")).exists();
    }
}
