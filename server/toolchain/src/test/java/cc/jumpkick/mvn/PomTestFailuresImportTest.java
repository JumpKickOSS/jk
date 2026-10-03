// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.TestFailureMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Maven's ways to keep going past a failing test import as {@code [test] failures = "report"}. */
class PomTestFailuresImportTest {

    private static String pom(String properties, String plugins) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>svc</artifactId>
                  <version>1.0.0</version>
                  <properties>%s</properties>
                  <build><plugins>%s</plugins></build>
                </project>
                """.formatted(properties, plugins);
    }

    private static String plugin(String artifactId, String configuration) {
        return """
                <plugin>
                  <groupId>org.apache.maven.plugins</groupId>
                  <artifactId>%s</artifactId>
                  <version>3.5.4</version>
                  <configuration>%s</configuration>
                </plugin>
                """.formatted(artifactId, configuration);
    }

    @Test
    void the_maven_property_is_report_and_round_trips(@TempDir Path dir) throws Exception {
        PomImporter.Result result =
                TestImporters.importXml(dir, pom("<maven.test.failure.ignore>true</maven.test.failure.ignore>", ""));
        assertThat(result.jkBuild().build().testFailures()).isEqualTo(TestFailureMode.REPORT);
        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("\n[test]\nfailures = \"report\"\n");
        assertThat(JkBuildParser.parse(rendered).build().testFailures()).isEqualTo(TestFailureMode.REPORT);
    }

    @Test
    void surefire_test_failure_ignore_is_report(@TempDir Path dir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(
                dir, pom("", plugin("maven-surefire-plugin", "<testFailureIgnore>true</testFailureIgnore>")));
        assertThat(result.jkBuild().build().testFailures()).isEqualTo(TestFailureMode.REPORT);
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.contains("testFailureIgnore"));
    }

    @Test
    void failsafe_alone_is_report_with_a_note_that_it_widens(@TempDir Path dir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(
                dir, pom("", plugin("maven-failsafe-plugin", "<testFailureIgnore>true</testFailureIgnore>")));
        assertThat(result.jkBuild().build().testFailures()).isEqualTo(TestFailureMode.REPORT);
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith("`maven-failsafe-plugin` `<testFailureIgnore>` is written as"));
    }

    @Test
    void a_false_or_absent_setting_fails_and_writes_nothing(@TempDir Path dir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(
                dir, pom("", plugin("maven-surefire-plugin", "<testFailureIgnore>false</testFailureIgnore>")));
        assertThat(result.jkBuild().build().testFailures()).isEqualTo(TestFailureMode.FAIL);
        assertThat(JkBuildRenderer.render(result.jkBuild())).doesNotContain("failures =");
    }
}
