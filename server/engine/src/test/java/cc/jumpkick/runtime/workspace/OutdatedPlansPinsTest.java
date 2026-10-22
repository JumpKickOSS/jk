// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.wire.protocol.OutdatedReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk outdated} reports every pin {@code jk update} moves, not only dependencies: the
 * language keys, written tool-table keys and {@code [guards] extends} rule packs; and a dependency
 * whose coordinate moved, by relocation POM or by a known rename.
 */
class OutdatedPlansPinsTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void isolateStore() {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        RepoGroup.clearProcessVersionsCache();
    }

    @AfterEach
    void releaseStoreOverride() {
        System.clearProperty("jk.env.JK_STORE_DIR");
    }

    @Test
    void language_tool_and_rule_pack_pins_get_a_row_each(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name = "tools"
                version = "1.0.0"
                java = 25
                kotlin = "2.4.20"
                scala = "^3.8"

                [dokka]
                version = "2.0.0"

                [protobuf]
                version = "4.30.0"

                [generate.api]
                tool   = "org.openapitools:openapi-generator-cli:7.10.0"
                inputs = ["api/openapi.yaml"]

                [lint]
                checkstyle = "config/checkstyle.xml"
                checkstyle-version = "13.0.0"
                """);
        Files.writeString(project.resolve("jk-guards.toml"), """
                [guards]
                extends = ["cc.jumpkick.guards:spring:0.13.2"]
                """);
        MavenStub upstream = new MavenStub(http);
        published(upstream, "org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.20", "2.4.21");
        published(upstream, "org.scala-lang", "scala3-compiler_3", "3.8.0", "3.10.0");
        published(upstream, "org.jetbrains.dokka", "dokka-cli", "2.0.0", "2.1.0");
        published(upstream, "com.google.protobuf", "protoc", "4.30.0", "4.33.1");
        published(upstream, "org.openapitools", "openapi-generator-cli", "7.10.0", "7.11.0");
        published(upstream, "com.puppycrawl.tools", "checkstyle", "13.0.0", "14.1.0");
        published(upstream, "net.sourceforge.pmd", "pmd-cli", "7.27.0", "7.30.0");
        published(upstream, "cc.jumpkick.guards", "spring", "0.13.2", "0.15.3");

        OutdatedReport report = OutdatedPlans.compute(
                project, project.resolve("cache"), http.base(), false, OutdatedPlans.Progress.NONE);

        assertThat(report.error()).isNull();
        // scala floats (^3.8): no pin, the relock moves it. pmd-version is the schema's default,
        // which moves with jk rather than with an update.
        assertThat(report.rows())
                .extracting(r -> r.coordinate(), r -> r.scope(), r -> r.current(), r -> r.latest())
                .containsExactlyInAnyOrder(
                        tuple("org.jetbrains.kotlin:kotlin-compiler-embeddable", "kotlin", "2.4.20", "2.4.21"),
                        tuple("org.jetbrains.dokka:dokka-cli", "dokka.version", "2.0.0", "2.1.0"),
                        tuple("com.google.protobuf:protoc", "protobuf.version", "4.30.0", "4.33.1"),
                        tuple("org.openapitools:openapi-generator-cli", "generate.api.tool", "7.10.0", "7.11.0"),
                        tuple("com.puppycrawl.tools:checkstyle", "lint.checkstyle-version", "13.0.0", "14.1.0"),
                        tuple("cc.jumpkick.guards:spring", "guards.extends", "0.13.2", "0.15.3"));
        assertThat(report.movable()).hasSize(6);
    }

    @Test
    void an_exact_scala_pin_gets_a_row(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"
                java = 25
                scala = "3.8.0"
                """);
        published(new MavenStub(http), "org.scala-lang", "scala3-compiler_3", "3.8.0", "3.10.0");

        OutdatedReport report = OutdatedPlans.compute(
                project, project.resolve("cache"), http.base(), false, OutdatedPlans.Progress.NONE);

        assertThat(report.rows())
                .extracting(r -> r.coordinate(), r -> r.scope(), r -> r.compatible(), r -> r.latest())
                .containsExactly(tuple("org.scala-lang:scala3-compiler_3", "scala", "3.8.0", "3.10.0"));
    }

    @Test
    void a_relocation_pom_names_the_new_coordinate_and_its_latest(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"
                java = 25

                [dependencies]
                old = "com.acme:old:1.0"
                """);
        MavenStub upstream = new MavenStub(http);
        upstream.pom("com.acme", "old", "1.0", MavenStub.emptyPom("com.acme", "old", "1.0"));
        upstream.pom("com.acme", "old", "1.1", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>old</artifactId>
                  <version>1.1</version>
                  <distributionManagement>
                    <relocation><artifactId>renamed</artifactId><version>2.0</version></relocation>
                  </distributionManagement>
                </project>
                """);
        upstream.metadata("com.acme", "old", "1.0", "1.1");
        published(upstream, "com.acme", "renamed", "2.0", "2.3");

        OutdatedReport report = OutdatedPlans.compute(
                project, project.resolve("cache"), http.base(), false, OutdatedPlans.Progress.NONE);

        assertThat(report.rows())
                .extracting(r -> r.coordinate(), r -> r.compatible(), r -> r.latest(), r -> r.relocated())
                .containsExactly(tuple("com.acme:old", "1.0", "2.3", "com.acme:renamed"));
        assertThat(report.rows().getFirst().canMove()).isTrue();
    }

    /** Testcontainers 2.0 renamed its modules without a relocation POM; the bundled rename names the new one. */
    @Test
    void a_known_rename_is_reported_when_the_new_coordinate_publishes_past_it(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"
                java = 25

                [test-dependencies]
                postgresql = { group = "org.testcontainers", version = "1.21.4" }
                testcontainers = "org.testcontainers:testcontainers:1.21.4"
                """);
        MavenStub upstream = new MavenStub(http);
        published(upstream, "org.testcontainers", "postgresql", "1.21.3", "1.21.4");
        published(upstream, "org.testcontainers", "testcontainers-postgresql", "2.0.0", "2.0.5");
        published(upstream, "org.testcontainers", "testcontainers", "1.21.4", "2.0.5");

        OutdatedReport report = OutdatedPlans.compute(
                project, project.resolve("cache"), http.base(), false, OutdatedPlans.Progress.NONE);

        assertThat(report.rows())
                .extracting(r -> r.coordinate(), r -> r.latest(), r -> r.relocated())
                .containsExactlyInAnyOrder(
                        tuple("org.testcontainers:postgresql", "2.0.5", "org.testcontainers:testcontainers-postgresql"),
                        tuple("org.testcontainers:testcontainers", "2.0.5", ""));
    }

    @Test
    void a_rename_needs_the_old_line_to_end_below_it_and_the_new_coordinate_to_exist() {
        List<MovedCoordinates.Rename> renames =
                List.of(new MovedCoordinates.Rename("org.acme:*", "org.acme:acme-*", "2.0.0"));
        MovedCoordinates.VersionLister published = m -> m.equals("org.acme:acme-db") ? List.of("2.0.1") : List.of();

        assertThat(MovedCoordinates.renamed("org.acme:db", "1.9.0", renames, published))
                .isEqualTo("org.acme:acme-db");
        assertThat(MovedCoordinates.renamed("org.acme:db", "2.0.0", renames, published))
                .isNull();
        assertThat(MovedCoordinates.renamed("org.acme:queue", "1.9.0", renames, published))
                .isNull();
        assertThat(MovedCoordinates.renamed("org.other:db", "1.9.0", renames, published))
                .isNull();
    }

    private static void published(MavenStub upstream, String group, String artifact, String... versions) {
        for (String v : versions) {
            upstream.pom(group, artifact, v, MavenStub.emptyPom(group, artifact, v));
        }
        upstream.metadata(group, artifact, versions);
    }
}
