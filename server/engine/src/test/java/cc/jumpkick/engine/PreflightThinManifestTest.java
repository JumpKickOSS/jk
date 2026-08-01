// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.BuildGraph;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine's preflight fingerprint snapshot must parse {@code jk.toml} by <em>path</em> so
 * workspace inheritance applies. A string parse of a thin member manifest yields sentinel
 * identity ({@code __jk.workspace__} / {@code java = 0}); {@code BuildGraph.resolve} on that
 * throws into the fail-open catch and the preflight memo is silently never stored.
 */
class PreflightThinManifestTest {

    @Test
    void path_parse_of_a_thin_member_resolves_a_graph(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name = "ws"
                version = "1.0.0"
                jdk = 21
                java = 21

                [workspace]
                modules = ["member"]
                """);
        Path member = Files.createDirectories(tmp.resolve("member"));
        // Thin manifest: identity inherited from the workspace root.
        Files.writeString(member.resolve("jk.toml"), """
                [project]
                name = "member"
                """);

        JkBuild byPath = JkBuildParser.parse(member.resolve("jk.toml"));
        assertThat(byPath.project().group())
                .as("path parse applies workspace inheritance")
                .isEqualTo("com.example");
        BuildGraph.Result graph = BuildGraph.resolve(member, byPath);
        assertThat(graph.hasErrors())
                .as("preflight snapshot path: graph resolves for a thin member")
                .isFalse();
    }
}
