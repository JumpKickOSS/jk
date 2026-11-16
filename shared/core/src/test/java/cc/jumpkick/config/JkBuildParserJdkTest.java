// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static cc.jumpkick.config.JkBuildParserFixtures.graal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ToolchainSpec;
import org.junit.jupiter.api.Test;

/**
 * {@code jdk} is a table, the one spelling of a project's JDK vendor and version (JK-74S9K):
 * {@code vendor} and {@code version} each optional, {@code version} a major, an exact build or a
 * floor within one major, {@code required} binding them; the string forms are retired.
 */
class JkBuildParserJdkTest {

    private static JkBuild parse(String jdkLine) {
        return JkBuildParser.parse(
                "group = \"com.example\"\nname = \"widget\"\nversion = \"1.0.0\"\n" + jdkLine + "\n");
    }

    @Test
    void a_vendor_and_major_is_a_suggestion() {
        JkBuild parsed = parse("jdk = { vendor = \"temurin\", version = \"25\" }");
        assertThat(parsed.project().jdk()).isEqualTo("temurin-25");
        assertThat(parsed.project().jdkMajor()).isEqualTo(25);
        assertThat(parsed.project().jdkSpec().requiredVendor()).isEmpty();
        // java falls back to the jdk major when not given explicitly.
        assertThat(parsed.project().javaRelease()).isEqualTo(25);
    }

    @Test
    void vendor_and_version_are_each_optional() {
        assertThat(parse("jdk = { vendor = \"corretto\" }").project().jdkSpec().suggestedVendor())
                .isEqualTo("corretto");
        assertThat(parse("jdk = { version = \"25\" }").project().jdkMajor()).isEqualTo(25);
        assertThat(parse("jdk = { version = 25 }").project().jdkMajor())
                .as("an integer major")
                .isEqualTo(25);
    }

    @Test
    void required_binds_the_vendor_and_the_version() {
        ToolchainSpec major = parse("jdk = { vendor = \"corretto\", version = \"21\", required = true }")
                .project()
                .jdkSpec();
        assertThat(major.requiredVendor()).isEqualTo("corretto");
        assertThat(major.suggestedVersion()).isEqualTo("21");
        ToolchainSpec exact = parse("jdk = { vendor = \"temurin\", version = \"25.0.4.1\", required = true }")
                .project()
                .jdkSpec();
        assertThat(exact.requiredVendor()).isEqualTo("temurin");
        assertThat(exact.requiredVersion()).isEqualTo("25.0.4.1");
        ToolchainSpec floor = parse("jdk = { version = \">=25.0.5\", required = true }")
                .project()
                .jdkSpec();
        assertThat(floor.requiredVendor()).isEmpty();
        assertThat(floor.suggestedVersion()).isEqualTo("25");
    }

    @Test
    void an_exact_build_without_required_is_a_suggestion() {
        ToolchainSpec spec = parse("jdk = { vendor = \"temurin\", version = \"25.0.3\" }")
                .project()
                .jdkSpec();
        assertThat(spec.suggestedVersion()).isEqualTo("25.0.3");
        assertThat(spec.requiredVersion()).isEmpty();
    }

    @Test
    void a_range_across_majors_is_refused_and_points_at_java() {
        for (String v : new String[] {">=21", ">=21, <27", "[21,27)"}) {
            assertThatThrownBy(() -> parse("jdk = { version = \"" + v + "\" }"))
                    .as(v)
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessageContaining("spans Java majors")
                    .hasMessageContaining("java = N");
        }
        assertThatThrownBy(() -> parse("jdk = { version = \"lts\" }"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must be a major");
    }

    @Test
    void an_unknown_key_is_refused() {
        assertThatThrownBy(() -> parse("jdk = { vendor = \"temurin\", pin = true }"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("not `pin`");
    }

    @Test
    void a_jdk_below_the_java_level_is_refused_and_above_is_allowed() {
        assertThatThrownBy(() -> parse("java = 25\njdk = { version = \"21\" }"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("below java = 25");
        assertThat(parse("java = 21\njdk = { version = \"25\" }").project().javaRelease())
                .isEqualTo(21);
    }

    @Test
    void java_as_a_table_is_refused_and_points_at_jdk() {
        assertThatThrownBy(() -> parse("java = { version = 25 }"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("plain integer")
                .hasMessageContaining("jdk = {");
    }

    @Test
    void the_string_forms_are_retired_with_the_table_to_write() {
        assertThatThrownBy(() -> parse("jdk = \"temurin-25\""))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("retired")
                .hasMessageContaining("jdk = { vendor = \"temurin\", version = \"25\" }");
        assertThatThrownBy(() -> parse("jdk = \"=temurin-25.0.4\""))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("jdk = { vendor = \"temurin\", version = \"25.0.4\", required = true }");
        assertThatThrownBy(() -> parse("jdk = 25"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("jdk = { version = \"25\" }");
        assertThatThrownBy(() -> parse("jdk-vendor = \"corretto\""))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("jdk-vendor")
                .hasMessageContaining("retired");
    }

    @Test
    void parses_graal_spec_variants() {
        assertThat(JkBuildParser.parse(graal("\"graalvm-25\"")).graal()).isEqualTo("graalvm-25");
        assertThat(JkBuildParser.parse(graal("25")).graal()).isEqualTo("25");
        assertThat(JkBuildParser.parse(graal("\"native\"")).graal()).isEqualTo("native");
        // [native] declared, graal key omitted → defaults to the "graalvm" spec.
        assertThat(JkBuildParser.parse(PROJECT + "\n[native]\n").graal()).isEqualTo("graalvm");
        // No [native] table at all → null.
        assertThat(JkBuildParser.parse(PROJECT).graal()).isNull();
    }

    @Test
    void accepts_a_graal_point_release_as_a_suggestion() {
        JkBuild parsed = JkBuildParser.parse(graal("\"graalvm-25.0.3\""));
        ToolchainSpec spec = parsed.nativeConfigOpt().orElseThrow().graalSpec();
        assertThat(spec.suggestedVendor()).isEqualTo("graalvm");
        assertThat(spec.suggestedVersion()).isEqualTo("25.0.3");
        assertThat(spec.requiredVersion()).isEmpty();
    }

    @Test
    void an_equals_graal_spec_is_required() {
        JkBuild parsed = JkBuildParser.parse(graal("\"=25.2.4-graalce\""));
        ToolchainSpec spec = parsed.nativeConfigOpt().orElseThrow().graalSpec();
        assertThat(spec.requiredVendor()).isEqualTo("graalce");
        assertThat(spec.requiredVersion()).isEqualTo("25.2.4");
    }
}
