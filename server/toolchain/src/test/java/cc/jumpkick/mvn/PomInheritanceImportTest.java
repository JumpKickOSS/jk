// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a POM inherits is imported: parents flattened, managed versions applied, BOM imports
 * inlined, properties interpolated. The parent chain is served from a loopback repository, so
 * nothing here reaches the network.
 */
class PomInheritanceImportTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    /**
     * A child of a Spring-Boot-shaped chain (starter-parent → dependencies → build, with a BOM
     * imported half-way up) imports with every version resolved and no placeholder left.
     */
    @Test
    void starter_parent_chain_resolves_every_version_offline(@TempDir Path tempDir) throws Exception {
        serveChain();
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>demo-starter-parent</artifactId>
                    <version>1.0</version>
                    <relativePath/>
                  </parent>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>0.1.0</version>
                  <properties>
                    <start-class>com.ex.App</start-class>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>com.fasterxml.jackson.core</groupId>
                      <artifactId>jackson-databind</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        PomImporter.Result result = TestImporters.over(tempDir, http.base()).importFrom(pom);
        JkBuild build = result.jkBuild();
        String rendered = JkBuildRenderer.render(build);

        assertThat(rendered).doesNotContain("${").doesNotContain("unresolved");
        assertThat(build.project().java())
                .as("maven.compiler.release=${java.version} from the top parent")
                .isEqualTo(17);
        assertThat(build.applicationOpt()).map(JkBuild.Application::main).contains("com.ex.App");
        assertThat(versions(build.dependencies().of(Scope.MAIN)))
                .containsExactly(
                        "com.google.guava:guava=33.4.0-jre",
                        "com.fasterxml.jackson.core:jackson-databind=2.18.2",
                        "org.slf4j:slf4j-api=2.0.16");
        assertThat(versions(build.dependencies().of(Scope.TEST)))
                .as("managed scope applies too, not only the version")
                .containsExactly("org.junit.jupiter:junit-jupiter=5.11.4");
        assertThat(versions(build.dependencies().of(Scope.PLATFORM)))
                .as("the published parent carries the whole inherited dependencyManagement")
                .containsExactly("org.demo:demo-starter-parent=1.0");

        List<String> messages = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();
        assertThat(result.report().hasErrors()).isFalse();
        assertThat(messages)
                .contains(
                        "versions for com.google.guava:guava, org.junit.jupiter:junit-jupiter managed by parent"
                                + " org.demo:demo-dependencies:1.0.",
                        "versions for com.fasterxml.jackson.core:jackson-databind managed by parent"
                                + " org.demo:demo-build:1.0.",
                        "dependencies org.slf4j:slf4j-api inherited from parent org.demo:demo-starter-parent:1.0.")
                .anyMatch(m ->
                        m.startsWith("`<dependencyManagement>` inherited from parent org.demo:demo-starter-parent:1.0"))
                .noneMatch(m -> m.contains("mvn help:effective-pom"))
                .noneMatch(m -> m.contains("pin was dropped"));
        assertThat(messages)
                .as("a parent's inactive profile is not this POM's to port")
                .noneMatch(m -> m.contains("profile `native`"));
    }

    /** dependencyManagement declared three parents up pins the version of a versionless dependency. */
    @Test
    void three_level_parent_chain_pins_from_the_top(@TempDir Path tempDir) throws Exception {
        http.serve(TestImporters.pomPath("org.ex", "top", "1"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.ex</groupId>
                  <artifactId>top</artifactId>
                  <version>1</version>
                  <packaging>pom</packaging>
                  <properties><commons.version>3.17.0</commons.version></properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>${commons.version}</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        http.serve(TestImporters.pomPath("org.ex", "middle", "1"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.ex</groupId><artifactId>top</artifactId><version>1</version></parent>
                  <artifactId>middle</artifactId>
                  <packaging>pom</packaging>
                </project>
                """);
        http.serve(TestImporters.pomPath("org.ex", "bottom", "1"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.ex</groupId><artifactId>middle</artifactId><version>1</version></parent>
                  <artifactId>bottom</artifactId>
                  <packaging>pom</packaging>
                </project>
                """);
        byte[] child = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.ex</groupId><artifactId>bottom</artifactId><version>1</version></parent>
                  <artifactId>leaf</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.commons</groupId>
                      <artifactId>commons-lang3</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """.getBytes(StandardCharsets.UTF_8);

        PomImporter.Result result = TestImporters.over(tempDir, http.base()).importFromBytes(child);
        assertThat(result.jkBuild().project().group()).isEqualTo("org.ex");
        assertThat(result.jkBuild().project().version()).isEqualTo("1");
        assertThat(versions(result.jkBuild().dependencies().of(Scope.MAIN)))
                .containsExactly("org.apache.commons:commons-lang3=3.17.0");
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .contains("versions for org.apache.commons:commons-lang3 managed by parent org.ex:top:1.");
    }

    /**
     * A BOM import whose version is a property the effective model left as written (the parent
     * that would value it is unresolvable) is a Tier-3 row naming the property, and no
     * {@code [platform-dependencies]} row asks a repository for the version {@code unresolved}.
     */
    @Test
    void bom_import_with_an_unresolved_version_property_is_an_error_row_not_a_platform_pin(@TempDir Path tempDir)
            throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.nowhere</groupId>
                    <artifactId>gone</artifactId>
                    <version>9</version>
                  </parent>
                  <artifactId>orphan</artifactId>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>io.netty</groupId>
                        <artifactId>netty-bom</artifactId>
                        <version>${netty.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>2.0.16</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        PomImporter.Result result = TestImporters.offline(tempDir).importFrom(pom);
        assertThat(result.jkBuild().dependencies().of(Scope.PLATFORM)).isEmpty();
        assertThat(JkBuildRenderer.render(result.jkBuild())).doesNotContain("unresolved");
        assertThat(result.report().issues())
                .filteredOn(i -> i.severity() == ImportReport.Severity.ERROR)
                .extracting(ImportReport.Issue::message)
                .anyMatch(m -> m.contains("io.netty:netty-bom") && m.contains("netty.version"));
    }

    /**
     * A published parent that declares a repository whose URL is a property nothing values (a
     * snapshot repository behind {@code ${snapshots.url}}) still hands its managed versions down:
     * Maven keeps such a repository until a fetch from it, so it is no reason to refuse the parent.
     */
    @Test
    void a_parents_repository_with_a_placeholder_url_does_not_stop_inheritance(@TempDir Path tempDir) throws Exception {
        http.serve(TestImporters.pomPath("org.ex", "top", "1"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.ex</groupId>
                  <artifactId>top</artifactId>
                  <version>1</version>
                  <packaging>pom</packaging>
                  <repositories>
                    <repository>
                      <id>snapshots</id>
                      <url>${snapshots.url}</url>
                      <releases><enabled>false</enabled></releases>
                    </repository>
                  </repositories>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>3.17.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        byte[] child = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.ex</groupId><artifactId>top</artifactId><version>1</version></parent>
                  <artifactId>leaf</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.commons</groupId>
                      <artifactId>commons-lang3</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """.getBytes(StandardCharsets.UTF_8);

        PomImporter.Result result = TestImporters.over(tempDir, http.base()).importFromBytes(child);

        assertThat(result.report().hasErrors())
                .as(String.join("\n", messages(result)))
                .isFalse();
        assertThat(versions(result.jkBuild().dependencies().of(Scope.MAIN)))
                .containsExactly("org.apache.commons:commons-lang3=3.17.0");
    }

    /** A parent no repository has is a Tier-3 row; the POM's own declarations still import. */
    @Test
    void missing_parent_is_an_error_row_not_a_crash(@TempDir Path tempDir) throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.nowhere</groupId>
                    <artifactId>gone</artifactId>
                    <version>9</version>
                  </parent>
                  <artifactId>orphan</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.slf4j</groupId>
                      <artifactId>slf4j-api</artifactId>
                      <version>2.0.16</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        PomImporter.Result result = TestImporters.offline(tempDir).importFrom(pom);
        assertThat(result.jkBuild().project().group()).isEqualTo("org.nowhere");
        assertThat(result.jkBuild().project().version()).isEqualTo("9");
        assertThat(versions(result.jkBuild().dependencies().of(Scope.MAIN)))
                .containsExactly("com.google.guava:guava=unresolved", "org.slf4j:slf4j-api=2.0.16");
        assertThat(result.report().issues())
                .filteredOn(i -> i.severity() == ImportReport.Severity.ERROR)
                .extracting(ImportReport.Issue::message)
                .anyMatch(m -> m.startsWith("`<parent>` org.nowhere:gone:9 could not be resolved"));
        assertThat(result.report().renderMarkdown("pom.xml")).contains("## Tier 3 — not imported");
    }

    /**
     * Inside a reactor the root answers a module's {@code <parent>} even when {@code <relativePath/>}
     * is empty (which sends Maven to the network): the root's dependencyManagement pins the module's
     * versions and no repository is asked.
     */
    @Test
    void reactor_parent_resolves_from_the_sibling_pom(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules><module>app</module></modules>
                  <properties><guava.version>33.4.0-jre</guava.version></properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.google.guava</groupId>
                        <artifactId>guava</artifactId>
                        <version>${guava.version}</version>
                      </dependency>
                      <dependency>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-api</artifactId>
                        <version>2.0.16</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
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
                    <relativePath/>
                  </parent>
                  <artifactId>app</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));
        JkBuild app = requireNonNull(result.modules().get("app"));
        assertThat(versions(app.dependencies().of(Scope.MAIN))).containsExactly("com.google.guava:guava=33.4.0-jre");
        assertThat(app.dependencies().of(Scope.PLATFORM))
                .as("a sibling is not a published BOM")
                .isEmpty();
        assertThat(result.report().hasErrors()).isFalse();
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .contains("[app] versions for com.google.guava:guava managed by workspace parent com.ex:parent:1.0.0.")
                .anyMatch(m -> m.startsWith(
                        "[app] `<dependencyManagement>` in workspace parent com.ex:parent:1.0.0 pins 1 version"));
    }

    private void serveChain() throws Exception {
        for (String artifact : List.of("demo-build", "demo-bom", "demo-dependencies", "demo-starter-parent")) {
            http.serve(
                    TestImporters.pomPath("org.demo", artifact, "1.0"),
                    TestImporters.fixture("parent-chain", artifact + "-1.0.pom"));
        }
    }

    private static List<String> messages(PomImporter.Result result) {
        return TestImporters.messages(result);
    }

    private static List<String> versions(List<Dependency> deps) {
        return deps.stream().map(d -> d.module() + "=" + d.version().raw()).toList();
    }
}
