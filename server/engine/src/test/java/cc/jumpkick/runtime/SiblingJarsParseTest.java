// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module's siblings are the workspace's module list, read as the loader reads it: one parse per
 * member, not a full parse of every member (each resolving the whole workspace again) per lookup.
 */
class SiblingJarsParseTest {

    private static final int MEMBERS = 6;

    @Test
    void a_sibling_lookup_parses_no_manifest_beyond_the_module_list(@TempDir Path tmp) throws IOException {
        Path root = workspace(tmp);

        long before = JkBuildParser.parseRequests();
        WorkspaceLoader.loadModules(root, JkBuildParser.parseLocal(root.resolve("jk.toml")));
        long loader = JkBuildParser.parseRequests() - before;

        before = JkBuildParser.parseRequests();
        Map<String, Path> jars = SiblingJars.siblingMainJars(root.resolve("m5"));
        long lookup = JkBuildParser.parseRequests() - before;

        assertThat(jars).containsKeys("m0", "com.example:m5");
        assertThat(lookup).as("parses for one sibling lookup").isEqualTo(loader);
    }

    /** A workspace whose members each name their predecessor, so a member's full parse needs the list. */
    private static Path workspace(Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        StringBuilder modules = new StringBuilder();
        for (int i = 0; i < MEMBERS; i++) {
            modules.append(i == 0 ? "" : ", ").append("\"m").append(i).append('"');
        }
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = [%s]
                """.formatted(modules));
        for (int i = 0; i < MEMBERS; i++) {
            Path dir = Files.createDirectories(root.resolve("m" + i));
            String deps = i == 0 ? "" : "\n[dependencies]\nm" + (i - 1) + ".workspace = true\n";
            Files.writeString(dir.resolve("jk.toml"), "name = \"m" + i + "\"\n" + deps);
        }
        return root;
    }
}
