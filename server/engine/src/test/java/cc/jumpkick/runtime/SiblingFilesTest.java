// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code sibling:<key>} input names the directories of every workspace sibling the module's
 * main compile reads — a provided sibling among them, as Maven's compile classpath holds a
 * {@code provided} dependency's jar and the protos it carries.
 */
class SiblingFilesTest {

    private static final PluginDescriptor PROTOBUF = PluginDescriptors.parse("""
            [plugin]
            id = "protobuf"
            table = "protobuf"
            version = "1"

            [schema]
            version = { type = "string", required = true }
            src = { type = "string-list", default = ["proto"] }
            """, "protobuf.toml");

    @Test
    void a_provided_sibling_contributes_its_proto_root_like_a_main_one(@TempDir Path root) throws Exception {
        workspace(root, "common", "client", "server");
        module(root, "common", "", "src/main/proto");
        module(root, "client", """
                [provided-dependencies]
                common = { workspace = true }
                """, "src/main/proto");
        module(root, "server", """
                [dependencies]
                common = { workspace = true }
                """, "src/main/proto");

        assertThat(dirs(root, "client")).containsExactly(root.resolve("common/src/main/proto"));
        assertThat(dirs(root, "server")).containsExactly(root.resolve("common/src/main/proto"));
    }

    @Test
    void a_runtime_or_test_sibling_is_not_an_include_root(@TempDir Path root) throws Exception {
        workspace(root, "common", "client");
        module(root, "common", "", "src/main/proto");
        module(root, "client", """
                [runtime-dependencies]
                common = { workspace = true }

                [test-dependencies]
                common = { workspace = true, kind = "tests" }
                """, "src/main/proto");

        assertThat(dirs(root, "client")).isEmpty();
    }

    private static List<Path> dirs(Path root, String module) throws IOException {
        JkBuild build = JkBuildParser.parse(root.resolve(module).resolve("jk.toml"));
        Map<String, List<Path>> out =
                SiblingFiles.forInputs(List.of("sibling:src"), root.resolve(module), build, PROTOBUF);
        return out.getOrDefault("src", List.of());
    }

    private static void workspace(Path root, String... modules) throws IOException {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = [%s]
                """.formatted(String.join(
                ", ", Arrays.stream(modules).map(m -> '"' + m + '"').toList())));
    }

    private static void module(Path root, String name, String depsBlock, String protoRoot) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.createDirectories(dir.resolve(protoRoot));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.ex"
                name = "%s"
                version = "0.1.0"
                java = 25

                [protobuf]
                version = "4.33.1"
                src = ["%s"]

                %s""".formatted(name, protoRoot, depsBlock));
    }
}
