// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.pom;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.List;
import org.junit.jupiter.api.Test;

class PomXmlTest {

    @Test
    void preamble_is_the_maven_4_header() {
        StringBuilder sb = new StringBuilder();
        PomXml.appendPreamble(sb);
        assertThat(sb.toString())
                .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project ")
                .contains("xmlns=\"http://maven.apache.org/POM/4.0.0\"")
                .endsWith("  <modelVersion>4.0.0</modelVersion>\n\n");
    }

    @Test
    void escape_replaces_the_five_xml_entities() {
        assertThat(PomXml.escape("a<b>c&d\"e'f")).isEqualTo("a&lt;b&gt;c&amp;d&quot;e&apos;f");
        assertThat(PomXml.escape(null)).isEmpty();
        assertThat(PomXml.escape("plain")).isEqualTo("plain");
    }

    @Test
    void mavenScope_maps_compile_scopes_to_null_and_the_rest_by_name() {
        assertThat(PomXml.mavenScope(Scope.MAIN)).isNull();
        assertThat(PomXml.mavenScope(Scope.EXPORT)).isNull();
        assertThat(PomXml.mavenScope(Scope.RUNTIME)).isEqualTo("runtime");
        assertThat(PomXml.mavenScope(Scope.PROVIDED)).isEqualTo("provided");
        assertThat(PomXml.mavenScope(Scope.TEST)).isEqualTo("test");
        // Never emitted as a plain <scope> (handled elsewhere, or dev-only).
        assertThat(PomXml.mavenScope(Scope.PLATFORM)).isNull();
        assertThat(PomXml.mavenScope(Scope.PROCESSOR)).isNull();
        assertThat(PomXml.mavenScope(Scope.DEV)).isNull();
        assertThat(PomXml.mavenScope(Scope.TEST_DEV)).isNull();
    }

    @Test
    void appendDependency_emits_scope_only_when_present_and_escapes_coords() {
        StringBuilder withScope = new StringBuilder();
        PomXml.appendDependency(withScope, "com.ex", "lib", "1.0", "runtime");
        assertThat(withScope.toString())
                .isEqualTo("    <dependency>\n"
                        + "      <groupId>com.ex</groupId>\n"
                        + "      <artifactId>lib</artifactId>\n"
                        + "      <version>1.0</version>\n"
                        + "      <scope>runtime</scope>\n"
                        + "    </dependency>\n");

        StringBuilder noScope = new StringBuilder();
        PomXml.appendDependency(noScope, "g&", "a", "1", null);
        assertThat(noScope.toString()).contains("<groupId>g&amp;</groupId>").doesNotContain("<scope>");
    }

    @Test
    void appendDependency_marks_an_optional_dependency_as_maven_does() {
        StringBuilder sb = new StringBuilder();
        Dependency optional = Dependency.of("mysql", "com.foo:mysql", VersionSelector.parse("=1.0"))
                .withOptional(true);
        PomXml.appendDependency(sb, optional, "1.0", null);
        assertThat(sb.toString()).contains("<optional>true</optional>");

        StringBuilder plain = new StringBuilder();
        PomXml.appendDependency(plain, optional.withOptional(false), "1.0", null);
        assertThat(plain.toString()).doesNotContain("<optional>");
    }

    @Test
    void appendDependencyManagement_is_a_noop_when_empty() {
        StringBuilder sb = new StringBuilder();
        PomXml.appendDependencyManagement(sb, List.of(), d -> "unused");
        assertThat(sb.toString()).isEmpty();
    }
}
