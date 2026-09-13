// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A build directory is recognised by where it sits, so a package or a module named build is source. */
class OutputDirsTest {

    @Test
    void build_beside_a_gradle_script_is_gradle_output(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { java }");
        Path build = Files.createDirectories(dir.resolve("build"));
        assertThat(OutputDirs.isBuildOutputDir(build)).isTrue();

        Path settingsOnly = Files.createDirectories(dir.resolve("root/build"));
        Files.writeString(dir.resolve("root/settings.gradle"), "rootProject.name = 'x'");
        assertThat(OutputDirs.isBuildOutputDir(settingsOnly)).isTrue();
    }

    @Test
    void a_package_or_module_named_build_is_source(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { java }");
        Path pkg = Files.createDirectories(dir.resolve("src/main/java/cc/jumpkick/plugin/build"));
        assertThat(OutputDirs.isBuildOutputDir(pkg))
                .as("a package under src/ has no script beside it")
                .isFalse();

        Path bare = Files.createDirectories(dir.resolve("tools/build"));
        assertThat(OutputDirs.isBuildOutputDir(bare))
                .as("no Gradle script in tools/")
                .isFalse();

        Path other = Files.createDirectories(dir.resolve("builds"));
        assertThat(OutputDirs.isBuildOutputDir(other)).as("only the exact name").isFalse();
    }

    @Test
    void build_beside_a_module_manifest_is_output(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "name = \"m\"\n");
        Path build = Files.createDirectories(dir.resolve("build"));
        assertThat(OutputDirs.isBuildOutputDir(build))
                .as("a tool's leftover output beside the manifest is not source")
                .isTrue();
    }

    @Test
    void a_module_named_build_holding_its_own_manifest_is_source(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "name = \"ws\"\n");
        Path module = Files.createDirectories(dir.resolve("build"));
        Files.writeString(module.resolve("jk.toml"), "name = \"build\"\n");
        assertThat(OutputDirs.isBuildOutputDir(module))
                .as("a module, whatever its name")
                .isFalse();
    }
}
