// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PinPolicy;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.PomParseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Multi-module POM import: sibling edges and test-jar → kind=tests. */
class PomImporterTest {

    /**
     * An imported {@code pom.xml} is someone else's file — a downloaded archive, a shared drive, a
     * repo just cloned. The importer reads it through the same hardened parser as everything else,
     * so a DOCTYPE is rejected and the entity it declares is never fetched.
     */
    @Test
    void a_pom_with_a_system_entity_is_rejected_not_resolved(@TempDir Path root) throws Exception {
        Path secret = root.resolve("secret.txt");
        Files.writeString(secret, "TOP_SECRET_VALUE");

        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE project [ <!ENTITY leak SYSTEM "file://%s"> ]>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>&leak;</artifactId>
                  <version>1.0.0</version>
                </project>
                """.formatted(secret.toAbsolutePath()));

        PomImporter importer = TestImporters.offline(root);
        assertThatThrownBy(() -> importer.importFrom(pom))
                .as("the DOCTYPE is refused outright, not merely the reference to what it declares")
                .hasMessageContaining("DOCTYPE")
                .hasMessageNotContaining("TOP_SECRET_VALUE")
                .isInstanceOf(PomParseException.class);
        assertThatThrownBy(() -> importer.importWorkspace(pom))
                .hasMessageContaining("DOCTYPE")
                .hasMessageNotContaining("TOP_SECRET_VALUE")
                .isInstanceOf(PomParseException.class);
    }

    /**
     * A POM's direct version is the version Maven built with, whatever a transitive asked for; the
     * imported manifest says so, and so does the workspace root that owns the lock.
     */
    @Test
    void an_imported_pom_resolves_its_pins_nearest_wins(@TempDir Path root) throws Exception {
        PomImporter.Result single = TestImporters.importXml(root, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        assertThat(single.jkBuild().build().pinPolicy()).isEqualTo(PinPolicy.NEAREST);

        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>lib</module>
                  </modules>
                </project>
                """);
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.ex</groupId><artifactId>parent</artifactId><version>1.0.0</version>
                  </parent>
                  <artifactId>lib</artifactId>
                </project>
                """);
        PomImporter.WorkspaceImportResult ws = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));
        assertThat(ws.root().build().pinPolicy()).isEqualTo(PinPolicy.NEAREST);
        assertThat(requireNonNull(ws.modules().get("lib")).build().pinPolicy()).isEqualTo(PinPolicy.NEAREST);
    }

    @Test
    void multi_module_rewrites_siblings_and_test_jars(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>lib</module>
                    <module>app</module>
                  </modules>
                </project>
                """);
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.ex</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>lib</artifactId>
                </project>
                """);
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.ex</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>app</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.ex</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.ex</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0.0</version>
                      <type>test-jar</type>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.10.0</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));
        assertThat(result.root().isWorkspaceRoot()).isTrue();
        assertThat(requireNonNull(result.root().workspace()).modules()).containsExactly("lib", "app");

        JkBuild app = requireNonNull(result.modules().get("app"));

        List<Dependency> main = app.dependencies().of(Scope.MAIN);
        assertThat(main).hasSize(1);
        assertThat(main.getFirst().isWorkspace()).isTrue();
        assertThat(main.getFirst().library()).isEqualTo("lib");
        assertThat(main.getFirst().kind()).isEqualTo(DependencyKind.MAIN);

        List<Dependency> test = app.dependencies().of(Scope.TEST);
        assertThat(test).anyMatch(d -> d.isWorkspace() && d.isTestsKind() && "lib".equals(d.library()));
        assertThat(test).anyMatch(d -> !d.isWorkspace() && d.module().equals("org.junit.jupiter:junit-jupiter"));
    }

    @Test
    void a_junit_below_the_vintage_floor_is_raised_with_a_note_and_a_supported_one_is_kept(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>junit</groupId><artifactId>junit</artifactId><version>3.8.2</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);
        PomImporter.Result raised = TestImporters.offline(root).importFrom(root.resolve("pom.xml"));
        assertThat(raised.jkBuild().dependencies().of(Scope.TEST))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.module()).isEqualTo("junit:junit");
                    assertThat(d.version().raw()).isEqualTo("4.13.2");
                });
        assertThat(raised.report().issues())
                .anyMatch(i -> i.message().contains("junit:junit 3.8.2 raised to 4.13.2")
                        && i.message().contains("junit-vintage-engine"));

        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>junit</groupId><artifactId>junit</artifactId><version>4.12</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);
        PomImporter.Result kept = TestImporters.offline(root).importFrom(root.resolve("pom.xml"));
        assertThat(kept.jkBuild().dependencies().of(Scope.TEST))
                .singleElement()
                .satisfies(d -> assertThat(d.version().raw()).isEqualTo("4.12"));
        assertThat(kept.report().issues()).noneMatch(i -> i.message().contains("raised to"));
    }

    @Test
    void jar_and_test_jar_of_same_ga_yield_two_entries(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.logging.log4j</groupId>
                      <artifactId>log4j-core</artifactId>
                      <version>2.24.0</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.apache.logging.log4j</groupId>
                      <artifactId>log4j-core</artifactId>
                      <version>2.24.0</version>
                      <type>test-jar</type>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);

        JkBuild app =
                TestImporters.offline(root).importFrom(root.resolve("pom.xml")).jkBuild();
        List<Dependency> test = app.dependencies().of(Scope.TEST);
        // Both packages survive with distinct, deterministic handles.
        assertThat(test).extracting(Dependency::library).containsExactly("log4j-core", "log4j-core-tests");
        assertThat(test)
                .extracting(Dependency::packageKey)
                .containsExactly(
                        "org.apache.logging.log4j:log4j-core:jar:",
                        "org.apache.logging.log4j:log4j-core:test-jar:tests");
    }

    @Test
    void colliding_handles_across_groups_are_uniquified(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.a</groupId>
                      <artifactId>util</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.b</groupId>
                      <artifactId>util</artifactId>
                      <version>2.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        PomImporter.Result result = TestImporters.offline(root).importFrom(root.resolve("pom.xml"));
        List<Dependency> main = result.jkBuild().dependencies().of(Scope.MAIN);
        assertThat(main).extracting(Dependency::library).containsExactly("util", "util-2");
        assertThat(main).extracting(Dependency::module).containsExactly("com.a:util", "com.b:util");
        assertThat(result.report().issues()).anyMatch(i -> i.message().contains("`util` collides"));
    }

    @Test
    void external_test_jar_keeps_kind_tests(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>helpers</artifactId>
                      <version>1.2.3</version>
                      <type>test-jar</type>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);

        // Single-module import path (not workspace rewrite).
        JkBuild app =
                TestImporters.offline(root).importFrom(root.resolve("pom.xml")).jkBuild();
        assertThat(app.dependencies().of(Scope.TEST))
                .anyMatch(d -> d.isTestsKind()
                        && d.module().equals("com.acme:helpers")
                        && d.packageKey().equals("com.acme:helpers:test-jar:tests"));
    }
}
