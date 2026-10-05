// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.node.DiscoveredNode;
import cc.jumpkick.node.NodeDiscovery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A JVM module's own node build, beside its Java sources in {@code src/main/node}: its output lands
 * in the module's jar under {@code static/}, or in its war under {@code webapp-root}, as
 * frontend-maven-plugin's would. On the host's Node.js, with a build that needs no install.
 */
@Tag("integration")
@DisabledOnOs(OS.WINDOWS)
class NodeSideBySideE2eTest {

    private String major = "";
    private String version = "";

    @BeforeEach
    void hostNode() {
        List<DiscoveredNode> found = new NodeDiscovery().discover();
        assumeTrue(!found.isEmpty(), "a Node.js on this host");
        version = found.get(0).version();
        major = version.substring(0, version.indexOf('.'));
    }

    @Test
    void the_jar_carries_the_node_output_and_only_a_node_edit_rebuilds_it(@TempDir Path dir) throws Exception {
        module(dir, "", "");
        nodeBuild(dir.resolve("src/main/node"));

        assertThat(build(dir)).isZero();
        Path jar = dir.resolve("target/app-1.0.0.jar");
        assertThat(entry(jar, "static/index.html")).isEqualTo("<h1>one</h1>");
        assertThat(builds(dir)).isEqualTo(1);

        assertThat(build(dir)).isZero();
        assertThat(builds(dir)).as("a second build runs no node build").isEqualTo(1);

        Path mainClass = dir.resolve("target/classes/app/Main.class");
        FileTime compiled = Files.getLastModifiedTime(mainClass);
        write(dir.resolve("src/main/node/page.txt"), "two");
        assertThat(build(dir)).isZero();
        assertThat(builds(dir)).isEqualTo(2);
        assertThat(entry(jar, "static/index.html")).isEqualTo("<h1>two</h1>");
        assertThat(Files.getLastModifiedTime(mainClass)).as("no Java recompile").isEqualTo(compiled);
        assertThat(dir.resolve("src/main/resources")).doesNotExist();
    }

    @Test
    void dir_and_classpath_root_move_the_build_and_its_place_in_the_jar(@TempDir Path dir) throws Exception {
        module(dir, "dir = \"src/main/frontend\"\nclasspath-root = \"public\"\n", "");
        nodeBuild(dir.resolve("src/main/frontend"));

        assertThat(build(dir)).isZero();
        assertThat(entry(dir.resolve("target/app-1.0.0.jar"), "public/index.html"))
                .isEqualTo("<h1>one</h1>");
    }

    @Test
    void a_war_carries_the_node_output_under_its_webapp_root(@TempDir Path dir) throws Exception {
        module(dir, "webapp-root = \"jsbundles\"\n", "\n[war]\n");
        nodeBuild(dir.resolve("src/main/node"));

        assertThat(build(dir)).isZero();
        Path war = dir.resolve("target/app-1.0.0.war");
        assertThat(entry(war, "jsbundles/index.html")).isEqualTo("<h1>one</h1>");
        assertThat(dir.resolve("target/app-1.0.0/jsbundles/index.html")).hasContent("<h1>one</h1>");
    }

    @Test
    void skip_node_packages_the_output_already_built_and_says_when_there_is_none(@TempDir Path dir) throws Exception {
        module(dir, "", "");
        nodeBuild(dir.resolve("src/main/node"));
        assertThat(build(dir)).isZero();

        write(dir.resolve("src/main/node/page.txt"), "two");
        Capture.Streams skipped = Capture.both(
                () -> assertThat(run("build", "--skip-node", "-C", dir.toString(), "--cache-dir", cache(dir)))
                        .isZero());
        assertThat(builds(dir)).as(skipped.out() + skipped.err()).isEqualTo(1);
        assertThat(entry(dir.resolve("target/app-1.0.0.jar"), "static/index.html"))
                .isEqualTo("<h1>one</h1>");

        deleteTree(dir.resolve("src/main/node/dist"));
        int[] exit = {0};
        Capture.Streams streams = Capture.both(
                () -> exit[0] = run("build", "--skip-node", "-C", dir.toString(), "--cache-dir", cache(dir)));
        String out = streams.out() + streams.err();
        assertThat(exit[0]).isNotZero();
        assertThat(out).contains("run the build once without --skip-node");
    }

    @Test
    void a_package_json_at_a_jvm_module_s_root_is_still_refused(@TempDir Path dir) throws Exception {
        module(dir, "classpath-root = \"static\"\n", "");
        nodeBuild(dir);

        int[] exit = {0};
        Capture.Streams streams = Capture.both(() -> exit[0] = build(dir));
        String out = streams.out() + streams.err();
        assertThat(exit[0]).as(out).isNotZero();
        assertThat(out).contains("package.json");
    }

    /** A JVM module with {@code [node]} carrying {@code nodeKeys}, plus {@code extra} manifest lines. */
    private void module(Path dir, String nodeKeys, String extra) throws IOException {
        write(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"
                java = 25

                [node]
                version = %s
                %s%s""".formatted(major, nodeKeys, extra));
        write(dir.resolve("src/main/java/app/Main.java"), "package app; public class Main {}\n");
        LockfileWriter.write(
                Lockfile.empty(JkVersion.VERSION).withNode(new NodePin(version, null, null, Map.of())),
                dir.resolve("jk-lock.toml"));
    }

    /**
     * A node build in {@code nodeDir} that needs no install: it writes {@code dist/index.html} from
     * {@code page.txt} and counts its runs in the module's {@code builds.txt}.
     */
    private static void nodeBuild(Path nodeDir) throws IOException {
        write(
                nodeDir.resolve("package.json"),
                "{\"name\":\"web\",\"version\":\"1.0.0\",\"scripts\":{\"build\":\"node build.js\"}}");
        write(nodeDir.resolve("package-lock.json"), """
                {"name":"web","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{"":{"name":"web","version":"1.0.0"}}}
                """);
        write(nodeDir.resolve("page.txt"), "one");
        write(nodeDir.resolve("build.js"), """
                const fs = require('fs');
                const path = require('path');
                fs.mkdirSync('dist', {recursive: true});
                fs.writeFileSync('dist/index.html', '<h1>' + fs.readFileSync('page.txt', 'utf8') + '</h1>');
                let root = __dirname;
                while (!fs.existsSync(path.join(root, 'jk.toml'))) root = path.dirname(root);
                const count = path.join(root, 'builds.txt');
                const n = fs.existsSync(count) ? Number(fs.readFileSync(count, 'utf8')) : 0;
                fs.writeFileSync(count, String(n + 1));
                """);
    }

    private static int builds(Path dir) throws IOException {
        return Integer.parseInt(Files.readString(dir.resolve("builds.txt")).trim());
    }

    private static int build(Path dir) {
        return run("build", "-C", dir.toString(), "--cache-dir", cache(dir));
    }

    private static String cache(Path dir) {
        return dir.resolve("cache").toString();
    }

    private static String entry(Path archive, String name) throws IOException {
        try (JarFile file = new JarFile(archive.toFile())) {
            ZipEntry e = file.getEntry(name);
            assertThat(e).as(name + " in " + archive.getFileName()).isNotNull();
            return new String(file.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }
}
