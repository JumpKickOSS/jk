// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.model.RepositorySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace root owns the one repository list the lock resolves every member against, so a
 * {@code <repository>} a member declares is hoisted onto it with its release/snapshot policy.
 */
class PomRepositoryHoistImportTest {

    @Test
    void member_repositories_are_hoisted_onto_the_root_with_their_policy(@TempDir Path root) throws Exception {
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>io.demo</groupId>
                  <artifactId>demo</artifactId>
                  <version>2.10.26</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>sdk</module>
                    <module>app</module>
                  </modules>
                </project>
                """);
        write(root, "sdk/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>io.demo</groupId>
                    <artifactId>demo</artifactId>
                    <version>2.10.26</version>
                  </parent>
                  <artifactId>sdk</artifactId>
                  <repositories>
                    <repository>
                      <id>fit2cloud-public</id>
                      <url>https://repository.fit2cloud.com/repository/fit2cloud-public/</url>
                    </repository>
                    <repository>
                      <id>central-portal-snapshots</id>
                      <url>https://central.sonatype.com/repository/maven-snapshots/</url>
                      <releases><enabled>false</enabled></releases>
                      <snapshots><enabled>true</enabled></snapshots>
                    </repository>
                  </repositories>
                </project>
                """);
        write(root, "app/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>io.demo</groupId>
                    <artifactId>demo</artifactId>
                    <version>2.10.26</version>
                  </parent>
                  <artifactId>app</artifactId>
                </project>
                """);

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(result.root().repositories())
                .extracting(RepositorySpec::name, RepositorySpec::releases, RepositorySpec::snapshots)
                .containsExactly(
                        Tuple.tuple("fit2cloud-public", true, true),
                        Tuple.tuple("central-portal-snapshots", false, true));
        String rendered = JkBuildRenderer.render(result.root());
        assertThat(rendered)
                .contains("fit2cloud-public = \"https://repository.fit2cloud.com/repository/fit2cloud-public/\"")
                .contains(
                        "central-portal-snapshots = { url = \"https://central.sonatype.com/repository/maven-snapshots/\","
                                + " releases = false }");
    }

    private static void write(Path root, String path, String xml) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, xml);
    }
}
