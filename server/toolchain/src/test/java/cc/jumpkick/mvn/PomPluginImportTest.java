// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JavadocMode;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.SourcesMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the compiler, toolchain and source-tree plugins land: annotation processor paths in
 * {@code [processor-dependencies]}, compiler args in {@code [javac]}, a toolchain pin alone in
 * {@code jdk}, build-helper roots in {@code extra-src}, the source plugin in {@code sources}, and a
 * row for what jk has no key for.
 */
class PomPluginImportTest {

    @Test
    void annotation_processor_paths_become_processor_dependencies(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importFixture(tempDir, "plugins", "processors-pom.xml");
        JkBuild build = result.jkBuild();

        assertThat(versions(build.dependencies().of(Scope.PROCESSOR)))
                .as("versions from the effective model; the versionless path takes the managed one")
                .containsExactly(
                        "org.projectlombok:lombok=1.18.42",
                        "org.mapstruct:mapstruct-processor=1.6.3",
                        "org.projectlombok:lombok-mapstruct-binding=0.2.0");
        assertThat(build.build().javac().args())
                .as("-A options ride [javac] args; the level pair is java = 17's to state")
                .containsExactly(
                        "-Amapstruct.defaultComponentModel=default",
                        "-Amapstruct.unmappedTargetPolicy=ERROR",
                        "-parameters");
        assertThat(build.project().java()).isEqualTo(17);
        assertThat(build.project().jdk()).isNull();
        assertThat(versions(build.dependencies().of(Scope.PROVIDED)))
                .containsExactly("org.projectlombok:lombok=1.18.42");
        assertThat(messages(result)).noneMatch(m -> m.contains("was not imported"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered)
                .contains("[processor-dependencies]")
                .contains("[javac]")
                .doesNotContain("jdk ");
        JkBuild reparsed = JkBuildParser.parse(rendered);
        assertThat(reparsed.dependencies().of(Scope.PROCESSOR)).hasSize(3);
        assertThat(reparsed.build().javac().args())
                .isEqualTo(build.build().javac().args());
    }

    @Test
    void processors_declared_as_plain_dependencies_are_written_when_no_path_is_declared(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = importFixture(tempDir, "plugins", "plain-processors-pom.xml");
        JkBuild build = result.jkBuild();

        assertThat(versions(build.dependencies().of(Scope.PROCESSOR)))
                .as("the recognized processors, at the POM's versions; guava and the mapstruct API are not processors")
                .containsExactly(
                        "org.projectlombok:lombok=1.18.42",
                        "org.mapstruct:mapstruct-processor=1.6.3",
                        "com.google.auto.value:auto-value=1.11.0");
        assertThat(versions(build.dependencies().of(Scope.PROVIDED)))
                .as("the plain declarations stay where the POM put them")
                .containsExactly("org.projectlombok:lombok=1.18.42", "com.google.auto.value:auto-value=1.11.0");
        assertThat(versions(build.dependencies().of(Scope.MAIN)))
                .containsExactly(
                        "org.mapstruct:mapstruct=1.6.3",
                        "org.mapstruct:mapstruct-processor=1.6.3",
                        "com.google.guava:guava=33.4.8-jre");
        assertThat(messages(result))
                .anyMatch(m -> m.startsWith("AutoValue, Lombok, MapStruct are declared as plain dependencies")
                        && m.contains("`[processor-dependencies]`"));

        JkBuild reparsed = JkBuildParser.parse(JkBuildRenderer.render(build));
        assertThat(reparsed.dependencies().of(Scope.PROCESSOR)).hasSize(3);
    }

    @Test
    void source_tree_plugins_land_in_extra_src_sources_and_rows(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importFixture(tempDir, "plugins", "source-tree-pom.xml");
        JkBuild build = result.jkBuild();
        List<String> messages = messages(result);

        assertThat(build.build().extraSrc())
                .as("${project.basedir} is interpolated to the POM's directory and relativized away")
                .containsExactly("src/main/generated");
        assertThat(build.build().testExtraSrc()).containsExactly("src/it/java");
        assertThat(build.project().sourcesMode()).isEqualTo(SourcesMode.ALWAYS);
        assertThat(build.project().javadocMode())
                .as("<failOnError>true</failOnError> is doclint failing the build")
                .isEqualTo(JavadocMode.STRICT);
        assertThat(build.manifest()).containsEntry("Implementation-Title", "lib");
        assertThat(build.project().java()).isEqualTo(21);

        assertThat(messages)
                .anyMatch(m -> m.startsWith("`<resources>` with `<filtering>true</filtering>` on src/main/resources"));
        assertThat(messages).anyMatch(m -> m.startsWith("`<resources>` directory src/main/config is outside"));
        assertThat(messages).noneMatch(m -> m.contains("javadoc"));
        assertThat(messages)
                .anyMatch(
                        m -> m.startsWith("`build-helper-maven-plugin` goal `reserve-network-port` was not imported"));
        assertThat(messages)
                .as("resources, build-helper, jar, source and javadoc each have their own landing place")
                .noneMatch(m -> m.startsWith("`<plugin>"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered)
                .contains("sources  = \"always\"")
                .contains("javadoc  = \"strict\"")
                .contains("[build]\nextra-src = [\"src/main/generated\"]")
                .contains("[test]\nextra-src = [\"src/it/java\"]");
        JkBuild reparsed = JkBuildParser.parse(rendered);
        assertThat(reparsed.build().extraSrc()).containsExactly("src/main/generated");
        assertThat(reparsed.build().testExtraSrc()).containsExactly("src/it/java");
        assertThat(reparsed.project().sourcesMode()).isEqualTo(SourcesMode.ALWAYS);
        assertThat(reparsed.project().javadocMode()).isEqualTo(JavadocMode.STRICT);
    }

    @Test
    void a_plain_javadoc_plugin_is_satisfied_by_the_library_default(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0.0</version>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-javadoc-plugin</artifactId>
                    <version>3.11.2</version>
                    <executions><execution><goals><goal>jar</goal></goals></execution></executions>
                  </plugin></plugins></build>
                </project>
                """);
        assertThat(result.jkBuild().project().javadocMode()).isEqualTo(JavadocMode.LENIENT);
        assertThat(JkBuildRenderer.render(result.jkBuild())).doesNotContain("javadoc");
        assertThat(messages(result)).noneMatch(m -> m.contains("javadoc"));
    }

    /** {@code <doclint>none</doclint>} is the plugin turning doclint off: the lenient default, not strict. */
    @Test
    void a_javadoc_plugin_with_doclint_none_stays_lenient(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0.0</version>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-javadoc-plugin</artifactId>
                    <version>3.12.0</version>
                    <executions><execution>
                      <phase>package</phase>
                      <goals><goal>jar</goal></goals>
                      <configuration><doclint>none</doclint></configuration>
                    </execution></executions>
                  </plugin></plugins></build>
                </project>
                """);
        assertThat(result.jkBuild().project().javadocMode()).isEqualTo(JavadocMode.LENIENT);
        assertThat(JkBuildRenderer.render(result.jkBuild())).doesNotContain("javadoc");
    }

    @Test
    void a_javadoc_plugin_that_keeps_doclint_on_is_strict_unless_it_never_fails(@TempDir Path tempDir)
            throws Exception {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0.0</version>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-javadoc-plugin</artifactId>
                    <version>3.12.0</version>
                    <configuration>%s</configuration>
                  </plugin></plugins></build>
                </project>
                """;
        assertThat(importXml(tempDir.resolve("all"), pom.formatted("<doclint>all,-missing</doclint>"))
                        .jkBuild()
                        .project()
                        .javadocMode())
                .isEqualTo(JavadocMode.STRICT);
        assertThat(importXml(
                                tempDir.resolve("off"),
                                pom.formatted("<doclint>all</doclint><failOnError>false</failOnError>"))
                        .jkBuild()
                        .project()
                        .javadocMode())
                .as("failOnError=false: doclint findings are printed, the build never fails on them")
                .isEqualTo(JavadocMode.LENIENT);
    }

    @Test
    void compiler_switches_become_javac_flags_once(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.14.0</version>
                    <configuration>
                      <parameters>true</parameters>
                      <enablePreview>true</enablePreview>
                      <failOnWarning>true</failOnWarning>
                      <showWarnings>true</showWarnings>
                      <compilerArgs><arg>-parameters</arg><arg>-Xlint:all</arg></compilerArgs>
                    </configuration>
                  </plugin></plugins></build>
                </project>
                """);

        assertThat(result.jkBuild().build().javac().args())
                .as("a switch spelled twice lands once; a switch with no javac flag is not invented")
                .containsExactly("-parameters", "-Xlint:all", "--enable-preview", "-Werror");
    }

