// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A POM's own {@code <repositories>}: the top-level list and those of profiles Maven activates
 * with no command line, Central and snapshot-only repositories left out, each entry naming the POM
 * that wrote it.
 */
class PomRepositoriesParseTest {

    @Test
    void top_level_and_default_active_profile_repositories_are_read_and_central_and_snapshot_only_are_not() {
        Pom pom = PomParser.parse("""
                <project>
                  <groupId>io.apicurio</groupId>
                  <artifactId>apicurio-registry</artifactId>
                  <version>2.6.13.Final</version>
                  <repositories>
                    <repository>
                      <id>central</id>
                      <url>https://repo.maven.apache.org/maven2</url>
                    </repository>
                    <repository>
                      <id>confluent</id>
                      <url>https://packages.confluent.io/maven/</url>
                    </repository>
                    <repository>
                      <id>apache.snapshots</id>
                      <url>https://repository.apache.org/snapshots</url>
                      <releases><enabled>false</enabled></releases>
                    </repository>
                  </repositories>
                  <profiles>
                    <profile>
                      <id>external_repos</id>
                      <activation>
                        <property>
                          <name>!skipDefault</name>
                        </property>
                      </activation>
                      <repositories>
                        <repository>
                          <id>jitpack.io</id>
                          <url>https://jitpack.io</url>
                        </repository>
                      </repositories>
                    </profile>
                    <profile>
                      <id>defaults</id>
                      <activation><activeByDefault>true</activeByDefault></activation>
                      <repositories>
                        <repository>
                          <url>https://maven.example.org/unnamed/</url>
                        </repository>
                      </repositories>
                    </profile>
                    <profile>
                      <id>release</id>
                      <activation>
                        <property>
                          <name>performRelease</name>
                          <value>true</value>
                        </property>
                      </activation>
                      <repositories>
                        <repository>
                          <id>staging</id>
                          <url>https://staging.example.org/</url>
                        </repository>
                      </repositories>
                    </profile>
                    <profile>
                      <id>old-jdk</id>
                      <activation><jdk>1.8</jdk></activation>
                      <repositories>
                        <repository>
                          <id>legacy</id>
                          <url>https://legacy.example.org/</url>
                        </repository>
                      </repositories>
                    </profile>
                  </profiles>
                </project>
                """);

        assertThat(pom.repositories())
                .containsExactly(
                        new Pom.Repository(
                                "confluent",
                                "https://packages.confluent.io/maven/",
                                "io.apicurio:apicurio-registry:2.6.13.Final"),
                        new Pom.Repository(
                                "jitpack.io", "https://jitpack.io", "io.apicurio:apicurio-registry:2.6.13.Final"),
                        new Pom.Repository(
                                "https://maven.example.org/unnamed/",
                                "https://maven.example.org/unnamed/",
                                "io.apicurio:apicurio-registry:2.6.13.Final"));
    }

    @Test
    void a_pom_without_repositories_has_none() {
        Pom pom = PomParser.parse(
                "<project><groupId>g</groupId><artifactId>a</artifactId><version>1</version></project>");
        assertThat(pom.repositories()).isEmpty();
    }
}
