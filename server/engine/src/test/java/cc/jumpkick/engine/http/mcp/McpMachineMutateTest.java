// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkOwnership;
import cc.jumpkick.jdk.JdkRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpMachineMutateTest {

    @Test
    void disk_nuke_without_confirm_is_preview(@TempDir Path cache) throws Exception {
        Path key = seed(cache.resolve("actions/keys/task1"));
        Map<String, Object> preview = McpMachine.diskAction("nuke", false, cache);
        assertThat(preview.get("preview")).isEqualTo(true);
        assertThat(key).exists();
        Map<String, Object> done = McpMachine.diskAction("nuke", true, cache);
        assertThat(done.get("nuked")).isEqualTo(true);
        assertThat(key).doesNotExist();
        Path repo = seed(cache.resolve("repos/central/lib.jar"));
        McpMachine.diskAction("nuke", true, cache);
        assertThat(repo).exists();
    }

    @Test
    void disk_clean_without_confirm_is_preview(@TempDir Path cache) {
        Map<String, Object> preview = McpMachine.diskAction("clean", false, cache);
        assertThat(preview.get("preview")).isEqualTo(true);
        assertThat(preview.get("note").toString()).contains("confirm=true");
    }

    @Test
    void jdk_uninstall_requires_confirm(@TempDir Path jdks) throws Exception {
        Path home = fakeJdk(jdks, "temurin-21.0.5", "21.0.5");
        JdkRegistry registry = new JdkRegistry(jdks);
        Map<String, Object> preview = McpMachine.jdkAction("uninstall", "21", null, false, registry);
        assertThat(preview.get("preview")).isEqualTo(true);
        assertThat(home).exists();
        Map<String, Object> done = McpMachine.jdkAction("uninstall", "21", null, true, registry);
        assertThat(done.get("removed")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> removed = (List<String>) done.get("removed");
        assertThat(removed).isNotEmpty();
        assertThat(home).doesNotExist();
    }

    @Test
    void jdk_uninstall_older_than_previews_then_removes(@TempDir Path jdks) throws Exception {
        Path old = fakeJdk(jdks, "temurin-21.0.5", "21.0.5");
        Path keep = fakeJdk(jdks, "temurin-25.0.1", "25.0.1");
        JdkRegistry registry = new JdkRegistry(jdks);
        Map<String, Object> preview = McpMachine.jdkAction("uninstall", null, 25, false, registry);
        assertThat(preview.get("preview")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> victims = (List<Map<String, Object>>) preview.get("victims");
        assertThat(victims).isNotEmpty();
        assertThat(victims.stream().map(v -> String.valueOf(v.get("identifier"))))
                .anyMatch(id -> id.contains("21"));
        McpMachine.jdkAction("uninstall", null, 25, true, registry);
        assertThat(old).doesNotExist();
        assertThat(keep).exists();
    }

    @Test
    void jdk_install_requires_spec() {
        Map<String, Object> m = McpMachine.jdkAction("install", "", null, false);
        assertThat(m.get("error").toString()).contains("spec");
    }

    @Test
    void major_of_parses_versions() {
        assertThat(McpMachine.majorOf("21.0.5")).isEqualTo(21);
        assertThat(McpMachine.majorOf("temurin-25.0.1")).isEqualTo(25);
        assertThat(McpMachine.majorOf("25")).isEqualTo(25);
    }

    private static Path fakeJdk(Path jdks, String folder, String version) throws Exception {
        Path home = jdks.resolve(folder);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin/java"), "#!/bin/sh\n");
        Files.writeString(home.resolve("bin/javac"), "#!/bin/sh\n");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        JdkOwnership.mark(home);
        return home;
    }

    private static Path seed(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[32]);
        return file;
    }
}
