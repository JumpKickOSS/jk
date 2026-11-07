// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Jlink;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.Scope;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Helidon's quickstarts (helidon-examples, branch {@code helidon-27.x}, commit {@code f8adff1d})
 * import to the {@code [helidon]} form the jk-helidon plugin's own fixtures are written in: the
 * parent is the table's version, not a platform row, Helidon's processors and its {@code copy-libs}
 * layout are the table's, and {@code create-application} is a binding option. Helidon's published
 * parent chain is served from a loopback repository ({@code helidon-dependencies} without its
 * third-party BOM imports), so nothing here reaches the network.
 */
class PomHelidonImportTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private static final String VERSION = "27.0.1";

    /** The Helidon modules both quickstarts declare in main scope, every one versioned by the BOM. */
    private static final List<String> MAIN = List.of(
            "io.helidon.webserver:helidon-webserver",
            "io.helidon.http.media:helidon-http-media-jsonp",
            "io.helidon.webserver.observe:helidon-webserver-observe-health",
            "io.helidon.webserver.observe:helidon-webserver-observe-metrics",
            "io.helidon.metrics:helidon-metrics-system-meters",
            "io.helidon.openapi:helidon-openapi",
            "io.helidon.config:helidon-config-yaml",
            "io.helidon.health:helidon-health-checks",
            "jakarta.json:jakarta.json-api");

    @BeforeEach
    void serveHelidonChain() throws Exception {
        serve("io.helidon.applications", "helidon-se");
        serve("io.helidon.applications", "helidon-applications");
        serve("io.helidon.applications", "helidon-applications-project");
        serve("io.helidon", "helidon-dependencies");
        serve("io.helidon", "helidon-bom");
        serve("io.helidon", "helidon-parent");
    }

    @Test
    void the_se_quickstart_is_the_helidon_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importQuickstart(tempDir, "se", List.of());
        JkBuild build = result.jkBuild();

        assertThat(helidon(build)).isEqualTo(Map.of("version", VERSION));
        assertQuickstartShape(result, MAIN);
        assertThat(JkBuildRenderer.render(build)).contains("[helidon]\nversion = \"" + VERSION + "\"\n");
    }

    @Test
    void the_inject_quickstart_drops_helidons_processors_and_maps_create_application(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = importQuickstart(tempDir, "inject", List.of());
        JkBuild build = result.jkBuild();

        assertThat(helidon(build))
                .as("create-application at its defaults is the table's default binding")
                .isEqualTo(Map.of("version", VERSION));
        List<String> main = new ArrayList<>(List.of("io.helidon.service:helidon-service-registry"));
        main.addAll(MAIN);
        assertQuickstartShape(result, main);
        assertThat(build.dependencies().of(Scope.PROCESSOR))
                .as("helidon-codegen-apt and helidon-service-codegen are the table's")
                .isEmpty();
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.contains(HelidonPlugins.SERVICE_PLUGIN));
        assertThat(JkBuildRenderer.render(build)).doesNotContain("[processor-dependencies]");
    }

    @Test
    void the_parents_jlink_image_profile_is_the_jlink_table(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importQuickstart(tempDir, "se", List.of("jlink-image"));

        Jlink jlink = Objects.requireNonNull(result.jkBuild().build().jlink());
        assertThat(jlink.stripDebug())
                .as("helidon-maven-plugin keeps debug attributes by default")
                .isFalse();
        assertThat(jlink.aotCache()).isEqualTo(Jlink.AotCache.AUTO);
        assertThat(jlink.testStart()).isTrue();
        assertThat(jlink.startTimeoutSeconds()).isEqualTo(Jlink.DEFAULT_START_TIMEOUT);
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.contains(HelidonPlugins.MAVEN_PLUGIN));
        assertThat(JkBuildRenderer.render(result.jkBuild())).contains("[jlink]\nstrip-debug = false\n");
    }

    @Test
    void jlink_image_settings_map_onto_the_jlink_keys(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importPom(tempDir, application("""
                <plugin>
                  <groupId>io.helidon.build-tools</groupId>
                  <artifactId>helidon-maven-plugin</artifactId>
                  <executions><execution>
                    <id>jlink-image</id>
                    <goals><goal>jlink-image</goal></goals>
                    <configuration>
                      <additionalModules><module>jdk.crypto.ec</module></additionalModules>
                      <stripDebug>true</stripDebug>
                      <aotCache>cds</aotCache>
                      <defaultJvmOptions><defaultJvmOption>-Xmx256m</defaultJvmOption></defaultJvmOptions>
                      <defaultArgs><defaultArg>--port=9090</defaultArg></defaultArgs>
                      <maxAppStartSeconds>120</maxAppStartSeconds>
                      <additionalJlinkArgs><arg>--compress=zip-9</arg></additionalJlinkArgs>
                    </configuration>
                  </execution></executions>
                </plugin>
                """));

        Jlink jlink = Objects.requireNonNull(result.jkBuild().build().jlink());
        assertThat(jlink.modules()).containsExactly("jdk.crypto.ec");
        assertThat(jlink.stripDebug()).isTrue();
        assertThat(jlink.aotCache()).isEqualTo(Jlink.AotCache.CDS);
        assertThat(jlink.jvmArgs()).containsExactly("-Xmx256m");
        assertThat(jlink.args()).containsExactly("--port=9090");
        assertThat(jlink.startTimeoutSeconds()).isEqualTo(120);
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith(HelidonPlugins.MAVEN_PLUGIN + " <additionalJlinkArgs> not imported"));
    }

    @Test
    void an_imported_helidon_bom_is_the_table_and_service_options_are_its_keys(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = importPom(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>27</maven.compiler.release>
                    <mainClass>com.ex.Main</mainClass>
                  </properties>
                  <dependencyManagement><dependencies><dependency>
                    <groupId>io.helidon</groupId><artifactId>helidon-dependencies</artifactId>
                    <version>27.0.1</version><type>pom</type><scope>import</scope>
                  </dependency></dependencies></dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>io.helidon.webserver</groupId><artifactId>helidon-webserver</artifactId>
                    </dependency>
                  </dependencies>
                  <build><plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-compiler-plugin</artifactId>
                      <configuration><annotationProcessorPaths>
                        <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>1.18.42</version></path>
                        <path><groupId>io.helidon.bundles</groupId><artifactId>helidon-bundles-apt</artifactId><version>27.0.1</version></path>
                      </annotationProcessorPaths></configuration>
                    </plugin>
                    <plugin>
                      <groupId>io.helidon.service</groupId>
                      <artifactId>helidon-service-maven-plugin</artifactId>
                      <version>27.0.1</version>
                      <executions>
                        <execution>
                          <id>create-application</id>
                          <goals><goal>create-application</goal></goals>
                          <configuration>
                            <generateMain>true</generateMain>
                            <validate>false</validate>
                            <packageName>com.ex.binding</packageName>
                          </configuration>
                        </execution>
                        <execution>
                          <id>create-test-application</id>
                          <goals><goal>test-application-create</goal></goals>
                        </execution>
                      </executions>
                    </plugin>
                  </plugins></build>
                </project>
                """);
        JkBuild build = result.jkBuild();

        assertThat(helidon(build))
                .containsEntry("version", VERSION)
                .containsEntry("generate-main", true)
                .containsEntry("validate", false)
                .containsEntry("declarative", true)
                .as("no copy-dependencies execution: Maven ships the plain jar")
                .containsEntry("layout", "jar")
                .doesNotContainKey("binding");
        assertThat(build.dependencies().of(Scope.PLATFORM)).isEmpty();
        assertThat(build.dependencies().of(Scope.PROCESSOR))
                .extracting(Dependency::module)
                .as("Lombok stays; helidon-bundles-apt is declarative = true")
                .containsExactly("org.projectlombok:lombok");
        assertThat(build.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module, Dependency::isPlatformManaged)
                .containsExactly(tuple("io.helidon.webserver:helidon-webserver", true));
        assertThat(TestImporters.messages(result))
                .anyMatch(
                        m -> m.startsWith(HelidonPlugins.SERVICE_PLUGIN + " <packageName>: not carried into [helidon]"))
                .noneMatch(m -> m.contains(HelidonPlugins.SERVICE_PLUGIN + " not imported"));
    }

    @Test
    void helidon_below_27_keeps_the_generic_import(@TempDir Path tempDir) throws Exception {
        http.serve(TestImporters.pomPath("io.helidon.applications", "helidon-se", "4.2.0"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>io.helidon.applications</groupId>
                  <artifactId>helidon-se</artifactId>
                  <version>4.2.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies><dependency>
                    <groupId>io.helidon.webserver</groupId><artifactId>helidon-webserver</artifactId><version>4.2.0</version>
                  </dependency></dependencies></dependencyManagement>
                </project>
                """);
        PomImporter.Result result = importPom(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>io.helidon.applications</groupId><artifactId>helidon-se</artifactId>
                    <version>4.2.0</version><relativePath/>
                  </parent>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>io.helidon.webserver</groupId><artifactId>helidon-webserver</artifactId>
                    </dependency>
                  </dependencies>
                  <build><plugins><plugin>
                    <groupId>io.helidon.service</groupId>
                    <artifactId>helidon-service-maven-plugin</artifactId>
                    <version>4.2.0</version>
                    <executions><execution><goals><goal>create-application</goal></goals></execution></executions>
                  </plugin></plugins></build>
                </project>
                """);
        JkBuild build = result.jkBuild();

        assertThat(build.pluginConfig("helidon")).isEmpty();
        assertThat(build.dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module)
                .containsExactly("io.helidon.applications:helidon-se");
        assertThat(TestImporters.messages(result))
                .anyMatch(m -> m.startsWith("Helidon 4.2.0: the [helidon] table is for Helidon 27 and later"))
                .contains(HelidonPlugins.SERVICE_PLUGIN + " not imported");
    }

    /**
     * What both quickstarts share with the plugin's fixtures: {@code java = 27}, the main class, the
     * BOM's versions as {@code managed} with no platform row, the runtime and test dependencies, no
     * lib-dir (the table's layout), no test config profile (the table's) and no row for the parent's
     * plugins the table accounts for.
     */
    private static void assertQuickstartShape(PomImporter.Result result, List<String> main) {
        JkBuild build = result.jkBuild();
        assertThat(build.project().java()).isEqualTo(27);
        assertThat(build.dependencies().of(Scope.PLATFORM))
                .as("the [helidon] table contributes helidon-dependencies")
                .isEmpty();
        assertThat(build.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactlyInAnyOrderElementsOf(main);
        assertThat(build.dependencies().of(Scope.MAIN)).allMatch(Dependency::isPlatformManaged);
        assertThat(build.dependencies().of(Scope.RUNTIME))
                .extracting(Dependency::module)
                .containsExactly("io.helidon.logging:helidon-logging-jul");
        assertThat(build.dependencies().of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactlyInAnyOrder(
                        "org.junit.jupiter:junit-jupiter-api",
                        "io.helidon.webserver.testing.junit5:helidon-webserver-testing-junit5",
                        "io.helidon.webclient:helidon-webclient",
                        "org.hamcrest:hamcrest-all");
        assertThat(build.dependencies().of(Scope.TEST)).allMatch(Dependency::isPlatformManaged);
        assertThat(build.libDir()).as("copy-libs is the table's default layout").isNull();
        assertThat(build.build().testJvm().systemProperties())
                .as("the table sets helidon.config.profile=test")
                .doesNotContainKey("helidon.config.profile");
        assertThat(TestImporters.messages(result))
                .noneMatch(m -> m.contains("maven-dependency-plugin"))
                .noneMatch(m -> m.contains("written as [platform]"))
                .noneMatch(m -> m.contains("not imported"));
        assertThat(result.report().hasErrors()).isFalse();
        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered)
                .doesNotContain("[platform-dependencies]")
                .doesNotContain("lib-dir")
                .doesNotContain("helidon.config.profile");
    }

    private static Map<String, Object> helidon(JkBuild build) {
        return build.pluginConfig("helidon").map(PluginConfig::values).orElseThrow();
    }

    private PomImporter.Result importQuickstart(Path tempDir, String flavor, List<String> profiles) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("helidon-quickstart-" + flavor));
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, TestImporters.fixture("helidon", "helidon-quickstart-" + flavor + "-pom.xml"));
        return TestImporters.over(tempDir, http.base()).activeProfiles(profiles).importFrom(pom);
    }

    private PomImporter.Result importPom(Path tempDir, String xml) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("app"));
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, xml);
        return TestImporters.over(tempDir, http.base()).importFrom(pom);
    }

    /** A helidon-se child with a main and the parent's copy-libs, plus {@code plugins}. */
    private static String application(String plugins) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>io.helidon.applications</groupId><artifactId>helidon-se</artifactId>
                    <version>27.0.1</version><relativePath/>
                  </parent>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <properties><mainClass>com.ex.Main</mainClass></properties>
                  <build><plugins>
                    <plugin>
                      <groupId>org.apache.maven.plugins</groupId>
                      <artifactId>maven-dependency-plugin</artifactId>
                      <executions><execution><id>copy-libs</id></execution></executions>
                    </plugin>
                %s  </plugins></build>
                </project>
                """.formatted(plugins);
    }

    private void serve(String group, String artifact) throws Exception {
        http.serve(
                TestImporters.pomPath(group, artifact, VERSION),
                TestImporters.fixture("helidon", artifact + "-" + VERSION + ".pom"));
    }
}
