// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.BuildStamps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WarPackagerTest {

    @Test
    void the_war_holds_the_webapp_the_classes_and_the_runtime_jars(@TempDir Path dir) throws IOException {
        Path classes = write(dir.resolve("classes"), "com/acme/Hello.class", "class");
        write(classes, "app.properties", "k=v");
        write(classes, BuildStamps.ALL.getFirst(), "stamp");
        Path webapp = write(dir.resolve("webapp"), "index.html", "<html/>");
        write(webapp, "WEB-INF/web.xml", "<web-app/>");
        Path guava = write(dir.resolve("repo/com/google/guava/guava/33.0"), "guava-33.0.jar", "jar")
                .resolve("guava-33.0.jar");
        Path exploded = dir.resolve("target/web-1.0");
        Path war = dir.resolve("target/web-1.0.war");
        write(exploded, "stale.txt", "from an earlier build");

        new WarPackager()
                .packageWar(new WarPackager.WarRequest(
                        classes,
                        webapp,
                        List.of(guava),
                        exploded,
                        war,
                        Map.of("Main-Class", "com.acme.Main"),
                        Map.of()));

        assertThat(exploded.resolve("index.html")).hasContent("<html/>");
        assertThat(exploded.resolve("WEB-INF/web.xml")).exists();
        assertThat(exploded.resolve("WEB-INF/classes/com/acme/Hello.class")).exists();
        assertThat(exploded.resolve("WEB-INF/classes/app.properties")).exists();
        assertThat(exploded.resolve("WEB-INF/classes").resolve(BuildStamps.ALL.getFirst()))
                .doesNotExist();
        assertThat(exploded.resolve("WEB-INF/lib/guava-33.0.jar")).exists();
        assertThat(exploded.resolve("stale.txt")).doesNotExist();
        try (JarFile jar = new JarFile(war.toFile())) {
            assertThat(Collections.list(jar.entries()).stream().map(e -> e.getName()))
                    .contains(
                            "index.html",
                            "WEB-INF/web.xml",
                            "WEB-INF/classes/com/acme/Hello.class",
                            "WEB-INF/lib/guava-33.0.jar")
                    .noneMatch(BuildStamps::isStampFile);
            assertThat(jar.getManifest().getMainAttributes().getValue("Main-Class"))
                    .isEqualTo("com.acme.Main");
        }
    }

    @Test
    void the_same_inputs_archive_the_same_bytes(@TempDir Path dir) throws IOException {
        Path classes = write(dir.resolve("classes"), "com/acme/Hello.class", "class");
        Path first = dir.resolve("a/web.war");
        Path second = dir.resolve("b/web.war");
        new WarPackager()
                .packageWar(new WarPackager.WarRequest(
                        classes, null, List.of(), dir.resolve("a/web"), first, Map.of(), Map.of()));
        new WarPackager()
                .packageWar(new WarPackager.WarRequest(
                        classes, null, List.of(), dir.resolve("b/web"), second, Map.of(), Map.of()));

        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
    }

    @Test
    void a_path_two_web_roots_carry_comes_from_the_first_and_the_other_copy_is_reported(@TempDir Path dir)
            throws IOException {
        Path classes = write(dir.resolve("classes"), "com/acme/Hello.class", "class");
        Path webapp = write(dir.resolve("webapp"), "index.html", "placeholder");
        Path ui = write(dir.resolve("ui/dist"), "index.html", "built");
        write(ui, "app.js", "js");
        Path admin = write(dir.resolve("admin/dist"), "index.html", "admin");
        Map<Path, String> webContent = new LinkedHashMap<>();
        webContent.put(ui, "");
        webContent.put(admin, "admin");
        webContent.put(dir.resolve("late/dist"), "admin");
        write(dir.resolve("late/dist"), "index.html", "late");

        WarPackager.War war = new WarPackager()
                .packageWar(new WarPackager.WarRequest(
                        classes, webapp, List.of(), dir.resolve("web"), dir.resolve("web.war"), Map.of(), webContent));

        Path exploded = dir.resolve("web");
        assertThat(exploded.resolve("index.html")).hasContent("placeholder");
        assertThat(exploded.resolve("app.js")).hasContent("js");
        assertThat(exploded.resolve("admin/index.html")).hasContent("admin");
        assertThat(war.file()).isEqualTo(dir.resolve("web.war"));
        assertThat(war.shadowed())
                .containsExactly(
                        new WarPackager.Shadowed("index.html", webapp, ui),
                        new WarPackager.Shadowed("admin/index.html", admin, dir.resolve("late/dist")));
    }

    @Test
    void jars_that_share_a_file_name_are_told_apart_by_their_group(@TempDir Path dir) {
        Path a = dir.resolve("repo/org/alpha/util/1.0/util-1.0.jar");
        Path b = dir.resolve("repo/org/beta/util/1.0/util-1.0.jar");
        Path c = dir.resolve("repo/org/gamma/core/2.0/core-2.0.jar");

        assertThat(WarPackager.libNames(List.of(a, b, c)))
                .containsExactly(
                        Map.entry("alpha-util-1.0.jar", a),
                        Map.entry("beta-util-1.0.jar", b),
                        Map.entry("core-2.0.jar", c));
    }

    private static Path write(Path root, String name, String body) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        return root;
    }
}
