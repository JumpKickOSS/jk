// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.mvn.PomImporter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Maven import → render → parse round trips. Whatever the POM throws at the importer, the emitted
 * jk.toml must be one jk itself accepts, and package identities (g:a:type:classifier) must survive.
 */
class MavenImportRoundTripTest {

    @Test
    void jar_plus_test_jar_of_one_ga_round_trips_as_two_packages() {
        JkBuild imported = importPom("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.logging.log4j</groupId>
                      <artifactId>log4j-core</artifactId>
                      <version>2.24.0</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.apache.logging.log4j</groupId>
                      <artifactId>log4j-core</artifactId>
                      <version>2.24.0</version>
                      <type>test-jar</type>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);

        String rendered = JkBuildRenderer.render(imported);
        JkBuild reparsed = JkBuildParser.parse(rendered);

        // Both packages of the GA make it to the lock input — this is what the
        // resolver roots on, so losing one here silently drops a test classpath entry.
        assertThat(reparsed.dependencies().of(Scope.TEST))
                .extracting(Dependency::packageKey)
                .containsExactlyInAnyOrder(
                        "org.apache.logging.log4j:log4j-core:jar:",
                        "org.apache.logging.log4j:log4j-core:test-jar:tests");
    }

    private static JkBuild importPom(String xml) {
        return PomImporter.importFromBytes(xml.getBytes(StandardCharsets.UTF_8)).jkBuild();
    }
}
