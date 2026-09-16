// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A member shaded with relocations publishes packages jk never produces; when another member's
 * sources import them, the shaded member is a Tier-3 row naming the relocations, the importers and
 * the Maven-built artifact to depend on instead.
 */
class PomShadedSiblingImportTest {

    private static final String PARENT = """
            <parent>
              <groupId>org.demo</groupId>
              <artifactId>parent</artifactId>
              <version>2.1.0</version>
            </parent>
            """;

    @Test
    void a_member_importing_a_siblings_relocated_packages_makes_the_sibling_a_tier_three_row(@TempDir Path root)
            throws Exception {
        writeReactor(root);
        write(root, "index/src/main/java/org/demo/index/Reader.java", """
                package org.demo.index;
                import org.demo.shaded.lucene9.index.IndexReader;
                class Reader { IndexReader r; }
                """);
        write(root, "index/src/main/java/org/demo/index/Writer.java", """
                package org.demo.index;
                class Writer { org.demo.shaded.lucene9.index.IndexWriter w; }
                """);
        write(root, "index/src/test/java/org/demo/index/ReaderTest.java", """
                package org.demo.index;
                class ReaderTest {}
                """);
        write(root, "server/src/main/java/org/demo/server/Main.java", """
                package org.demo.server;
                import org.demo.shaded.lucene9.store.Directory;
                class Main { Directory d; }
                """);

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(requireNonNull(result.root().workspace()).modules()).contains("lucene9-shaded", "index", "server");
        assertThat(result.report().issues())
                .filteredOn(i -> i.severity() == ImportReport.Severity.ERROR)
                .singleElement()
                .satisfies(issue -> assertThat(issue.message())
                        .startsWith("[lucene9-shaded] `maven-shade-plugin` relocates org.apache.lucene →"
                                + " org.demo.shaded.lucene9; jk has no package relocation")
                        .contains("`index` (2 files), `server` (1 file) import them")
                        .contains("`jk mvn -pl lucene9-shaded install` publishes org.demo:lucene9-shaded:2.1.0"));
    }

    @Test
    void relocations_nobody_imports_stay_a_tier_two_row(@TempDir Path root) throws Exception {
        writeReactor(root);
        write(root, "index/src/main/java/org/demo/index/Reader.java", """
                package org.demo.index;
                import org.apache.lucene.index.IndexReader;
                class Reader { IndexReader r; }
                """);

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(result.report().issues())
                .filteredOn(i -> i.severity() == ImportReport.Severity.ERROR)
                .isEmpty();
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .anyMatch(m -> m.startsWith("[lucene9-shaded] `maven-shade-plugin` `<relocations>` org.apache.lucene →"
                        + " org.demo.shaded.lucene9"));
    }

    /** A shaded member relocating lucene, an `index` member depending on it, and a `server` member depending on `index`. */
    private static void writeReactor(Path root) throws Exception {
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>2.1.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>lucene9-shaded</module>
                    <module>index</module>
                    <module>server</module>
                  </modules>
                </project>
                """);
        write(root, "lucene9-shaded/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>lucene9-shaded</artifactId>
                  <properties>
                    <moduleName>org.demo.shaded.lucene9</moduleName>
                  </properties>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-shade-plugin</artifactId>
                        <version>3.6.0</version>
                        <executions>
                          <execution>
                            <goals><goal>shade</goal></goals>
                            <configuration>
                              <relocations>
                                <relocation>
                                  <pattern>org.apache.lucene</pattern>
                                  <shadedPattern>${moduleName}</shadedPattern>
                                </relocation>
                                <relocation>
                                  <pattern>org/apache/lucene</pattern>
                                  <shadedPattern>org/demo/shaded/lucene9</shadedPattern>
                                  <rawString>true</rawString>
                                </relocation>
                              </relocations>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(PARENT));
        write(root, "index/pom.xml", member("index", "lucene9-shaded"));
        write(root, "server/pom.xml", member("server", "index"));
    }

    private static String member(String artifactId, String dependsOn) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>%s</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.demo</groupId>
                      <artifactId>%s</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(PARENT, artifactId, dependsOn);
    }

    private static void write(Path root, String path, String text) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, text);
    }
}
