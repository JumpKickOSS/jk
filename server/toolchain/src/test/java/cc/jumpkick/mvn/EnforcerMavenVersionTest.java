// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnforcerMavenVersionTest {

    @Test
    void a_bare_version_a_range_and_a_property_reference_all_read_as_the_floor(@TempDir Path dir) throws Exception {
        assertThat(EnforcerMavenVersion.minimum(pom(dir, "3.9.11", ""))).isEqualTo("3.9.11");
        assertThat(EnforcerMavenVersion.minimum(pom(dir, "[3.9.11,)", ""))).isEqualTo("3.9.11");
        assertThat(EnforcerMavenVersion.minimum(pom(dir, "[3.6.3,4.0)", ""))).isEqualTo("3.6.3");
        assertThat(EnforcerMavenVersion.minimum(pom(
                        dir,
                        "${required.maven.version}",
                        "<properties><required.maven.version>3.9.11</required.maven.version></properties>")))
                .isEqualTo("3.9.11");
    }

    @Test
    void the_highest_of_several_rules_wins_and_an_unreadable_one_is_no_floor(@TempDir Path dir) throws Exception {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <groupId>g</groupId><artifactId>a</artifactId><version>1</version>
                  <build><plugins><plugin>
                    <artifactId>maven-enforcer-plugin</artifactId>
                    <executions>
                      <execution><id>a</id><configuration><rules>
                        <requireMavenVersion><version>3.6.3</version></requireMavenVersion>
                      </rules></configuration></execution>
                      <execution><id>b</id><configuration><rules>
                        <requireMavenVersion><version>[3.9.11,)</version></requireMavenVersion>
                        <requireMavenVersion><version>${unset.property}</version></requireMavenVersion>
                        <requireMavenVersion><version>(,3.9.20]</version></requireMavenVersion>
                      </rules></configuration></execution>
                    </executions>
                  </plugin></plugins></build>
                </project>
                """);
        assertThat(EnforcerMavenVersion.minimum(dir)).isEqualTo("3.9.11");
    }

    @Test
    void a_pom_without_the_rule_or_no_pom_at_all_states_no_floor(@TempDir Path dir) throws Exception {
        assertThat(EnforcerMavenVersion.minimum(dir)).isNull();
        Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>a</artifactId></project>");
        assertThat(EnforcerMavenVersion.minimum(dir)).isNull();
        Files.writeString(dir.resolve("pom.xml"), "<project><not closed");
        assertThat(EnforcerMavenVersion.minimum(dir)).isNull();
    }

    static Path pom(Path dir, String spec, String properties) throws Exception {
        Path project = Files.createDirectories(dir.resolve(Integer.toHexString(spec.hashCode())));
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                  <groupId>g</groupId><artifactId>a</artifactId><version>1</version>
                  %s
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-enforcer-plugin</artifactId>
                    <executions><execution><id>enforce</id><goals><goal>enforce</goal></goals>
                      <configuration><rules>
                        <requireMavenVersion><version>%s</version></requireMavenVersion>
                      </rules></configuration>
                    </execution></executions>
                  </plugin></plugins></build>
                </project>
                """.formatted(properties, spec));
        return project;
    }
}
