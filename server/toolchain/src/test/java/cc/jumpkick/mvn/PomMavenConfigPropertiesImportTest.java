// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.TestFailureMode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code -D} option in {@code .mvn/maven.config} is a user property, so every mapper that reads a
 * property sees it, ahead of the POM's own {@code <properties>}, as Maven's plugins do.
 */
class PomMavenConfigPropertiesImportTest {

    private static PomImporter.Result importWithConfig(Path tmp, String config, String properties) throws Exception {
        Path mvn = Files.createDirectories(tmp.resolve("project/.mvn"));
        Files.writeString(mvn.resolve("maven.config"), config);
        return TestImporters.importXml(tmp, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <properties>%s</properties>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <version>3.5.4</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(properties));
    }

    @Test
    void a_config_option_reaches_the_mappers_that_read_its_property(@TempDir Path tmp) throws Exception {
        PomImporter.Result result = importWithConfig(tmp, """
                -Dmaven.test.failure.ignore=true
                -DargLine=-Xmx1g
                -Dmaven.compiler.release=21
                -Dexec.mainClass=com.ex.Main
                """, "");
        JkBuild build = result.jkBuild();

        assertThat(build.build().testFailures()).isEqualTo(TestFailureMode.REPORT);
        assertThat(build.build().testJvm().jvmArgs()).containsExactly("-Xmx1g");
        assertThat(build.project().java()).isEqualTo(21);
        assertThat(build.applicationOpt().orElseThrow().main()).isEqualTo("com.ex.Main");
    }

    @Test
    void a_config_option_wins_over_the_poms_property(@TempDir Path tmp) throws Exception {
        PomImporter.Result result = importWithConfig(
                tmp,
                "-Dmaven.test.failure.ignore=true -DskipTests\n",
                "<maven.test.failure.ignore>false</maven.test.failure.ignore><skipTests>false</skipTests>");

        assertThat(result.jkBuild().build().testFailures()).isEqualTo(TestFailureMode.REPORT);
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith("maven-surefire-plugin skips tests: not imported"));
    }

    @Test
    void a_config_option_wins_over_an_inactive_profiles_property(@TempDir Path tmp) throws Exception {
        Path mvn = Files.createDirectories(tmp.resolve("project/.mvn"));
        Files.writeString(mvn.resolve("maven.config"), "-Dh2.version=2.3.232\n");
        PomImporter.Result result = TestImporters.importXml(tmp, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>0.1.0</version>
                  <profiles>
                    <profile>
                      <id>h2</id>
                      <activation><property><name>with-h2</name></property></activation>
                      <properties><h2.version>2.2.224</h2.version></properties>
                      <dependencies>
                        <dependency>
                          <groupId>com.h2database</groupId>
                          <artifactId>h2</artifactId>
                          <version>${h2.version}</version>
                          <scope>test</scope>
                        </dependency>
                      </dependencies>
                    </profile>
                  </profiles>
                </project>
                """);

        assertThat(result.jkBuild().dependencies().of(Scope.TEST))
                .extracting(d -> d.module() + "=" + d.version().raw(), Dependency::optional)
                .containsExactly(tuple("com.h2database:h2=2.3.232", true));
    }
}
