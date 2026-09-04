// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginModuleTest {

    @Test
    void absent_markers_are_not_a_worker(@TempDir Path dir) {
        assertThat(PluginModule.isWorker(dir)).isFalse();
        assertThat(PluginModule.isWorker(null)).isFalse();
        JkBuild lib = JkBuild.of(new Project("g", "n", "1", 25));
        assertThat(PluginModule.mainClass(dir, lib)).isNull();
    }

    @Test
    void root_jk_plugin_toml_marks_a_worker(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk-plugin.toml"), "[plugin]\nid = \"x\"\ntable = \"x\"\n");
        assertThat(PluginModule.isWorker(dir)).isTrue();
        assertThat(PluginModule.mainClass(dir, JkBuild.of(new Project("g", "n", "1", 25))))
                .isEqualTo(PluginModule.WORKER_MAIN);
    }

    @Test
    void resources_jk_plugin_toml_marks_a_worker(@TempDir Path dir) throws Exception {
        Path manifest = dir.resolve("src/main/resources/jk-plugin.toml");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "[plugin]\nid = \"x\"\ntable = \"x\"\n");
        assertThat(PluginModule.isWorker(dir)).isTrue();
    }

    @Test
    void plugin_service_file_marks_a_worker(@TempDir Path dir) throws Exception {
        Path service = dir.resolve("src/main/resources/META-INF/services/cc.jumpkick.plugin.Plugin");
        Files.createDirectories(service.getParent());
        Files.writeString(service, "com.example.FooPlugin\n");
        assertThat(PluginModule.isWorker(dir)).isTrue();
    }
}
