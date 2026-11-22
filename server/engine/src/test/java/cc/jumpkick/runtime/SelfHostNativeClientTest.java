// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite that runs jk's native client is handed the binary its module links, never the copy the
 * build assembles into {@code target/dist} after every module's tests.
 */
class SelfHostNativeClientTest {

    @Test
    void a_suite_whose_tests_depend_on_the_client_module_gets_its_native_binary(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp);
        Path e2e = root.resolve("e2e");

        Path client = SelfHostEngine.nativeClient(e2e, JkBuildParser.parse(e2e.resolve("jk.toml")));

        assertThat(client)
                .isEqualTo(root.resolve("cli/target")
                        .resolve(BuildLayout.nativeExecutableFileName("jk"))
                        .toAbsolutePath()
                        .normalize());
        assertThat(String.valueOf(client)).doesNotContain("dist");
    }

    @Test
    void the_client_rides_the_test_props_the_stamp_hashes_and_the_run_waits_for(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp);
        Path e2e = root.resolve("e2e");
        Map<String, String> props = new LinkedHashMap<>();

        SelfHostEngine.enrichCliTestProps(e2e, JkBuildParser.parse(e2e.resolve("jk.toml")), props);

        assertThat(props)
                .containsEntry(
                        SelfHostEngine.NATIVE_CLIENT_PROPERTY,
                        root.resolve("cli/target")
                                .resolve(BuildLayout.nativeExecutableFileName("jk"))
                                .toAbsolutePath()
                                .normalize()
                                .toString());
    }

    @Test
    void a_suite_that_does_not_depend_on_the_client_module_gets_none(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp);
        Path other = root.resolve("other");

        assertThat(SelfHostEngine.nativeClient(other, JkBuildParser.parse(other.resolve("jk.toml"))))
                .isNull();
    }

    /** A client module that links a native binary jk installs as itself, and two test-only modules. */
    private static Path workspace(Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["cli", "e2e", "other"]
                """);
        Files.writeString(Files.createDirectories(root.resolve("cli")).resolve("jk.toml"), """
                name = "jk-cli"

                [application]
                main = "demo.Main"

                [install]
                product-bin = "jk"

                [native]
                enabled = "always"
                name = "jk"
                """);
        Files.writeString(Files.createDirectories(root.resolve("e2e")).resolve("jk.toml"), """
                name = "jk-cli-e2e"

                [test-dependencies]
                jk-cli.workspace = true
                """);
        Files.writeString(Files.createDirectories(root.resolve("other")).resolve("jk.toml"), """
                name = "jk-other"
                """);
        return root;
    }
}
