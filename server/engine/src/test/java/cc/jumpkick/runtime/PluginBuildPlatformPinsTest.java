// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The platform versions a plugin's {@code ${config.<key>}} segment reads are the module's: a member
 * whose own partition row a BOM at another version pinned reads that BOM's version, the workspace
 * root and every other member read the workspace's.
 */
class PluginBuildPlatformPinsTest {

    @Test
    void a_members_platform_pin_is_the_one_its_own_row_carries(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("lib"));
        Lockfile.Artifact workspace = new Lockfile.Artifact(
                "com.foo:widget:jar:",
                "1.0",
                "central+",
                "sha256:aa",
                null,
                List.of(Scope.MAIN),
                List.of(),
                "org.example:the-bom:1.0");
        Lockfile.Artifact partition = new Lockfile.Artifact(
                        "com.foo:widget:jar:",
                        "2.0",
                        "central+",
                        "sha256:bb",
                        null,
                        List.of(Scope.MAIN),
                        List.of(),
                        "org.example:the-bom:2.0")
                .withMembers(List.of("lib"));
        Path lockFile = root.resolve("jk-lock.toml");
        LockfileWriter.write(
                new Lockfile(1, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(workspace, partition)), lockFile);

        assertThat(PluginBuild.platformPins(root, lockFile)).isEqualTo(Map.of("org.example:the-bom", "1.0"));
        assertThat(PluginBuild.platformPins(root.resolve("lib"), lockFile))
                .isEqualTo(Map.of("org.example:the-bom", "2.0"));
    }
}
