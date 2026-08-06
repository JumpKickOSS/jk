// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.WorkspaceProduct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Multi-module POM import: sibling edges and test-jar → product=tests. */
class PomImporterTest {

    @Test
    void multi_module_rewrites_siblings_and_test_jars(@TempDir Path root) throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                """
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
        Files.writeString(
                root.resolve("lib/pom.xml"),
                """
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
        Files.writeString(
                root.resolve("app/pom.xml"),
                """
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

        PomImporter.WorkspaceImportResult result = PomImporter.importWorkspace(root.resolve("pom.xml"));
        assertThat(result.root().isWorkspaceRoot()).isTrue();
        assertThat(result.root().workspace().modules()).containsExactly("lib", "app");

        JkBuild app = result.modules().get("app");
        assertThat(app).isNotNull();

        List<Dependency> main = app.dependencies().of(Scope.MAIN);
        assertThat(main).hasSize(1);
        assertThat(main.getFirst().isWorkspace()).isTrue();
        assertThat(main.getFirst().library()).isEqualTo("lib");
        assertThat(main.getFirst().product()).isEqualTo(WorkspaceProduct.MAIN);

        List<Dependency> test = app.dependencies().of(Scope.TEST);
        assertThat(test).anyMatch(d -> d.isWorkspace() && d.isTestsProduct() && "lib".equals(d.library()));
        assertThat(test)
                .anyMatch(d -> !d.isWorkspace() && d.module().equals("org.junit.jupiter:junit-jupiter"));
    }

    @Test
    void external_test_jar_keeps_product_tests(@TempDir Path root) throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                """
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
        JkBuild app = PomImporter.importFrom(root.resolve("pom.xml")).jkBuild();
        assertThat(app.dependencies().of(Scope.TEST))
                .anyMatch(d -> d.isTestsProduct()
                        && d.module().equals("com.acme:helpers")
                        && d.packageKey().equals("com.acme:helpers:test-jar:tests"));
    }
}
