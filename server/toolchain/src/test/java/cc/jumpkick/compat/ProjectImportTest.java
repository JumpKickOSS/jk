// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.command.Exit;
import cc.jumpkick.mvn.TestImporters;
import java.nio.file.Files;
import java.nio.file.Path;
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
                root.resolve("pom.xml"),
                root.resolve("jk.toml"),
                root,
                null,
                false,
                null);

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

    @Test
    void force_writes_the_root_and_every_member(@TempDir Path root) throws Exception {
        writeReactor(root);
        Files.writeString(root.resolve("core/jk.toml"), "name = \"prior\"\n");

        ProjectImport.Outcome outcome = ProjectImport.run(
                TestImporters.offline(root.resolve("importer")),
                root.resolve("pom.xml"),
                root.resolve("jk.toml"),
                root,
                null,
                true,
                root.resolve("report.md"));

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
