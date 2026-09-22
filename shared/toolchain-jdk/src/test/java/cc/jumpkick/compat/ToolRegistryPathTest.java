// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A build-tool version is one directory name under the tools root. */
class ToolRegistryPathTest {

    @Test
    void a_version_that_is_a_path_is_usage_and_deletes_nothing(@TempDir Path tmp) throws Exception {
        Path sentinel = tmp.resolve("sentinel");
        Files.writeString(sentinel, "keep");
        ToolRegistry registry = new ToolRegistry(tmp.resolve("tools"));
        assertThat(ToolRegistry.invalidVersion("..")).isNotNull();
        assertThat(ToolRegistry.invalidVersion("..\\..\\sentinel")).isNotNull();
        assertThat(ToolRegistry.invalidVersion("../../sentinel")).isNotNull();
        assertThatThrownBy(() -> registry.installDir(BuildTool.MAVEN, ".."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.installDir(BuildTool.MAVEN, "..\\..\\sentinel"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(sentinel)).isEqualTo("keep");
        assertThat(registry.installDir(BuildTool.MAVEN, "3.9.9"))
                .isEqualTo(tmp.resolve("tools").resolve("maven").resolve("3.9.9"));
    }
}
