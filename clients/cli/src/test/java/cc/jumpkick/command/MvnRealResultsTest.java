// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkVersion;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real thing: jk provisions Maven, Maven loads this build's spy jar from its extension path,
 * runs a two-module reactor with one failing test, and the journal reads its events and surefire
 * XML back. Talks to Maven Central for the distribution and the plugins, hence the tier.
 */
@DisabledOnOs(OS.WINDOWS)
@Tag("network")
class MvnRealResultsTest {

    @TempDir
    Path tempDir;

    private @Nullable String previousSpy;

    @BeforeEach
    void useThisBuildsSpyJar() {
        previousSpy = System.getProperty("jk.maven-spy.jar");
        Path jar = RepoRoot.file(MvnRealResultsTest.class, "target/jk-maven-spy-" + JkVersion.VERSION + ".jar");
        System.setProperty("jk.maven-spy.jar", jar.toString());
    }

    @AfterEach
    void restoreSpyJar() {
        if (previousSpy == null) System.clearProperty("jk.maven-spy.jar");
        else System.setProperty("jk.maven-spy.jar", previousSpy);
    }

    @Test
    void jk_mvn_test_on_a_maven_only_reactor_writes_jk_results() throws Exception {
        Path projectDir = reactor(tempDir.resolve("proj"));
        int exit = run(
                "-C",
                projectDir.toString(),
                "mvn",
                "--tools-dir",
                tempDir.resolve("tools").toString(),
                "--no-discover",
                "-q",
                "test");
        assertThat(exit).isNotEqualTo(0);

        Path results = projectDir.resolve("target").resolve("jk-results.md");
        assertThat(results).exists();
        String md = Files.readString(results);
        assertThat(md).startsWith("# jk results — FAIL");
        assertThat(md).contains("trigger: cli · tool: mvn");
        assertThat(md).contains("## Tests");
        assertThat(md).contains("#### com.example.AppTest\n");
        assertThat(md).contains("##### `adds()`");
        assertThat(md).contains("expected: <4> but was: <5>");
        assertThat(md).contains("at com.example.AppTest.adds(AppTest.java:");
        assertThat(md).contains("## Modules");
        assertThat(md).contains("| com.example:app | FAIL |");
        assertThat(md).contains("| com.example:lib | OK |");
        assertThat(md).contains("| com.example:app | `surefire:test` | FAIL |");
    }

    /** A parent POM with modules lib (a class) and app (one passing and one failing JUnit 5 test). */
    private static Path reactor(Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("pom.xml"), parentPom());
        Path lib = Files.createDirectories(root.resolve("lib/src/main/java/com/example"));
        Files.writeString(root.resolve("lib/pom.xml"), modulePom("lib", ""));
        Files.writeString(
                lib.resolve("Lib.java"),
                "package com.example;\npublic final class Lib { public static int add(int a, int b) { return a + b + 1; } }\n");
        Path app = Files.createDirectories(root.resolve("app/src/test/java/com/example"));
        Files.writeString(
                root.resolve("app/pom.xml"),
                modulePom(
                        "app",
                        "<dependency><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>"));
        Files.writeString(app.resolve("AppTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class AppTest {
                    @Test
                    void greets() {
                        assertEquals(2, 1 + 1);
                    }

                    @Test
                    void adds() {
                        assertEquals(4, Lib.add(2, 2));
                    }
                }
                """);
        return root;
    }

    private static String parentPom() {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>reactor</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>lib</module><module>app</module></modules>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.13.4</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version></plugin>
                        <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.3</version></plugin>
                        <plugin><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                        <plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
                      </plugins>
                    </pluginManagement>
                  </build>
                </project>
                """;
    }

    private static String modulePom(String artifact, String dependencies) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent><groupId>com.example</groupId><artifactId>reactor</artifactId><version>1.0</version></parent>\n"
                + "  <artifactId>" + artifact + "</artifactId>\n"
                + "  <dependencies>" + dependencies + "</dependencies>\n"
                + "</project>\n";
    }
}
