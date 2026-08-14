// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1694: class-initialization policy is not reachability metadata. Plugins declare what their
 * framework needs; the user's {@code [native] args} still wins because the engine appends it after.
 */
class NativeArgsContributionTest {

    @Test
    void a_micronaut_project_with_native_gets_the_plugins_args(@TempDir Path dir) throws Exception {
        JkBuild build = parse(dir, """
                group = "com.example"
                name = "svc"
                version = "0.1.0"
                jdk = 25

                [micronaut]
                version = "5"

                [native]
                always = true
                """);

        assertThat(PluginContributions.nativeArgs(build, dir))
                .contains("--initialize-at-run-time=io.netty.buffer")
                .contains("--initialize-at-run-time=io.netty.util")
                .contains("--initialize-at-build-time=ch.qos.logback,org.slf4j")
                .anyMatch(a -> a.contains("$NettyServiceDiscovery$"));
    }

    @Test
    void without_native_declared_the_args_do_not_apply(@TempDir Path dir) throws Exception {
        JkBuild build = parse(dir, """
                group = "com.example"
                name = "svc"
                version = "0.1.0"
                jdk = 25

                [micronaut]
                version = "5"
                """);

        assertThat(PluginContributions.nativeArgs(build, dir)).isEmpty();
    }

    @Test
    void a_project_with_no_plugin_table_contributes_nothing(@TempDir Path dir) throws Exception {
        JkBuild build = parse(dir, """
                group = "com.example"
                name = "svc"
                version = "0.1.0"
                jdk = 25

                [native]
                always = true
                """);

        assertThat(PluginContributions.nativeArgs(build, dir)).isEmpty();
    }

    private static JkBuild parse(Path dir, String toml) throws Exception {
        Path file = dir.resolve("jk.toml");
        Files.writeString(file, toml);
        return JkBuildParser.parse(file);
    }
}
