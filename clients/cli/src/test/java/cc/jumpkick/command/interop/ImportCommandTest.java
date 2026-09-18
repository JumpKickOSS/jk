// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class ImportCommandTest {

    @Test
    void writes_build_jk_and_report(@TempDir Path tempDir) throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.fasterxml.jackson.core</groupId>
                      <artifactId>jackson-databind</artifactId>
                      <version>2.18.2</version>
                    </dependency>
                  </dependencies>
                </project>
                """, StandardCharsets.UTF_8);

        int exit =
                run("import", "--report", tempDir.resolve("jk-import-report.md").toString(), pom.toString());
        assertThat(exit).isEqualTo(0);

        String jkBuild = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(jkBuild).contains("name     = \"widget\"");
        assertThat(jkBuild).contains("[dependencies]");
        assertThat(jkBuild)
                .as("a plain coordinate is spelled as jk add writes it")
                .contains("jackson-databind = \"com.fasterxml.jackson.core:jackson-databind:2.18.2\"");

        String report = Files.readString(tempDir.resolve("jk-import-report.md"));
        assertThat(report).contains("# jk import report");
        assertThat(report).contains("Import was lossless");
    }

    @Test
    void relative_report_and_out_paths_resolve_against_the_working_directory(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0.0</version>
                </project>
                """, StandardCharsets.UTF_8);

        int[] exit = new int[1];
        String out = Capture.stdout(() -> exit[0] =
                run("import", "-C", tempDir.toString(), "--report", "reports/import.md", "--out", "out/jk.toml"));

        assertThat(exit[0]).as(out).isEqualTo(0);
        assertThat(tempDir.resolve("reports/import.md"))
                .as("a relative --report lands under the directory the command runs in, not the engine's")
                .isRegularFile();
        assertThat(tempDir.resolve("out/jk.toml")).isRegularFile();
        assertThat(tempDir.resolve("jk.toml")).doesNotExist();
    }

    @Test
    void refuses_to_overwrite_without_force(@TempDir Path tempDir) throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                </project>
                """, StandardCharsets.UTF_8);

        Path existing = tempDir.resolve("jk.toml");
        Files.writeString(existing, "group = \"prior\"\n");

        int exit = run("import", pom.toString());
        assertThat(exit).isEqualTo(73); // EX_CANTCREAT
        assertThat(Files.readString(existing)).contains("\"prior\"");
    }

    /** A reactor whose member already has a jk.toml: the root is not written either, and the message names the member. */
    @Test
    void a_reactor_member_manifest_refuses_the_root_write_too(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                  </modules>
                </project>
                """, StandardCharsets.UTF_8);
        Path core = Files.createDirectories(tempDir.resolve("core"));
        Files.writeString(core.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>core</artifactId>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(core.resolve("jk.toml"), "name = \"prior\"\n");

        int exit = run("import", tempDir.resolve("pom.xml").toString());
        assertThat(exit).isEqualTo(73); // EX_CANTCREAT
        assertThat(tempDir.resolve("jk.toml")).doesNotExist();
        assertThat(Files.readString(core.resolve("jk.toml"))).isEqualTo("name = \"prior\"\n");
    }

    @Test
    void force_overwrites_existing_build_jk(@TempDir Path tempDir) throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("jk.toml"), "group = \"prior\"\n");

        int exit = run(
                "import",
                "--overwrite",
                "--report",
                tempDir.resolve("report.md").toString(),
                pom.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tempDir.resolve("jk.toml")))
                .contains("name     = \"widget\"")
                .doesNotContain("\"prior\"");
    }

    @Test
    void activate_profiles_lists_the_named_profiles_modules(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>tutorials</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <profiles>
                    <profile>
                      <id>default</id>
                      <modules><module>core</module></modules>
                    </profile>
                    <profile>
                      <id>default-heavy</id>
                      <modules><module>heavy</module></modules>
                    </profile>
                  </profiles>
                </project>
                """, StandardCharsets.UTF_8);
        for (String module : List.of("core", "heavy")) {
            Path dir = Files.createDirectories(tempDir.resolve(module));
            Files.writeString(dir.resolve("pom.xml"), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                        <groupId>com.example</groupId>
                        <artifactId>tutorials</artifactId>
                        <version>1.0</version>
                      </parent>
                      <artifactId>%s</artifactId>
                    </project>
                    """.formatted(module), StandardCharsets.UTF_8);
        }

        int exit = run(
                "import",
                "-P",
                "default,default-heavy",
                "--report",
                tempDir.resolve("report.md").toString(),
                tempDir.resolve("pom.xml").toString());

        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tempDir.resolve("jk.toml"))).contains("modules = [\"core\", \"heavy\"]");
        assertThat(tempDir.resolve("core/jk.toml")).isRegularFile();
        assertThat(tempDir.resolve("heavy/jk.toml")).isRegularFile();
    }

    @Test
    void gradle_kts_source_imports_and_writes_build_jk(@TempDir Path tempDir) throws Exception {
        Path gradle = tempDir.resolve("build.gradle.kts");
        Files.writeString(gradle, """
                plugins { id("java") }

                group = "com.example"
                version = "1.0.0"

                java { sourceCompatibility = JavaVersion.VERSION_25 }

                dependencies {
                    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
                }
                """, StandardCharsets.UTF_8);

        int exit =
                run("import", "--report", tempDir.resolve("jk-import-report.md").toString(), gradle.toString());
        assertThat(exit).isEqualTo(0);

        String jkBuild = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(jkBuild).contains("group    = \"com.example\"");
        assertThat(jkBuild).contains("jdk      = \"25\"");
        assertThat(jkBuild).contains("[dependencies]");
        // The catalog short name (jackson2-databind) with the bare version, as jk add writes it.
        assertThat(jkBuild).contains("jackson2-databind = \"2.18.2\"");

        assertThat(Files.readString(tempDir.resolve("jk-import-report.md"))).contains("# jk import report");
    }

    @Test
    void unrecognised_source_returns_usage_error(@TempDir Path tempDir) throws Exception {
        Path foo = tempDir.resolve("foo.txt");
        Files.writeString(foo, "x");
        int exit = run("import", foo.toString());
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void missing_source_returns_no_input(@TempDir Path tempDir) {
        int exit = run("import", tempDir.resolve("missing.xml").toString());
        assertThat(exit).isEqualTo(66); // EX_NOINPUT
    }

    @Test
    void multi_module_pom_writes_root_and_child_build_jks(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget-parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                    <module>app</module>
                  </modules>
                </project>
                """, StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("core"));
        Files.writeString(tempDir.resolve("core/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>widget-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>widget-core</artifactId>
                </project>
                """, StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(tempDir.resolve("app/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>widget-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>widget-app</artifactId>
                </project>
                """, StandardCharsets.UTF_8);

        int exit = run(
                "import",
                "--report",
                tempDir.resolve("report.md").toString(),
                tempDir.resolve("pom.xml").toString());
        assertThat(exit).isEqualTo(0);

        String root = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(root).contains("name     = \"widget-parent\"");
        assertThat(root).contains("[workspace]");
        assertThat(root).contains("modules = [\"core\", \"app\"]");

        assertThat(Files.readString(tempDir.resolve("core/jk.toml"))).contains("name     = \"widget-core\"");
        assertThat(Files.readString(tempDir.resolve("app/jk.toml"))).contains("name     = \"widget-app\"");
    }

    @Test
    void default_report_path_names_by_coordinate_and_increments_on_collision(@TempDir Path tmp) throws Exception {
        Path first = ImportCommand.defaultReportPath(tmp, "com.example-widget-1.0.0", "pom.xml");
        assertThat(first.getFileName().toString()).isEqualTo("com.example-widget-1.0.0-1-pom.xml-import.md");
        assertThat(first.getParent()).isEqualTo(tmp);

        // Once it exists, the next call bumps n.
        Files.writeString(first, "x");
        Path second = ImportCommand.defaultReportPath(tmp, "com.example-widget-1.0.0", "pom.xml");
        assertThat(second.getFileName().toString()).isEqualTo("com.example-widget-1.0.0-2-pom.xml-import.md");

        // Source filename is reflected (e.g. Gradle).
        Path gradle = ImportCommand.defaultReportPath(tmp, "g-a-2.0", "build.gradle.kts");
        assertThat(gradle.getFileName().toString()).isEqualTo("g-a-2.0-1-build.gradle.kts-import.md");
    }
}
