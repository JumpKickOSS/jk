// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The protobuf manifest's tool lane over a module that runs a protoc plugin: the table's own
 * protoc, and one {@code protoc-gen-<id>} per {@code [protobuf.<id>]} entry, each resolved as a
 * bare native binary for the host ({@code :<os-arch>!exe}) the way protoc itself is.
 */
class ProtobufToolLaneTest {

    private static final Path MANIFEST = RepoRoot.file(ProtobufToolLaneTest.class, "plugins/protobuf/jk-plugin.toml");

    private static JkBuild build(String toml) throws IOException {
        PluginDescriptor manifest = PluginDescriptors.parse(Files.readString(MANIFEST), MANIFEST.toString());
        PluginTableRegistry.putBuiltIn(manifest, null);
        return JkBuildParser.parse(toml);
    }

    @Test
    void each_protoc_plugin_entry_is_one_host_native_tool_beside_protoc() throws Exception {
        JkBuild build = build("""
                name = "svc"
                group = "com.example"
                version = "0.1.0"
                java = 25

                [protobuf]
                version = "3.25.5"

                [protobuf.grpc-java]
                plugin = "io.grpc:protoc-gen-grpc-java:1.81.0"
                options = ["@generated=omit"]
                """);

        List<PluginContributions.StepDep> tools = PluginContributions.stepDependencies(build, null, Map.of());
        assertThat(tools)
                .extracting(PluginContributions.StepDep::artifact)
                .containsExactly("protoc", "protoc-gen-grpc-java");
        String protoc = Objects.requireNonNull(tools.get(0).coordinateSpec());
        String plugin = Objects.requireNonNull(tools.get(1).coordinateSpec());
        assertThat(protoc).startsWith("com.google.protobuf:protoc:3.25.5:").endsWith("!exe");
        assertThat(plugin)
                .as("the entry's group:artifact:version takes protoc's own classifier and packaging")
                .isEqualTo("io.grpc:protoc-gen-grpc-java:1.81.0"
                        + protoc.substring("com.google.protobuf:protoc:3.25.5".length()));
        assertThat(tools).allSatisfy(tool -> assertThat(tool.forSteps()).isEmpty());
    }

    /** A table with no entry fetches protoc alone: no plugin, no second binary. */
    @Test
    void a_table_without_entries_fetches_protoc_alone() throws Exception {
        JkBuild build = build("""
                name = "msgs"
                group = "com.example"
                version = "0.1.0"
                java = 25

                [protobuf]
                version = "4.33.1"
                """);

        assertThat(PluginContributions.stepDependencies(build, null, Map.of()))
                .extracting(PluginContributions.StepDep::artifact)
                .containsExactly("protoc");
    }
}
