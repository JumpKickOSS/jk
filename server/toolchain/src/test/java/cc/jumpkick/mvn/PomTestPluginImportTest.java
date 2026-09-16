// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where Surefire, Failsafe and JaCoCo land: groups in {@code [test]} tag filters, everything jk
 * has no key for in a row that names the setting and its landing place.
 */
class PomTestPluginImportTest {

    @Test
    void surefire_groups_become_tag_filters_and_the_rest_is_rows(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importFixture(tempDir, "plugins", "test-plugins-pom.xml");
        JkBuild build = result.jkBuild();
        List<String> messages = TestImporters.messages(result);

        assertThat(build.build().testIncludeTags()).containsExactly("fast", "smoke");
        assertThat(build.build().testExcludeTags()).containsExactly("slow");

        assertThat(messages)
                .as("the argLine minus the ${argLine} placeholder and the JaCoCo agent")
                .anyMatch(m -> m.startsWith("`maven-surefire-plugin` `<argLine>` -Xmx1g -Dfile.encoding=UTF-8 —"));
        assertThat(messages)
                .anyMatch(m -> m.startsWith("`maven-surefire-plugin` system properties"
                        + " -Dspring.profiles.active=test -Djava.awt.headless=true —"));
        assertThat(messages).anyMatch(m -> m.startsWith("`maven-surefire-plugin` `<excludes>` **/*Slow*.java —"));
        assertThat(messages)
                .as("failsafe's default patterns name the classes to move into the integration suite")
                .anyMatch(m -> m.startsWith("`maven-failsafe-plugin` runs **/IT*.java, **/*IT.java, **/*ITCase.java")
                        && m.contains("`src/integration/java`"));
        assertThat(messages).anyMatch(m -> m.startsWith("`maven-failsafe-plugin` `<argLine>` -Xmx2g —"));
        assertThat(messages)
                .anyMatch(m -> m.startsWith("`jacoco-maven-plugin` —") && m.contains("`jk test --coverage`"));
        assertThat(messages).noneMatch(m -> m.contains("skip tests"));
        assertThat(messages)
                .as("surefire, failsafe and jacoco each have their own rows, never the generic one")
                .noneMatch(m -> m.startsWith("`<plugin>"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("[test]\ninclude-tags = [\"fast\", \"smoke\"]\nexclude-tags = [\"slow\"]\n");
        JkBuild reparsed = JkBuildParser.parse(rendered);
        assertThat(reparsed.build().testIncludeTags()).containsExactly("fast", "smoke");
        assertThat(reparsed.build().testExcludeTags()).containsExactly("slow");
        assertThat(JkBuildParser.parseTestTags(writeManifest(tempDir, rendered)).excludeTags())
                .as("the engine's root-scoped reader sees the same filters")
                .containsExactly("slow");
    }

    @Test
    void a_tag_expression_and_a_skip_flag_are_rows(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <properties><skipTests>true</skipTests></properties>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.2</version>
                    <configuration><groups>fast &amp; !flaky</groups></configuration>
                  </plugin></plugins></build>
                </project>
                """);
        List<String> messages = TestImporters.messages(result);
        assertThat(result.jkBuild().build().testIncludeTags()).isEmpty();
        assertThat(messages)
                .anyMatch(m ->
                        m.startsWith("`maven-surefire-plugin` `<groups>fast & !flaky</groups>` is a tag expression"));
        assertThat(messages).anyMatch(m -> m.contains("skip tests") && m.contains("`jk build --skip-tests`"));
        assertThat(JkBuildRenderer.render(result.jkBuild())).doesNotContain("[test]");
    }

    @Test
    void the_argline_property_counts_when_surefire_declares_none(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <properties><argLine>--enable-preview -XX:+UseZGC</argLine></properties>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.2</version>
                  </plugin></plugins></build>
                </project>
                """);
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith("`maven-surefire-plugin` `<argLine>` --enable-preview -XX:+UseZGC —"));
    }

    private static Path writeManifest(Path tempDir, String rendered) throws Exception {
        Path manifest = tempDir.resolve("rendered").resolve("jk.toml");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, rendered);
        return manifest;
    }
}
