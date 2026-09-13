// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.plugin.manifest.PluginDescriptorStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pinned plugin jar registers only its own descriptor. A jar assembled with a sibling's
 * {@code jk-plugin.toml} at its root would otherwise be recorded as the owner of the sibling's
 * table, so {@link PluginDescriptorOps#materialize} refuses it before anything reaches the store.
 */
class PluginDescriptorOpsRefusalTest {

    private static final String SHA = "ab".repeat(32);

    @Test
    void a_third_party_jar_whose_descriptor_names_another_worker_is_refused_and_nothing_is_written(@TempDir Path tmp)
            throws Exception {
        Path jar = jarWith(tmp, descriptor("acme", "other-plugin"));

        assertThatThrownBy(() -> PluginDescriptorOps.materialize(tmp, SHA, jar, "com.acme:acme-plugin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.acme:acme-plugin")
                .hasMessageContaining("describes plugin `acme`")
                .hasMessageContaining("worker other-plugin")
                .hasMessageContaining("Publish acme-plugin with its own " + ManifestPaths.PLUGIN_MANIFEST);
        assertThat(PluginDescriptorStore.fileFor(tmp, SHA)).doesNotExist();
    }

    @Test
    void a_third_party_descriptor_naming_the_pinned_artifact_or_no_worker_is_its_own(@TempDir Path tmp)
            throws Exception {
        PluginDescriptorOps.materialize(
                tmp, SHA, jarWith(tmp.resolve("a"), descriptor("acme", "acme-plugin")), "com.acme:acme-plugin");
        assertThat(PluginDescriptorStore.fileFor(tmp, SHA)).exists();

        String other = "cd".repeat(32);
        PluginDescriptorOps.materialize(
                tmp, other, jarWith(tmp.resolve("b"), descriptor("acme", null)), "com.acme:acme-plugin");
        assertThat(PluginDescriptorStore.fileFor(tmp, other)).exists();
    }

    @Test
    void a_first_party_pin_is_held_to_the_jk_id_default(@TempDir Path tmp) throws Exception {
        Path grails = jarWith(tmp, descriptor("grails", null));

        assertThatThrownBy(() -> PluginDescriptorOps.materialize(tmp, SHA, grails, "cc.jumpkick:jk-spring-boot"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worker jk-grails")
                .hasMessageContaining("not the pinned artifact jk-spring-boot");
        assertThat(PluginDescriptorStore.fileFor(tmp, SHA)).doesNotExist();

        PluginDescriptorOps.materialize(tmp, SHA, grails, "cc.jumpkick:jk-grails");
        assertThat(PluginDescriptorStore.fileFor(tmp, SHA)).exists();
    }

    @Test
    void a_path_pin_has_no_artifact_to_compare_against(@TempDir Path tmp) throws Exception {
        PluginDescriptorOps.materialize(tmp, SHA, jarWith(tmp, descriptor("acme", "other-plugin")), "path:acme");
        assertThat(PluginDescriptorStore.fileFor(tmp, SHA)).exists();
    }

    private static Path jarWith(Path dir, String toml) throws IOException {
        Files.createDirectories(dir);
        Path jar = dir.resolve("plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(ManifestPaths.PLUGIN_MANIFEST));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static String descriptor(String id, @Nullable String worker) {
        String code = worker == null ? "" : "[code]\nworker = \"" + worker + "\"\nprotocol-prefix = \"##X:\"\n";
        return "[plugin]\nid = \"" + id + "\"\ntable = \"" + id + "\"\njk-compat = \">=0.10\"\n\n" + code;
    }
}
