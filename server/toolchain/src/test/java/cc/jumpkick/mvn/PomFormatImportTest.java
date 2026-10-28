// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A formatter the POM runs is {@code [format] enforce} in its style; a POM without one gets none. */
class PomFormatImportTest {

    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.ex</groupId>
              <artifactId>lib</artifactId>
              <version>1.0.0</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins>
            """;
    private static final String TAIL = """
              </plugins></build>
            </project>
            """;

    @Test
    void spotless_with_palantir_is_enforced_palantir(@TempDir Path tempDir) throws Exception {
        JkBuild build = TestImporters.importXml(tempDir, HEAD + """
                        <plugin>
                          <groupId>com.diffplug.spotless</groupId>
                          <artifactId>spotless-maven-plugin</artifactId>
                          <version>2.44.0</version>
                          <configuration><java><palantirJavaFormat/></java></configuration>
                        </plugin>
                        """ + TAIL).jkBuild();
        assertThat(build.format().enforce()).isTrue();
        assertThat(build.format().java()).isEqualTo("palantir");
        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("\n[format]\njava = \"palantir\"\nenforce = true\n");
        assertThat(JkBuildParser.parse(rendered).format()).isEqualTo(build.format());
    }

    @Test
    void spotless_with_google_in_aosp_and_ktfmt_maps_both_languages(@TempDir Path tempDir) throws Exception {
        JkBuild build = TestImporters.importXml(tempDir, HEAD + """
                        <plugin>
                          <groupId>com.diffplug.spotless</groupId>
                          <artifactId>spotless-maven-plugin</artifactId>
                          <version>2.44.0</version>
                          <configuration>
                            <java><googleJavaFormat><style>AOSP</style></googleJavaFormat></java>
                            <kotlin><ktfmt><style>KOTLINLANG</style></ktfmt></kotlin>
                          </configuration>
                        </plugin>
                        """ + TAIL).jkBuild();
        assertThat(build.format().java()).isEqualTo("aosp");
        assertThat(build.format().kotlin()).isEqualTo("kotlinlang");
        assertThat(build.format().enforce()).isTrue();
    }

    @Test
    void fmt_maven_plugin_is_google_java_format(@TempDir Path tempDir) throws Exception {
        JkBuild build = TestImporters.importXml(tempDir, HEAD + """
                        <plugin>
                          <groupId>com.spotify.fmt</groupId>
                          <artifactId>fmt-maven-plugin</artifactId>
                          <version>2.25</version>
                        </plugin>
                        """ + TAIL).jkBuild();
        assertThat(build.format().java()).isEqualTo("google");
        assertThat(build.format().enforce()).isTrue();
    }

    @Test
    void a_spotless_step_jk_does_not_run_leaves_enforce_off_and_says_so(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, HEAD + """
                <plugin>
                  <groupId>com.diffplug.spotless</groupId>
                  <artifactId>spotless-maven-plugin</artifactId>
                  <version>2.44.0</version>
                  <configuration><java><eclipse/></java></configuration>
                </plugin>
                """ + TAIL);
        assertThat(result.jkBuild().format().enforce()).isFalse();
        assertThat(TestImporters.messages(result)).anyMatch(m -> m.contains("`[format] enforce` is left off"));
    }

    @Test
    void a_pom_with_no_formatter_gets_no_format_table(@TempDir Path tempDir) throws Exception {
        JkBuild build = TestImporters.importXml(tempDir, HEAD + TAIL).jkBuild();
        assertThat(build.format()).isEqualTo(JkBuild.FormatConfig.EMPTY);
        assertThat(JkBuildRenderer.render(build)).doesNotContain("[format]");
    }
}
