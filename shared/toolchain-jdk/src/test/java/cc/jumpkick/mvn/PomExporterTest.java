// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.DomXml;
import cc.jumpkick.model.JkBuild;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class PomExporterTest {

    private static JkBuild parse(String toml) {
        return JkBuildParser.parse(toml);
    }

    @Test
    void tests_kind_exports_maven_test_jar() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [test-dependencies]
                helpers = { group = "com.acme", name = "helpers", version = "1.2.3", kind = "tests" }
                """);

        String xml = PomExporter.export(b).xml();

        assertThat(xml)
                .contains("<artifactId>helpers</artifactId>")
                .contains("<type>test-jar</type>")
                .contains("<classifier>tests</classifier>")
                .contains("<scope>test</scope>");
    }

    @Test
    void managed_dependencies_export_as_plain_dependency_management_entries() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [managed-dependencies]
                commons-io = "commons-io:commons-io:2.16.1"
                """);

        String xml = PomExporter.export(b).xml();

        assertThat(xml)
                .contains("<dependencyManagement>")
                .contains("<artifactId>commons-io</artifactId>")
                .contains("<version>2.16.1</version>")
                .doesNotContain("<scope>import</scope>");
        assertThat(xml.substring(xml.indexOf("</dependencyManagement>")))
                .as("a managed entry is a constraint, not a dependency")
                .doesNotContain("commons-io");
    }

    @Test
    void fixtures_are_not_a_published_artifact() {
        JkBuild producer = parse("""
                group = "com.example"
                name  = "helpers"
                version = "1.0.0"
                java = 25

                [test]
                fixtures = true
                """);
        String producerXml = PomExporter.export(producer).xml();
        assertThat(producerXml)
                .doesNotContain("test-fixtures")
                .doesNotContain("testFixtures")
                .doesNotContain("<classifier>fixtures</classifier>");

        JkBuild consumer = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [test-dependencies]
                helpers = { group = "com.acme", name = "helpers", version = "1.2.3" }
                """);
        String consumerXml = PomExporter.export(consumer).xml();
        assertThat(consumerXml)
                .contains("<artifactId>helpers</artifactId>")
                .doesNotContain("<type>test-jar</type>")
                .doesNotContain("<classifier>tests</classifier>")
                .doesNotContain("<classifier>fixtures</classifier>");
    }

    @Test
    void coords_release_and_dependency() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.2.3"
                java = 25

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre" }
                """);

        String xml = PomExporter.export(b).xml();

        assertThat(xml).contains("<groupId>com.example</groupId>");
        assertThat(xml).contains("<artifactId>app</artifactId>");
        assertThat(xml).contains("<version>1.2.3</version>");
        assertThat(xml).contains("<maven.compiler.release>25</maven.compiler.release>");
        assertThat(xml).contains("<artifactId>guava</artifactId>");
    }

    @Test
    void older_language_levels_17_and_21_still_export() {
        // Compat: defaults are newest stable, but java=17 / java=21 remain valid floors.
        for (int release : new int[] {17, 21}) {
            JkBuild b = parse("""
                    group = "com.example"
                    name  = "app"
                    version = "1.0.0"
                    java = %d
                    """.formatted(release));
            assertThat(PomExporter.export(b).xml())
                    .contains("<maven.compiler.release>" + release + "</maven.compiler.release>");
        }
    }

    @Test
    void locked_version_wins_over_selector() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "^33.0.0-jre" }
                """);

        String xml = PomExporter.export(b, Map.of("com.google.guava:guava", "33.4.0-jre"))
                .xml();

        assertThat(xml).contains("<version>33.4.0-jre</version>");
        assertThat(xml).doesNotContain("33.0.0-jre");
    }

    @Test
    void locked_version_matches_full_package_keys_for_plain_and_tests_kind() {
        // The engine's lock map keys on the full package id (g:a:type:classifier) — the
        // exporter must hit it for the plain jar AND the tests-kind test-jar edge.
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "^33.0.0-jre" }

                [test-dependencies]
                helpers = { group = "com.acme", name = "helpers", version = "^1.0.0", kind = "tests" }
                """);

        PomExporter.Result result = PomExporter.export(
                b,
                Map.of(
                        "com.google.guava:guava:jar:", "33.4.0-jre",
                        "com.acme:helpers:test-jar:tests", "1.2.3"));

        assertThat(result.xml()).contains("<version>33.4.0-jre</version>");
        assertThat(result.xml()).contains("<version>1.2.3</version>");
        assertThat(result.xml()).doesNotContain("33.0.0-jre");
        // The collapsed-selector fallback warning fires only when genuinely unlocked.
        assertThat(result.report().issues()).noneMatch(i -> i.message().contains("caret"));
    }

    @Test
    void jdk_emits_toolchains_plugin_and_main_emits_jar_plugin() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk  = 25
                java = 25

                [application]
                main = "com.example.Main"
                """);

        String xml = PomExporter.export(b).xml();

        assertThat(xml).contains("<artifactId>toolchains-maven-plugin</artifactId>");
        assertThat(xml).contains("<artifactId>maven-jar-plugin</artifactId>");
        assertThat(xml).contains("<mainClass>com.example.Main</mainClass>");
    }

    @Test
    void kotlin_shadow_and_native_emit_plugins() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25
                kotlin = "2.3.21"

                [application]
                main       = "com.example.Main"
                assembly = true

                [native]
                enabled = "always"
                """);

        String xml = PomExporter.export(b).xml();

        assertThat(xml).contains("<artifactId>kotlin-maven-plugin</artifactId>");
        assertThat(xml).contains("<artifactId>maven-shade-plugin</artifactId>");
        assertThat(xml).contains("<artifactId>native-maven-plugin</artifactId>");
    }

    /**
     * Regression: plugin executions must use Maven's OWN element vocabulary —
     * {@code <phase>}/{@code <goals>}/{@code <goal>}. A domain-vocabulary rename once rewrote
     * these literals to {@code <step>}/{@code <pipelines>}, producing poms Maven rejects with
     * "Unrecognised tag".
     */
    @Test
    void executions_use_maven_element_vocabulary_and_parse_as_xml() throws Exception {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 25
                java = 25
                kotlin = "2.3.21"

                [application]
                main       = "com.example.Main"
                assembly = true
                """);

        String xml = PomExporter.export(b).xml();

        assertThat(xml)
                .contains("<phase>compile</phase>", "<goals><goal>compile</goal></goals>")
                .contains("<goals><goal>toolchain</goal></goals>")
                .contains("<phase>package</phase>", "<goals><goal>shade</goal></goals>")
                .doesNotContain("<step>", "<pipelines>", "<pipeline>");

        // The whole document must be well-formed XML (what mvn's parser sees first).
        Document doc = DomXml.parse(xml);
        assertThat(doc.getElementsByTagName("goals").getLength()).isGreaterThanOrEqualTo(3);
        assertThat(doc.getElementsByTagName("step").getLength()).isZero();
    }

    @Test
    void processor_scope_becomes_annotation_processor_path() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [processor-dependencies]
                mapstruct-ap = { group = "org.mapstruct", name = "mapstruct-processor", version = "1.6.3" }
                """);

        String xml = PomExporter.export(b).xml();

        assertThat(xml).contains("<annotationProcessorPaths>");
        assertThat(xml).contains("<artifactId>mapstruct-processor</artifactId>");
    }

    @Test
    void workspace_root_is_pom_packaging_with_modules() {
        JkBuild root = parse("""
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["mod-a", "mod-b"]
                """);

        String xml = PomExporter.export(root).xml();

        assertThat(xml).contains("<packaging>pom</packaging>");
        assertThat(xml).contains("<module>mod-a</module>");
        assertThat(xml).contains("<module>mod-b</module>");
    }

    @Test
    void git_dependency_warns() {
        JkBuild b = parse("""
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 25

                [dependencies]
                acme = { git = "https://example.com/acme.git", tag = "v1.0" }
                """);

        PomExporter.Result r = PomExporter.export(b);

        assertThat(r.report().issues()).anyMatch(i -> i.message().toLowerCase().contains("git"));
    }
}
