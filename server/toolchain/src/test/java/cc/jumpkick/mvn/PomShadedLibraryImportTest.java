// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A member shaded with relocations and no main is a library whose fat jar moves packages: it
 * imports as a {@code [library]} module carrying the relocations, and the members that import the
 * shaded packages keep their workspace edge — they compile and run against its {@code -all.jar}.
 */
class PomShadedLibraryImportTest {

    private static final String PARENT = """
            <parent>
              <groupId>org.demo</groupId>
              <artifactId>parent</artifactId>
              <version>2.1.0</version>
            </parent>
            """;

    @Test
    void a_shaded_member_without_a_main_imports_as_a_library_assembly_its_importers_depend_on(@TempDir Path root)
            throws Exception {
        writeReactor(root);
        write(root, "index/src/main/java/org/demo/index/Reader.java", """
                package org.demo.index;
                import org.demo.shaded.lucene9.index.IndexReader;
                class Reader { IndexReader r; }
                """);
        write(root, "server/src/main/java/org/demo/server/Main.java", """
                package org.demo.server;
                import org.demo.shaded.lucene9.store.Directory;
                class Main { Directory d; }
                """);

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(requireNonNull(result.root().workspace()).modules()).contains("lucene9-shaded", "index", "server");
        JkBuild shaded = requireNonNull(result.modules().get("lucene9-shaded"));
        assertThat(shaded.applicationOpt())
                .as("a library has no main and so no [application]")
                .isEmpty();
        assertThat(shaded.libraryOpt()).isPresent();
        assertThat(shaded.assembly()).isTrue();
        assertThat(shaded.relocate()).containsExactly(Map.entry("org.apache.lucene", "org.demo.shaded.lucene9"));
        assertThat(JkBuildRenderer.render(shaded))
                .contains(
                        "[library]\nassembly = true\nrelocate = { \"org.apache.lucene\" = \"org.demo.shaded.lucene9\" }");
        assertThat(requireNonNull(result.modules().get("index")).dependencies().of(Scope.MAIN))
                .as("the importer keeps the workspace edge: the sibling's -all.jar is what index compiles against")
                .anyMatch(d -> d.isWorkspace() && "lucene9-shaded".equals(d.workspaceName()));
        assertThat(result.report().issues())
                .filteredOn(i -> i.severity() == ImportReport.Severity.ERROR)
                .isEmpty();
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .noneMatch(m -> m.contains("no `<mainClass>`"))
                .noneMatch(m -> m.contains("Keep building this module with Maven"));
    }

    @Test
    void the_raw_string_twin_of_a_package_rule_is_covered_by_it(@TempDir Path root) throws Exception {
        writeReactor(root);

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .noneMatch(m -> m.contains("`<relocations>`"));
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
