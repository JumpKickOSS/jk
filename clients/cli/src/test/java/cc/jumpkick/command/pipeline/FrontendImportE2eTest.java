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
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk import} of a reactor whose root runs frontend-maven-plugin and whose bundler writes into
 * the war module: the front end becomes a generated node module, and {@code jk build} puts its
 * output in the war where the bundler used to. On the host's Node.js, with a build that needs no install.
 */
@Tag("integration")
@DisabledOnOs(OS.WINDOWS)
class FrontendImportE2eTest {

    @Test
    void the_imported_front_end_builds_into_the_war_under_its_old_path(@TempDir Path dir) throws Exception {
        List<DiscoveredNode> found = new NodeDiscovery().discover();
        assumeTrue(!found.isEmpty(), "a Node.js on this host");
        String version = found.get(0).version();
        reactor(dir, version);

        Capture.Streams dry = Capture.both(() ->
                assertThat(run("import", "--dry-run", "-C", dir.toString())).isZero());
        assertThat(dry.out() + dry.err()).contains("dry run");
        try (var listing = Files.walk(dir)) {
            assertThat(dir.resolve("web"))
                    .as(dry.out() + dry.err() + "\n"
                            + listing.map(dir::relativize).toList())
                    .doesNotExist();
        }

        Capture.Streams imported = Capture.both(
                () -> assertThat(run("import", "-C", dir.toString())).isZero());
        String log = imported.out() + imported.err();
        assertThat(dir.resolve("web/package.json")).as(log).exists();
        assertThat(dir.resolve("package.json")).doesNotExist();
        assertThat(Files.readString(dir.resolve("web/webpack.config.js"))).contains("\"dist\"");

        LockfileWriter.write(
                Lockfile.empty(JkVersion.VERSION).withNode(new NodePin(version, null, null, Map.of())),
                dir.resolve("jk-lock.toml"));
        int[] exit = {0};
        Capture.Streams built = Capture.both(() -> exit[0] = run(
                "build",
                "--skip-tests",
                "-C",
                dir.toString(),
                "--cache-dir",
                dir.resolve("cache").toString()));
        assertThat(exit[0])
                .as(built.out() + built.err() + "\n--- jk.toml\n" + Files.readString(dir.resolve("jk.toml"))
                        + "\n--- war/jk.toml\n" + Files.readString(dir.resolve("war/jk.toml"))
                        + "\n--- web/jk.toml\n" + Files.readString(dir.resolve("web/jk.toml")))
                .isZero();
        assertThat(entry(dir.resolve("war/target/app.war"), "ui/index.html")).isEqualTo("<h1>jenkins</h1>");
        assertThat(dir.resolve("war/src/main/webapp/ui"))
                .as("nothing is built into src/")
                .doesNotExist();
    }

    private static void reactor(Path dir, String version) throws IOException {
        write(dir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>war</module></modules>
                  <build><plugins>
                    <plugin>
                      <groupId>com.github.eirslett</groupId>
                      <artifactId>frontend-maven-plugin</artifactId>
                      <version>2.0.2</version>
                      <inherited>false</inherited>
                      <executions>
                        <execution><id>node</id><goals><goal>install-node-and-npm</goal></goals>
                          <configuration><nodeVersion>v%s</nodeVersion></configuration></execution>
                        <execution><id>install</id><goals><goal>npm</goal></goals>
                          <configuration><arguments>ci</arguments></configuration></execution>
                        <execution><id>bundle</id><goals><goal>npm</goal></goals><phase>generate-sources</phase>
                          <configuration><arguments>run build</arguments></configuration></execution>
                      </executions>
                    </plugin>
                  </plugins></build>
                </project>
                """.formatted(version));
        write(dir.resolve("war/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>app-war</artifactId>
                  <packaging>war</packaging>
                  <build><finalName>app</finalName></build>
                </project>
                """);
        write(dir.resolve("war/src/main/webapp/index.html"), "<html/>");
        write(
                dir.resolve("package.json"),
                "{\"name\":\"ui\",\"version\":\"1.0.0\",\"scripts\":{\"build\":\"node src/main/js/build.js\"}}");
        write(dir.resolve("package-lock.json"), """
                {"name":"ui","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{"":{"name":"ui","version":"1.0.0"}}}
                """);
        write(dir.resolve("webpack.config.js"), """
                const path = require("path");
                module.exports = {
                  entry: { app: [path.join(__dirname, "src/main/js/build.js")] },
                  output: { path: path.join(__dirname, "war/src/main/webapp/ui") },
                };
                """);
        write(dir.resolve("src/main/js/build.js"), """
                const fs = require('fs');
                const out = require('../../../webpack.config.js').output.path;
                fs.mkdirSync(out, {recursive: true});
                fs.writeFileSync(out + '/index.html', '<h1>jenkins</h1>');
                """);
    }

    private static String entry(Path archive, String name) throws IOException {
        try (JarFile file = new JarFile(archive.toFile())) {
            ZipEntry e = file.getEntry(name);
            assertThat(e).as(name + " in " + archive.getFileName()).isNotNull();
            return new String(file.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }
}
