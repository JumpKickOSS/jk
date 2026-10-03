// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The webapp template's {@code web} module is a node module jk builds, with no hand-run npm step. */
class WebappTemplateShapeTest {

    private static final Path REPO = RepoRoot.find(WebappTemplateShapeTest.class);

    @TempDir
    Path tmp;

    @Test
    void the_web_module_is_a_node_module_vite_builds_into_dist() throws IOException {
        // A rendered root: the modules inherit its group and version.
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name = "shop"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["java", "kotlin"]
                """);
        for (String lang : List.of("java", "kotlin")) {
            Path g8 = REPO.resolve("templates/" + lang + "/spring-boot/webapp.g8/src/main/g8");
            // Away from the template's root jk.toml, whose placeholders are rendered only by jk new.
            Path web = tmp.resolve(lang);
            PathUtil.copyTree(g8.resolve("web"), web);
            JkBuild build = JkBuildParser.parse(web.resolve("jk.toml"));
            assertThat(NodeShape.kind(build, web)).as(lang).isEqualTo(NodeShape.Kind.MODULE);
            assertThat(NodeProject.infer(web, build.node()).out()).as(lang).isEqualTo("dist");
            assertThat(Files.readString(web.resolve("vite.config.ts"))).as(lang).doesNotContain("outDir");
            assertThat(Files.readString(g8.resolve(".gitignore"))).as(lang).doesNotContain("web/resources");
            assertThat(Files.readString(g8.resolve("README.md"))).as(lang).doesNotContain("npm ci");
        }
    }
}