    @Test
    void a_compile_goal_executions_args_reach_compile_main_alone(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.14.0</version>
                    <configuration>
                      <compilerArgs><arg>-parameters</arg></compilerArgs>
                      <annotationProcessorPaths>
                        <path><groupId>com.uber.nullaway</groupId><artifactId>nullaway</artifactId><version>0.13.0</version></path>
                      </annotationProcessorPaths>
                    </configuration>
                    <executions>
                      <execution>
                        <id>java-compile</id>
                        <goals><goal>compile</goal></goals>
                        <configuration>
                          <compilerArgs>
                            <arg>-XDcompilePolicy=simple</arg>
                            <arg>-Xplugin:ErrorProne -Xep:NullAway:ERROR</arg>
                          </compilerArgs>
                        </configuration>
                      </execution>
                      <execution>
                        <id>java-test-compile</id>
                        <goals><goal>testCompile</goal></goals>
                        <configuration>
                          <compilerArgs><arg>-Xlint:none</arg></compilerArgs>
                          <annotationProcessorPaths>
                            <path><groupId>org.mapstruct</groupId><artifactId>mapstruct-processor</artifactId><version>1.6.3</version></path>
                          </annotationProcessorPaths>
                        </configuration>
                      </execution>
                    </executions>
                  </plugin></plugins></build>
                </project>
                """);
        JkBuild build = result.jkBuild();

        assertThat(build.build().javac().args())
                .as("the plugin's own configuration and the compile goal's execution")
                .containsExactly("-parameters", "-XDcompilePolicy=simple", "-Xplugin:ErrorProne -Xep:NullAway:ERROR");
        assertThat(build.build().javac().forTests().args())
                .as("the plugin's own configuration and the testCompile goal's execution")
                .containsExactly("-parameters", "-Xlint:none");
        assertThat(versions(build.dependencies().of(Scope.PROCESSOR)))
                .as("the plugin's own paths serve both compiles")
                .containsExactly("com.uber.nullaway:nullaway=0.13.0");
        assertThat(versions(build.dependencies().of(Scope.TEST_PROCESSOR)))
                .as("a testCompile execution's paths run over the test sources alone")
                .containsExactly("org.mapstruct:mapstruct-processor=1.6.3");
        List<String> messages = messages(result);
        assertThat(messages)
                .anySatisfy(m -> assertThat(m)
                        .contains("execution `java-compile`")
                        .contains("goal `compile`")
                        .contains("[javac.test]"))
                .anySatisfy(m -> assertThat(m)
                        .contains("`testCompile` execution")
                        .contains("org.mapstruct:mapstruct-processor")
                        .contains("[test-processor-dependencies]"))
                .noneSatisfy(m -> assertThat(m).contains("both compiles run them"));

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered)
                .contains("[javac]\nargs = [\"-parameters\", \"-XDcompilePolicy=simple\","
                        + " \"-Xplugin:ErrorProne -Xep:NullAway:ERROR\"]")
                .contains("[javac.test]\nargs = [\"-parameters\", \"-Xlint:none\"]")
                .contains("[test-processor-dependencies]\nmapstruct-processor = ");
        JkBuild reparsed = JkBuildParser.parse(rendered);
        assertThat(versions(reparsed.dependencies().of(Scope.TEST_PROCESSOR)))
                .containsExactly("org.mapstruct:mapstruct-processor=1.6.3");
        assertThat(reparsed.build().javac().args())
                .isEqualTo(build.build().javac().args());
        assertThat(reparsed.build().javac().forTests().args())
                .isEqualTo(build.build().javac().forTests().args());
    }

    @Test
    void a_compile_goal_execution_that_is_all_the_arguments_leaves_the_suite_with_none(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.14.0</version>
                    <executions><execution>
                      <id>default-compile</id>
                      <configuration><compilerArgs><arg>-Werror</arg></compilerArgs></configuration>
                    </execution></executions>
                  </plugin></plugins></build>
                </project>
                """);
        JkBuild build = result.jkBuild();

