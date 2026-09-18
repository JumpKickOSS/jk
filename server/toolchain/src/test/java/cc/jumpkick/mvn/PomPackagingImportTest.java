// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.ImageTable;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the packaging plugins land: Shade and {@code jar-with-dependencies} in {@code [application]
 * assembly}, Boot in {@code [spring-boot]}, Quarkus in {@code [quarkus]}, native-image in {@code
 * [native]}, Jib and the Docker plugins in {@code [image]}, and a war in a Tier-3 row.
 */
class PomPackagingImportTest {

    @Test
    void shade_is_the_fat_jar_and_its_rewrites_are_rows(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importFixture(tempDir, "plugins", "shade-pom.xml");
        JkBuild build = result.jkBuild();
        List<String> messages = TestImporters.messages(result);

        assertThat(build.applicationOpt()).isPresent();
        assertThat(build.applicationOpt().get().main()).isEqualTo("com.ex.cli.Main");
        assertThat(build.applicationOpt().get().assembly()).isTrue();
        assertThat(build.applicationOpt().get().relocate())
                .as("a whole-package relocation is the assembly's relocate rule, in declaration order")
                .containsExactly(
                        Map.entry("com.google.common", "com.ex.shaded.guava"),
                        Map.entry("com.fasterxml.jackson", "com.ex.shaded.jackson"));
        assertThat(build.manifest()).containsEntry("Multi-Release", "true");
        assertThat(messages)
                .as("a relocation narrowed by <excludes> is the one row")
                .anyMatch(m -> m.startsWith("`maven-shade-plugin` `<relocations>` org.apache.commons.io →"
                        + " com.ex.shaded.commons.io — `[application] relocate` moves whole packages"));
        assertThat(messages).anyMatch(m -> m.startsWith("`maven-shade-plugin` `<filters>` on *:* —"));
        assertThat(messages)
                .as("the manifest and services transformers are jk's default merge; only the appending one is a row")
                .anyMatch(m -> m.startsWith("`maven-shade-plugin` transformers AppendingTransformer —"));
        assertThat(messages).noneMatch(m -> m.startsWith("`<plugin>"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered)
                .contains("[application]\nmain       = \"com.ex.cli.Main\"\nassembly = true\n"
                        + "relocate = { \"com.google.common\" = \"com.ex.shaded.guava\","
                        + " \"com.fasterxml.jackson\" = \"com.ex.shaded.jackson\" }\n");
        assertThat(JkBuildParser.parse(rendered).applicationOpt().get().assembly())
                .isTrue();
        assertThat(JkBuildParser.parse(rendered).applicationOpt().get().relocate())
                .containsEntry("com.google.common", "com.ex.shaded.guava");
    }

    @Test
    void jar_with_dependencies_is_the_fat_jar_and_other_descriptors_are_a_row(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importFixture(tempDir, "plugins", "assembly-pom.xml");
        JkBuild.Application application = result.jkBuild().applicationOpt().orElseThrow();
        assertThat(application.main()).isEqualTo("com.ex.tool.Main");
        assertThat(application.assembly()).isTrue();
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith("`maven-assembly-plugin` descriptors src —"))
                .noneMatch(m -> m.startsWith("`<plugin>"));
    }

