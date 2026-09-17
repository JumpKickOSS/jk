// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A dependency's {@code <exclusions>} reach the manifest as {@code exclude = [...]}: a plain
 * exclusion is {@code group:artifact}, a {@code *} artifactId is {@code group:*}, a {@code *}
 * groupId is {@code *:artifact}, and a BOM import's exclusions are not written.
 */
class PomExclusionImportTest {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.demo</groupId>
              <artifactId>service</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.demo</groupId>
                    <artifactId>bom</artifactId>
                    <version>1.0.0</version>
                    <type>pom</type>
                    <scope>import</scope>
                    <exclusions>
                      <exclusion><groupId>org.demo</groupId><artifactId>ignored</artifactId></exclusion>
                    </exclusions>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>io.apicurio</groupId>
                  <artifactId>apicurio-registry-schema-util-json</artifactId>
                  <version>2.6.13.Final</version>
                  <exclusions>
                    <exclusion>
                      <groupId>io.apicurio</groupId>
                      <artifactId>apicurio-common-app-components-logging</artifactId>
                    </exclusion>
                    <exclusion>
                      <groupId>com.github.everit-org.json-schema</groupId>
                      <artifactId>*</artifactId>
                    </exclusion>
                  </exclusions>
                </dependency>
                <dependency>
                  <groupId>org.demo</groupId>
                  <artifactId>noisy</artifactId>
                  <version>1.0</version>
                  <exclusions>
                    <exclusion>
                      <groupId>*</groupId>
                      <artifactId>slf4j-simple</artifactId>
                    </exclusion>
                    <exclusion>
                      <groupId>org.demo</groupId>
                      <artifactId>chatter</artifactId>
                    </exclusion>
                  </exclusions>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void exclusions_land_on_the_entry_in_every_maven_spelling(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, POM);
        JkBuild build = result.jkBuild();
        List<String> messages = TestImporters.messages(result);

        Dependency schemaJson = only(build.dependencies().of(Scope.MAIN), "apicurio-registry-schema-util-json");
        assertThat(schemaJson.exclusions())
                .containsExactly(
                        "io.apicurio:apicurio-common-app-components-logging", "com.github.everit-org.json-schema:*");
        assertThat(messages).noneMatch(m -> m.contains("apicurio-common-app-components-logging"));

        Dependency noisy = only(build.dependencies().of(Scope.MAIN), "noisy");
        assertThat(noisy.exclusions()).containsExactly("*:slf4j-simple", "org.demo:chatter");
        assertThat(messages).noneMatch(m -> m.startsWith("`<exclusion>`"));

        assertThat(build.dependencies().of(Scope.PLATFORM))
                .allSatisfy(d -> assertThat(d.exclusions()).isEmpty());
    }

    @Test
    void the_rendered_manifest_reads_back_with_the_exclusions(@TempDir Path tempDir) throws Exception {
        String toml =
                JkBuildRenderer.render(TestImporters.importXml(tempDir, POM).jkBuild());
        assertThat(toml)
                .contains("apicurio-registry-schema-util-json = { group = \"io.apicurio\", version = \"2.6.13.Final\","
                        + " exclude = [\"io.apicurio:apicurio-common-app-components-logging\","
                        + " \"com.github.everit-org.json-schema:*\"] }");
        Dependency reparsed = only(JkBuildParser.parse(toml).dependencies().of(Scope.MAIN), "noisy");
        assertThat(reparsed.exclusions()).containsExactly("*:slf4j-simple", "org.demo:chatter");
    }

    private static Dependency only(List<Dependency> deps, String library) {
        return deps.stream()
                .filter(d -> d.library().equals(library))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no dependency `" + library + "` in " + deps));
    }
}
