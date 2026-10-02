// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk build} of a {@code [war]} module writes the exploded war and the archive Maven would. */
@Tag("integration")
class WarBuildE2eTest {

    @Test
    void a_war_holds_its_webapp_its_classes_and_its_runtime_siblings_but_not_provided_ones(@TempDir Path ws)
            throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name  = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["model", "api", "web"]
                """, StandardCharsets.UTF_8);
        module(ws.resolve("model"), "model", "model", "Item", "");
        module(ws.resolve("api"), "api", "api", "Servlet", "");
        module(ws.resolve("web"), "web", "web", "Home", """

                [war]
                name = "ROOT"

                [dependencies]
                model = { workspace = true }

                [provided-dependencies]
                api = { workspace = true }
                """);
        Path webapp = ws.resolve("web/src/main/webapp/WEB-INF/web.xml");
        Files.createDirectories(webapp.getParent());
        Files.writeString(webapp, "<web-app/>", StandardCharsets.UTF_8);
        Files.writeString(ws.resolve("web/src/main/webapp/index.html"), "<html/>", StandardCharsets.UTF_8);

        int exit = run(
                "build",
                "--skip-tests",
                "-C",
                ws.toString(),
                "--cache-dir",
                ws.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        Path target = ws.resolve("web/target");
        assertThat(target.resolve("web-1.0.0.jar")).exists();
        assertThat(target.resolve("ROOT/index.html")).hasContent("<html/>");
        assertThat(target.resolve("ROOT/WEB-INF/classes/web/Home.class")).exists();
        assertThat(target.resolve("ROOT/WEB-INF/lib/model-1.0.0.jar")).exists();
        assertThat(target.resolve("ROOT/WEB-INF/lib/api-1.0.0.jar")).doesNotExist();
        try (JarFile war = new JarFile(target.resolve("ROOT.war").toFile())) {
            List<String> entries = Collections.list(war.entries()).stream()
                    .map(ZipEntry::getName)
                    .toList();
            assertThat(entries)
                    .contains(
                            "index.html",
                            "WEB-INF/web.xml",
                            "WEB-INF/classes/web/Home.class",
                            "WEB-INF/lib/model-1.0.0.jar")
                    .doesNotContain("WEB-INF/lib/api-1.0.0.jar");
        }

        // A second build restores the war from the cache and leaves the exploded tree as it was.
        byte[] first = Files.readAllBytes(target.resolve("ROOT.war"));
        assertThat(run(
                        "build",
                        "--skip-tests",
                        "-C",
                        ws.toString(),
                        "--cache-dir",
                        ws.resolve("cache").toString()))
                .isEqualTo(0);
        assertThat(Files.readAllBytes(target.resolve("ROOT.war"))).isEqualTo(first);
        assertThat(target.resolve("ROOT/WEB-INF/lib/model-1.0.0.jar")).exists();
    }

    private static void module(Path dir, String name, String pkg, String cls, String extra) throws IOException {
        Files.createDirectories(dir.resolve("src/main/java/" + pkg));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name  = "%s"
                version = "1.0.0"
                java = 25
                %s
                """.formatted(name, extra), StandardCharsets.UTF_8);
        Files.writeString(
                dir.resolve("src/main/java/" + pkg + "/" + cls + ".java"),
                "package " + pkg + "; public class " + cls + " {}",
                StandardCharsets.UTF_8);
    }
}