    @Test
    void a_fat_jar_without_a_main_class_is_a_row_not_a_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0.0</version>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-shade-plugin</artifactId>
                    <version>3.6.0</version>
                  </plugin></plugins></build>
                </project>
                """);
        assertThat(result.jkBuild().applicationOpt()).isEmpty();
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith("a fat jar was requested but no `<mainClass>` was found"));
        assertThat(JkBuildRenderer.render(result.jkBuild())).doesNotContain("[application]");
    }

    @Test
    void spring_boot_plugin_is_the_table_at_the_managed_boot_version(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importFixture(tempDir, "plugins", "spring-boot-pom.xml");
        JkBuild build = result.jkBuild();
        List<String> messages = TestImporters.messages(result);

        assertThat(build.pluginConfig("spring-boot")).isPresent();
        assertThat(build.pluginConfig("spring-boot").get().string("version")).isEqualTo("3.5.5");
        assertThat(build.applicationOpt().orElseThrow().main()).isEqualTo("com.ex.api.Application");
        assertThat(build.applicationOpt().orElseThrow().assembly())
                .as("the Boot jar is plugin-owned, not the assembly fat jar")
                .isFalse();
        assertThat(messages)
                .anyMatch(m -> m.startsWith("`spring-boot-maven-plugin` `<excludes>` org.projectlombok:lombok —"))
                .noneMatch(m -> m.startsWith("`<plugin>"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("[spring-boot]\nversion = \"3.5.5\"\n");
        assertThat(JkBuildParser.parse(rendered)
                        .pluginConfig("spring-boot")
                        .orElseThrow()
                        .string("version"))
                .isEqualTo("3.5.5");
    }

    @Test
    void quarkus_plugin_is_the_table_at_the_platform_version(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importFixture(tempDir, "plugins", "quarkus-pom.xml");
        JkBuild build = result.jkBuild();

        assertThat(build.pluginConfig("quarkus")).isPresent();
        assertThat(build.pluginConfig("quarkus").get().string("version")).isEqualTo("3.39.2");
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.startsWith("`<plugin>"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("[quarkus]\nversion = \"3.39.2\"\n");
        assertThat(JkBuildParser.parse(rendered)
                        .pluginConfig("quarkus")
                        .orElseThrow()
                        .string("version"))
                .isEqualTo("3.39.2");
    }

    @Test
    void a_quarkus_plugin_without_a_version_anywhere_is_a_row(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <build><plugins><plugin>
                    <groupId>io.quarkus.platform</groupId>
                    <artifactId>quarkus-maven-plugin</artifactId>
                  </plugin></plugins></build>
                </project>
                """);
        assertThat(result.jkBuild().pluginConfig("quarkus")).isEmpty();
        assertThat(TestImporters.messages(result))
                .anyMatch(
                        m -> m.startsWith("`quarkus-maven-plugin` is declared without a resolvable platform version"));
    }

    /**
     * A library that lists the Boot plugin bare — no {@code repackage} execution, no {@code
     * <mainClass>} — runs nothing under Maven (the starter parent is what binds the goal), so no
     * {@code [spring-boot]} table asks jk's Boot packager for a main it does not have.
     */
    @Test
    void a_bare_boot_plugin_on_a_module_with_no_main_and_no_repackage_writes_no_table(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>maintainer-client</artifactId>
                  <version>1.0.0</version>
                  <properties><spring-boot.version>3.5.5</spring-boot.version></properties>
                  <build><plugins><plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <configuration><attach>false</attach></configuration>
                  </plugin></plugins></build>
                </project>
                """);

        assertThat(result.jkBuild().pluginConfig("spring-boot")).isEmpty();
        assertThat(result.jkBuild().applicationOpt()).isEmpty();
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith(
                        "`spring-boot-maven-plugin` binds no `repackage` execution and names no" + " `<mainClass>`"));
    }

    /** A {@code repackage} execution is the Boot jar even without a declared main: the packager scans for it. */
    @Test
    void a_repackage_execution_without_a_declared_main_is_the_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <properties><spring-boot.version>3.5.5</spring-boot.version></properties>
                  <build><plugins><plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <executions><execution><goals><goal>repackage</goal></goals></execution></executions>
                  </plugin></plugins></build>
                </project>
                """);

        assertThat(result.jkBuild().pluginConfig("spring-boot")).isPresent();
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.contains("binds no `repackage` execution"));
    }

    /** {@code <skip>true</skip>} is Maven's way of saying this module is not the Boot jar. */
    @Test
    void a_skipped_boot_plugin_writes_no_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0.0</version>
                  <properties><spring-boot.version>3.5.5</spring-boot.version></properties>
                  <build><plugins><plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <configuration><skip>true</skip><mainClass>com.ex.Lib</mainClass></configuration>
                    <executions><execution><goals><goal>repackage</goal></goals></execution></executions>
                  </plugin></plugins></build>
                </project>
                """);

        assertThat(result.jkBuild().pluginConfig("spring-boot")).isEmpty();
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith("`spring-boot-maven-plugin` is skipped (`<skip>true</skip>`)"));
    }

    @Test
    void a_boot_plugin_without_a_version_anywhere_is_a_row(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>api</artifactId>
                  <version>1.0.0</version>
                  <build><plugins><plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <configuration><mainClass>com.ex.Api</mainClass></configuration>
                  </plugin></plugins></build>
                </project>
                """);
        assertThat(result.jkBuild().pluginConfig("spring-boot")).isEmpty();
        assertThat(TestImporters.messages(result))
                .anyMatch(
                        m -> m.startsWith("`spring-boot-maven-plugin` is declared without a resolvable Boot version"));
    }

    @Test
    void native_plugin_is_the_native_table_and_jib_is_the_image_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importFixture(tempDir, "plugins", "native-image-pom.xml");
        JkBuild build = result.jkBuild();
        List<String> messages = TestImporters.messages(result);

        JkBuild.NativeConfig nativeConfig = build.nativeConfigOpt().orElseThrow();
        assertThat(build.applicationOpt().orElseThrow().main()).isEqualTo("com.ex.fastcli.Main");
        assertThat(nativeConfig.mainClass())
                .as("[application] main already names the entry point")
                .isNull();
        assertThat(nativeConfig.name()).isEqualTo("fastcli");
        assertThat(nativeConfig.args()).containsExactly("--no-fallback", "-H:+ReportExceptionStackTraces");
        assertThat(nativeConfig.enabled()).isEqualTo(JkBuild.NativeMode.SUPPORTED);
        ImageTable image = build.image();
        assertThat(image.base()).isEqualTo("eclipse-temurin:21-jre");
        assertThat(image.registry()).isEqualTo("ghcr.io");
        assertThat(image.name()).isEqualTo("ex/fastcli");
        assertThat(image.tag()).isEqualTo("1.0.0");
        assertThat(messages)
                .anyMatch(m ->
                        m.startsWith("`jib-maven-plugin` `<from>` and `<to>` are written as `[image]` base, registry,"
                                + " name and tag; jk builds the image itself (`jk image`)"))
                .noneMatch(m -> m.contains("paste"))
                .noneMatch(m -> m.startsWith("`<plugin>"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered)
                .contains(
                        "[native]\nname       = \"fastcli\"\nargs       = [\"--no-fallback\", \"-H:+ReportExceptionStackTraces\"]\n")
                .contains(
                        "\n[image]\nbase = \"eclipse-temurin:21-jre\"\nname = \"ex/fastcli\"\nregistry = \"ghcr.io\"\n"
                                + "tag = \"1.0.0\"\n");
        JkBuild reparsed = JkBuildParser.parse(rendered);
        assertThat(reparsed.image()).isEqualTo(image);
        assertThat(reparsed.nativeConfigOpt().orElseThrow().args()).isEqualTo(nativeConfig.args());
    }

    @Test
    void a_war_is_a_tier_three_row(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>web</artifactId>
                  <version>1.0.0</version>
                  <packaging>war</packaging>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-war-plugin</artifactId>
                    <version>3.4.0</version>
                  </plugin></plugins></build>
                </project>
                """);
        assertThat(result.report().hasErrors()).isTrue();
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith("packaging `war` (`maven-war-plugin`) is not supported"))
                .noneMatch(m -> m.startsWith("`<plugin>"));
    }
}
