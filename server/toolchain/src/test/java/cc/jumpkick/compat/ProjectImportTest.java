// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.gradle.GradleBuildImport;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.mvn.TestImporters;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A reactor import writes every manifest or none: a member's existing {@code jk.toml} refuses the root's write too. */
class ProjectImportTest {

    @Test
    void a_member_manifest_that_exists_refuses_the_whole_import_before_the_root_is_written(@TempDir Path root)
            throws Exception {
        writeReactor(root);
        Files.writeString(root.resolve("core/jk.toml"), "name = \"prior\"\n");
        Files.writeString(root.resolve("app/jk.toml"), "name = \"prior\"\n");

        ProjectImport.Outcome outcome = ProjectImport.run(
                TestImporters.offline(root.resolve("importer")),
                GradleBuildImport.scannerOnly(),
                root.resolve("pom.xml"),
                root.resolve("jk.toml"),
                root,
                null,
                false,
                false,
                null,
                note -> {},
                ProjectImport.PinRaise.NONE);

        assertThat(outcome.exit()).isEqualTo(Exit.CANT_CREATE);
        assertThat(outcome.wrote()).isEmpty();
        assertThat(outcome.error())
                .contains("refusing to overwrite 2 existing manifests: core/jk.toml, app/jk.toml")
                .contains("nothing was written")
                .contains("--overwrite");
        assertThat(root.resolve("jk.toml"))
                .as("the root manifest is not written either")
                .doesNotExist();
        assertThat(Files.readString(root.resolve("core/jk.toml"))).isEqualTo("name = \"prior\"\n");
    }

    /** {@code -t other.toml}: the pin check still runs, on a {@code jk.toml}, and its raises land in the file. */
    @Test
    void a_manifest_written_under_another_name_is_pin_checked_too(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>solo</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Path out = root.resolve("target/reimport.toml");

        ProjectImport.Outcome outcome = ProjectImport.run(
                TestImporters.offline(root.resolve("importer")),
                GradleBuildImport.scannerOnly(),
                root.resolve("pom.xml"),
                out,
                root,
                null,
                false,
                false,
                root.resolve("report.md"),
                note -> {},
                lockDir -> {
                    Path manifest = lockDir.resolve("jk.toml");
                    Files.writeString(manifest, Files.readString(manifest) + "# raised\n");
                    return List.of("raised one pin");
                });

        assertThat(outcome.exit()).isZero();
        assertThat(Files.readString(out)).contains("name     = \"solo\"").endsWith("# raised\n");
        assertThat(Files.readString(root.resolve("report.md")))
                .contains("raised one pin")
                .doesNotContain("not checked");
        assertThat(root.resolve("target/jk.toml")).doesNotExist();
    }

    @Test
    void force_writes_the_root_and_every_member(@TempDir Path root) throws Exception {
        writeReactor(root);
        Files.writeString(root.resolve("core/jk.toml"), "name = \"prior\"\n");

        ProjectImport.Outcome outcome = ProjectImport.run(
                TestImporters.offline(root.resolve("importer")),
                GradleBuildImport.scannerOnly(),
                root.resolve("pom.xml"),
                root.resolve("jk.toml"),
                root,
                null,
                true,
                false,
                root.resolve("report.md"),
                note -> {},
                ProjectImport.PinRaise.NONE);

        assertThat(outcome.exit()).isZero();
        assertThat(outcome.wrote())
                .containsExactly(
                        root.resolve("jk.toml"),
                        root.resolve("core/jk.toml"),
                        root.resolve("app/jk.toml"),
                        root.resolve("report.md"));
        assertThat(Files.readString(root.resolve("core/jk.toml"))).contains("name     = \"core\"");
        assertThat(Files.readString(root.resolve("jk.toml"))).contains("[workspace]");
    }

    /**
     * A handle is shortened only when the catalog the parse will read maps it to the same
     * coordinate: here the project's {@code jk-libs.toml} maps a bundled handle elsewhere, as a stale
     * downloaded registry can.
     */
    @Test
    void a_handle_another_catalog_layer_maps_elsewhere_keeps_its_coordinate(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk-libs.toml"), """
                [libraries]
                testcontainers-mysql = "org.testcontainers:mysql"
                """);
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>solo</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.testcontainers</groupId>
                      <artifactId>testcontainers-mysql</artifactId>
                      <version>2.0.2</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        ProjectImport.Outcome outcome = ProjectImport.run(
                TestImporters.offline(root.resolve("importer")),
                GradleBuildImport.scannerOnly(),
                root.resolve("pom.xml"),
                root.resolve("jk.toml"),
                root,
                null,
                false,
                false,
                root.resolve("report.md"),
                note -> {},
                ProjectImport.PinRaise.NONE);

        assertThat(outcome.exit()).isZero();
        assertThat(Files.readString(root.resolve("jk.toml")))
                .contains("testcontainers-mysql = \"org.testcontainers:testcontainers-mysql:2.0.2\"");
    }

    private static void writeReactor(Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                    <module>app</module>
                  </modules>
                </project>
                """);
        for (String module : new String[] {"core", "app"}) {
            Path dir = Files.createDirectories(root.resolve(module));
            Files.writeString(dir.resolve("pom.xml"), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0</version>
                      </parent>
                      <artifactId>%s</artifactId>
                    </project>
                    """.formatted(module));
        }
    }
}
