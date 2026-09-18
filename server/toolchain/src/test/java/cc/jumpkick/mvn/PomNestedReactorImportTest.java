// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A reactor is a tree: an aggregator module lists modules of its own, and a profile active on this
 * machine can list modules too. Import walks the whole tree into one flat workspace whose module
 * paths are relative to the root.
 */
class PomNestedReactorImportTest {

    private static final String PARENT = """
            <parent>
              <groupId>org.demo</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
            </parent>
            """;

    @Test
    void aggregator_modules_recurse_into_a_flat_workspace_of_relative_paths(@TempDir Path root) throws Exception {
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>libs</module>
                    <module>app</module>
                    <module>missing</module>
                  </modules>
                  <profiles>
                    <profile>
                      <id>extras</id>
                      <activation><activeByDefault>true</activeByDefault></activation>
                      <modules>
                        <module>extra</module>
                      </modules>
                    </profile>
                    <profile>
                      <id>legacy</id>
                      <activation><jdk>1.8</jdk></activation>
                      <modules>
                        <module>legacy</module>
                      </modules>
                    </profile>
                  </profiles>
                </project>
                """);
        write(root, "libs/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>libs-parent</artifactId>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                    <module>util/pom.xml</module>
                  </modules>
                </project>
                """.formatted(PARENT));
        write(root, "libs/core/pom.xml", leaf("core", "../../libs/pom.xml", "libs-parent"));
        write(root, "libs/util/pom.xml", leaf("util", "../pom.xml", "libs-parent"));
        write(root, "extra/pom.xml", leaf("extra", "../pom.xml", "parent"));
        write(root, "legacy/pom.xml", leaf("legacy", "../pom.xml", "parent"));
        write(root, "app/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>app</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.demo</groupId>
                      <artifactId>core</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(PARENT));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(requireNonNull(result.root().workspace()).modules())
                .as("aggregators are walked, not listed; active-profile modules are listed; inactive ones are not")
                .containsExactly("libs/core", "libs/util", "app", "extra");
        assertThat(result.modules().keySet()).containsExactlyInAnyOrder("libs/core", "libs/util", "app", "extra");
        JkBuild app = requireNonNull(result.modules().get("app"));
        List<Dependency> main = app.dependencies().of(Scope.MAIN);
        assertThat(main).singleElement().satisfies(d -> {
            assertThat(d.isWorkspace())
                    .as("a nested sibling is a workspace edge")
                    .isTrue();
            assertThat(d.library()).isEqualTo("core");
        });
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .anyMatch(m -> m.startsWith("workspace module `missing` has no pom.xml"));
    }

    /**
     * A profile activated by name — {@code jk import -P}, as Maven's {@code -P} — contributes its
     * modules whether or not the POM would activate it here: Baeldung's tutorials list every module
     * under profiles with no activation at all, so its reactor imports through {@code -P default}.
     */
    @Test
    void a_profile_activated_by_name_contributes_its_modules(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <profiles>
                    <profile>
                      <id>default</id>
                      <modules>
                        <module>app</module>
                      </modules>
                    </profile>
                    <profile>
                      <id>default-heavy</id>
                      <modules>
                        <module>heavy</module>
                      </modules>
                    </profile>
                    <profile>
                      <id>legacy</id>
                      <activation><jdk>1.8</jdk></activation>
                      <modules>
                        <module>legacy</module>
                      </modules>
                    </profile>
                  </profiles>
                </project>
                """);
        write(root, "app/pom.xml", leaf("app", "../pom.xml", "parent"));
        write(root, "heavy/pom.xml", leaf("heavy", "../pom.xml", "parent"));
        write(root, "legacy/pom.xml", leaf("legacy", "../pom.xml", "parent"));

        PomImporter.WorkspaceImportResult plain = TestImporters.offline(tmp).importWorkspace(root.resolve("pom.xml"));
        assertThat(requireNonNull(plain.root().workspace()).modules())
                .as("no profile activates itself: nothing to build")
                .isEmpty();
        assertThat(plain.report().issues())
                .extracting(ImportReport.Issue::message)
                .anySatisfy(m -> assertThat(m)
                        .startsWith("`<modules>` are declared only in profiles that are not active on this machine"
                                + " (default, default-heavy, legacy)")
                        .contains("`jk import pom.xml -P default`"));

        PomImporter.WorkspaceImportResult activated = TestImporters.offline(tmp)
                .activeProfiles(List.of("default", "default-heavy", "nowhere"))
                .importWorkspace(root.resolve("pom.xml"));

        assertThat(requireNonNull(activated.root().workspace()).modules())
                .as("the named profiles' modules, in profile order; the JDK-gated one stays out")
                .containsExactly("app", "heavy");
        assertThat(activated.modules().keySet()).containsExactlyInAnyOrder("app", "heavy");
        assertThat(activated.report().issues())
                .extracting(ImportReport.Issue::message)
                .anySatisfy(m -> assertThat(m).startsWith("`-P nowhere` names a profile the root POM does not declare"))
                .noneMatch(m -> m.startsWith("`<modules>` are declared only in profiles"));
    }

    @Test
    void a_module_outside_the_root_is_reported_and_skipped(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>../outside</module>
                    <module>inside</module>
                  </modules>
                </project>
                """);
        write(tmp, "outside/pom.xml", leaf("outside", "../root/pom.xml", "parent"));
        write(root, "inside/pom.xml", leaf("inside", "../pom.xml", "parent"));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(tmp).importWorkspace(root.resolve("pom.xml"));

        assertThat(requireNonNull(result.root().workspace()).modules()).containsExactly("inside");
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .anyMatch(m -> m.startsWith("module `../outside` of the root pom.xml lies outside the root directory"));
    }

    /**
     * A sibling is a workspace edge wherever it sits in the tree and however its version is spelled:
     * nested under an aggregator, in a sub-group, versioned by {@code ${revision}}, declared with
     * {@code ${project.version}}, with a stale literal version, or as its {@code test-jar}.
     */
    @Test
    void nested_and_property_versioned_siblings_are_workspace_edges_whatever_the_declared_version(@TempDir Path root)
            throws Exception {
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>io.demo</groupId>
                  <artifactId>demo-parent</artifactId>
                  <version>${revision}</version>
                  <packaging>pom</packaging>
                  <properties>
                    <revision>3.6.2-SNAPSHOT</revision>
                  </properties>
                  <modules>
                    <module>collector</module>
                    <module>server</module>
                  </modules>
                </project>
                """);
        write(root, "collector/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>io.demo</groupId>
                    <artifactId>demo-parent</artifactId>
                    <version>${revision}</version>
                  </parent>
                  <groupId>io.demo.collector</groupId>
                  <artifactId>collector-parent</artifactId>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                    <module>kafka</module>
                  </modules>
                </project>
                """);
        write(root, "collector/core/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>io.demo.collector</groupId>
                    <artifactId>collector-parent</artifactId>
                    <version>${revision}</version>
                  </parent>
                  <artifactId>collector</artifactId>
                </project>
                """);
        write(root, "collector/kafka/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>io.demo.collector</groupId>
                    <artifactId>collector-parent</artifactId>
                    <version>${revision}</version>
                  </parent>
                  <artifactId>collector-kafka</artifactId>
                </project>
                """);
        write(root, "server/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>io.demo</groupId>
                    <artifactId>demo-parent</artifactId>
                    <version>${revision}</version>
                  </parent>
                  <artifactId>server</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>${project.groupId}.collector</groupId>
                      <artifactId>collector</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>io.demo.collector</groupId>
                      <artifactId>collector-kafka</artifactId>
                      <version>3.6.1</version>
                    </dependency>
                    <dependency>
                      <groupId>io.demo.collector</groupId>
                      <artifactId>collector</artifactId>
                      <version>${project.version}</version>
                      <type>test-jar</type>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(requireNonNull(result.root().workspace()).modules())
                .containsExactly("collector/core", "collector/kafka", "server");
        JkBuild server = requireNonNull(result.modules().get("server"));
        assertThat(server.project().version()).isEqualTo("3.6.2-SNAPSHOT");
        assertThat(server.dependencies().of(Scope.MAIN))
                .allMatch(Dependency::isWorkspace)
                .extracting(Dependency::library)
                .containsExactly("collector", "collector-kafka");
        assertThat(server.dependencies().of(Scope.TEST)).singleElement().satisfies(d -> {
            assertThat(d.isWorkspace()).isTrue();
            assertThat(d.isTestsKind()).isTrue();
            assertThat(d.library()).isEqualTo("collector");
        });
        assertThat(result.report().hasErrors()).isFalse();
    }

    private static String leaf(String artifactId, String relativePath, String parentArtifactId) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0.0</version>
                    <relativePath>%s</relativePath>
                  </parent>
                  <artifactId>%s</artifactId>
                </project>
                """.formatted(parentArtifactId, relativePath, artifactId);
    }

    private static void write(Path root, String path, String xml) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, xml);
    }
}
