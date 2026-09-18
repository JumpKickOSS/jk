// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static cc.jumpkick.mvn.TestImporters.messages;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.PluginConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rule set, header and suppressions a {@code maven-checkstyle-plugin} names as resources of
 * its dependency jars: {@code jk import} reads each jar to confirm the resource is there.
 */
class PomLintClasspathResourceImportTest {

    /**
     * A rule set the module does not hold is a resource of a {@code checkstyle-classpath} jar; the
     * import reads the jar to confirm it — a row when no jar holds it, none when one does.
     */
    @Test
    void a_classpath_rule_set_is_confirmed_in_the_jar_it_names(@TempDir Path tempDir) throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        serveJar(repo, "com.acme", "rules", "1.0", "acme/checkstyle.xml", "acme/header.txt");

        PomImporter.Result held =
                importCheckstyle(tempDir.resolve("held"), repo, "acme/checkstyle.xml", "acme/header.txt");
        PluginConfig lint = held.jkBuild().pluginConfig("lint").orElseThrow();
        assertThat(lint.values())
                .containsEntry("checkstyle", "acme/checkstyle.xml")
                .containsEntry("checkstyle-header", "acme/header.txt")
                .containsEntry("checkstyle-classpath", List.of("com.acme:rules:1.0"));
        assertThat(messages(held)).noneMatch(m -> m.contains("none of the plugin's dependency jars"));

        PomImporter.Result missing =
                importCheckstyle(tempDir.resolve("missing"), repo, "acme/other.xml", "acme/header.txt");
        assertThat(missing.jkBuild().pluginConfig("lint").orElseThrow().values())
                .containsEntry("checkstyle", "acme/other.xml");
        assertThat(messages(missing))
                .anyMatch(m -> m.contains("`acme/other.xml`")
                        && m.contains("none of the plugin's dependency jars holds (com.acme:rules:1.0)"))
                .noneMatch(m -> m.contains("`acme/header.txt`"));

        Path empty = Files.createDirectories(tempDir.resolve("empty-repo"));
        PomImporter.Result unserved =
                importCheckstyle(tempDir.resolve("unserved"), empty, "acme/checkstyle.xml", "acme/header.txt");
        assertThat(messages(unserved))
                .anyMatch(m -> m.contains("`acme/checkstyle.xml`")
                        && m.contains("could not confirm")
                        && m.contains("com.acme:rules:1.0"));
    }

    /** A jar under {@code repo} in Maven layout holding empty entries of the given names. */
    static void serveJar(Path repo, String group, String artifact, String version, String... entries) throws Exception {
        Path jar = repo.resolve(
                TestImporters.pomPath(group, artifact, version).substring(1).replace(".pom", ".jar"));
        Files.createDirectories(Objects.requireNonNull(jar.getParent()));
        try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.write(("<!-- " + entry + " -->").getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    /** A POM whose Checkstyle plugin depends on {@code com.acme:rules:1.0} and names {@code config} and {@code header}. */
    private static PomImporter.Result importCheckstyle(Path dir, Path repo, String config, String header)
            throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-checkstyle-plugin</artifactId>
                        <version>3.6.0</version>
                        <dependencies>
                          <dependency>
                            <groupId>com.acme</groupId>
                            <artifactId>rules</artifactId>
                            <version>1.0</version>
                          </dependency>
                        </dependencies>
                        <configuration>
                          <configLocation>%s</configLocation>
                          <headerLocation>%s</headerLocation>
                        </configuration>
                        <executions><execution><goals><goal>check</goal></goals></execution></executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(config, header));
        return TestImporters.over(dir, repo.toUri()).importFrom(project.resolve("pom.xml"));
    }
}