        assertThat(build.build().javac().args()).containsExactly("-Werror");
        assertThat(build.build().javac().forTests().args())
                .as("the default-compile execution is the compile goal's")
                .isEmpty();
        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered).contains("[javac.test]\nplugins = {}\n");
        assertThat(JkBuildParser.parse(rendered).build().javac().forTests().args())
                .isEmpty();
    }

    /**
     * Error Prone's documented {@code -J--add-exports} lines open {@code jdk.compiler} packages jk
     * grants every compiler worker: the import drops them and says so once, and a {@code -J} flag
     * jk does not grant stays.
     */
    @Test
    void J_lines_jk_already_grants_the_worker_are_dropped_with_one_row(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.14.0</version>
                    <configuration>
                      <compilerArgs>
                        <arg>-XDcompilePolicy=simple</arg>
                        <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED</arg>
                        <arg>-J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED</arg>
                        <arg>-J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED</arg>
                        <arg>-J--add-opens=java.base/java.lang=ALL-UNNAMED</arg>
                      </compilerArgs>
                    </configuration>
                  </plugin></plugins></build>
                </project>
                """);

        assertThat(result.jkBuild().build().javac().args())
                .as("the flags jk grants are gone; the one it does not stays")
                .containsExactly("-XDcompilePolicy=simple", "-J--add-opens=java.base/java.lang=ALL-UNNAMED");
        assertThat(messages(result)).anySatisfy(m -> assertThat(m)
                .contains("3 `-J` lines")
                .contains("jdk.compiler")
                .contains("jk grants every compiler worker")
                .contains("com.sun.tools.javac.api")
                .contains("not written"));
    }

    @Test
    void a_toolchain_pin_is_the_only_thing_that_writes_jdk(@TempDir Path tempDir) throws Exception {
        PomImporter.Result pinned = importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-toolchains-plugin</artifactId>
                    <version>3.2.0</version>
                    <configuration><toolchains><jdk>
                      <version>21</version>
                      <vendor>temurin</vendor>
                    </jdk></toolchains></configuration>
                  </plugin></plugins></build>
                </project>
                """);
        assertThat(pinned.jkBuild().project().jdk()).isEqualTo("temurin-21");
        assertThat(pinned.jkBuild().project().java()).isEqualTo(17);
        assertThat(messages(pinned)).anyMatch(m -> m.startsWith("`<toolchains>` pins JDK temurin-21"));

        PomImporter.Result below = importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration><source>1.8</source><target>1.8</target>
                      <jdkToolchain><version>[1.8,9)</version></jdkToolchain></configuration>
                  </plugin></plugins></build>
                </project>
                """);
        assertThat(below.jkBuild().project().jdk())
                .as("a pin below the floor is dropped, not written")
                .isNull();
        assertThat(below.jkBuild().project().java()).isEqualTo(17);
        assertThat(messages(below)).anyMatch(m -> m.startsWith("toolchain pin `8` is below jk's floor of 17"));
        assertThat(messages(below)).anyMatch(m -> m.startsWith("`<target>` declared 8; jk's floor is 17"));
    }

    private static PomImporter.Result importFixture(Path tempDir, String dir, String file) throws Exception {
        return importXml(tempDir, TestImporters.fixture(dir, file));
    }

    private static PomImporter.Result importXml(Path tempDir, String xml) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, xml, StandardCharsets.UTF_8);
        return TestImporters.offline(tempDir).importFrom(pom);
    }

    private static List<String> messages(PomImporter.Result result) {
        return result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();
    }

    private static List<String> versions(List<Dependency> deps) {
        return deps.stream().map(d -> d.module() + "=" + d.version().raw()).toList();
    }
}
