// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.ShadowManifests;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The module edges the graph worker computes reach from: every way the build lets one module see
 * another, drawn from real manifests, including the ways a {@code workspace = true} entry in a
 * top-level dependency table does not spell.
 */
class GraphModuleEdgesTest {

    @AfterEach
    void noShadows() {
        ShadowManifests.install(dir -> List.of());
    }

    @Test
    void aWorkspaceEntryAndACoordinateThatNamesAMemberAreEdges(@TempDir Path root) throws IOException {
        workspace(root, "core", "app", "lib", "other");
        member(root, "core", "");
        member(root, "app", "[test-dependencies]\ncore = { workspace = true }\n");
        member(root, "lib", "[dependencies]\ncore = \"t:core:1.0\"\n");
        member(root, "other", "");
        assertThat(GraphModuleEdges.rows(root)).contains("core/", "app/\tcore/", "lib/\tcore/", "other/");
    }

    /** A published library whose locked POM depends on a member: the member is on the consumer's classpath. */
    @Test
    void aMemberBehindALibrarysPomIsAnEdge(@TempDir Path root) throws IOException {
        workspace(root, "core", "app");
        member(root, "core", "");
        member(root, "app", "[dependencies]\nmiddle = \"com.foo:middle:1.0\"\n");
        Files.writeString(root.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"

                [[artifact]]
                name     = "com.foo:middle:jar:"
                version  = "1.0"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:00"
                scopes   = ["main"]
                deps = [
                  "t:core:jar:@1.0",
                ]
                """);
        assertThat(GraphModuleEdges.rows(root)).contains("app/\tcore/");
    }

    /** A variant value's dependencies are applied by the plan, not the build graph; every value counts. */
    @Test
    void aVariantValuesDependencyOnAMemberIsAnEdge(@TempDir Path root) throws IOException {
        workspace(root, "core", "app");
        member(root, "core", "");
        member(root, "app", """
                [variants.tier.free]
                [variants.tier.paid]
                [variants.tier.paid.dependencies]
                core = { workspace = true }
                """);
        assertThat(buildGraphSees(root, "app", "core"))
                .as("the build graph leaves it to the plan")
                .isFalse();
        assertThat(GraphModuleEdges.rows(root)).contains("app/\tcore/");
    }

    @Test
    void aPathDependencyOnAMembersDirectoryIsAnEdge(@TempDir Path root) throws IOException {
        workspace(root, "core", "app");
        member(root, "core", "");
        member(root, "app", "[dependencies]\ncore = \"../core\"\n");
        assertThat(buildGraphSees(root, "app", "core"))
                .as("the build graph draws no edge")
                .isFalse();
        assertThat(GraphModuleEdges.rows(root)).contains("app/\tcore/");
    }

    /**
     * A Maven reactor builds in place from the shadow manifests its POMs render: a dependency on a
     * sibling that only a profile Maven activates here declares is an edge.
     */
    @Test
    void aSiblingOnlyAnActiveMavenProfileDeclaresIsAnEdge(@TempDir Path root) throws IOException {
        ShadowManifests.install(dir -> List.of());
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>t</groupId><artifactId>parent</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                  <properties><maven.compiler.release>25</maven.compiler.release></properties>
                  <modules><module>core</module><module>app</module></modules>
                </project>
                """);
        pom(root, "core", "");
        pom(root, "app", """
                  <profiles>
                    <profile>
                      <id>with-core</id>
                      <activation><activeByDefault>true</activeByDefault></activation>
                      <dependencies>
                        <dependency><groupId>t</groupId><artifactId>core</artifactId><version>1.0</version></dependency>
                      </dependencies>
                    </profile>
                  </profiles>
                """);
        assertThat(ManifestPaths.manifestIn(root)).as("a shadowed reactor").isNotEqualTo(root.resolve("jk.toml"));
        assertThat(GraphModuleEdges.rows(root)).contains("app/\tcore/");
    }

    @Test
    void aWorkspaceWhoseGraphDoesNotResolveHasNoEdges(@TempDir Path root) throws IOException {
        workspace(root, "core", "app");
        member(root, "core", "[dependencies]\napp = { workspace = true }\n");
        member(root, "app", "[dependencies]\ncore = { workspace = true }\n");
        assertThat(GraphModuleEdges.rows(root))
                .as("a cycle: the worker counts every module")
                .isEmpty();
    }

    private static boolean buildGraphSees(Path root, String from, String to) throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(root, JkBuildParser.parse(root.resolve("jk.toml")));
        Path dir = BuildGraph.canonicalPath(root.resolve(from));
        return graph.edges().getOrDefault(dir, Set.of()).contains(BuildGraph.canonicalPath(root.resolve(to)));
    }

    private static void workspace(Path root, String... members) throws IOException {
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "root"
                version = "1.0"
                java = 25

                [workspace]
                modules = [%s]
                """.formatted(String.join(
                ", ", Arrays.stream(members).map(m -> "\"" + m + "\"").toList())));
    }

    private static void member(Path root, String name, String extra) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Path src = Files.createDirectories(dir.resolve("src/main/java/t"));
        Files.writeString(src.resolve("A.java"), "package t; class A {}\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "%s"
                version = "1.0"
                java = 25

                %s""".formatted(name, extra));
    }

    private static void pom(Path root, String name, String extra) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Path src = Files.createDirectories(dir.resolve("src/main/java/t"));
        Files.writeString(src.resolve("A.java"), "package t; class A {}\n");
        Files.writeString(dir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>t</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>%s</artifactId>
                %s</project>
                """.formatted(name, extra));
    }
}
