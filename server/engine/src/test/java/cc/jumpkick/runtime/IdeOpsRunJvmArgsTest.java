// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.IdeOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The IDE model carries each module's {@code [application] jvm-args}, the flags {@code jk run} gives its JVM. */
class IdeOpsRunJvmArgsTest {

    @Test
    void each_module_s_jvm_args_are_rows_under_its_own_index_in_order(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["lib", "app"]
                """);
        module(root, "lib", "");
        module(root, "app", """
                [application]
                main = "com.ex.Main"
                jvm-args = ["-Xmx512m", "--enable-native-access=ALL-UNNAMED"]
                """);
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(root, JkBuildParser.parse(root.resolve("jk.toml")));
        List<Path> dirs = new ArrayList<>(List.of(root.resolve("lib"), root.resolve("app")));

        assertThat(IdeOps.runJvmArgs(dirs, modules))
                .containsExactly("1|-Xmx512m", "1|--enable-native-access=ALL-UNNAMED");
    }

    private static void module(Path root, String name, String tables) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.ex"
                name = "%s"
                version = "0.1.0"

                %s""".formatted(name, tables));
    }
}
