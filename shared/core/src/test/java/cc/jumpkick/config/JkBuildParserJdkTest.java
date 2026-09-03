// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static cc.jumpkick.config.JkBuildParserFixtures.graal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ToolchainSpec;
import org.junit.jupiter.api.Test;

class JkBuildParserJdkTest {

    @Test
    void parses_jdk_vendor_major_spec() {
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "temurin-25"
                """);
        assertThat(parsed.project().jdk()).isEqualTo("temurin-25");
        assertThat(parsed.project().jdkMajor()).isEqualTo(25);
        // java falls back to the jdk major when not given explicitly.
        assertThat(parsed.project().javaRelease()).isEqualTo(25);
    }

    @Test
    void parses_jdk_keyword_specs() {
        // lts/stable/latest/native are accepted as-is (the same keywords --jdk/JK_JDK/
        // .jdk-version accept) — resolved downstream by JdkKeywords, not by this parser.
        for (String keyword : new String[] {"lts", "stable", "latest", "native"}) {
            JkBuild parsed = JkBuildParser.parse("""
                    group    = "com.example"
                    name     = "widget"
                    version  = "1.0.0"
                    jdk      = "%s"
                    """.formatted(keyword));
            assertThat(parsed.project().jdk()).isEqualTo(keyword);
        }
    }

    @Test
    void parses_jdk_bare_major_string() {
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "25"
                """);
        assertThat(parsed.project().jdk()).isEqualTo("25");
        assertThat(parsed.project().jdkMajor()).isEqualTo(25);
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
        ToolchainSpec spec = parsed.nativeConfig().orElseThrow().graalSpec();
        assertThat(spec.suggestedVendor()).isEqualTo("graalvm");
        assertThat(spec.suggestedVersion()).isEqualTo("25.0.3");
        assertThat(spec.requiredVersion()).isEmpty();
    }

    @Test
    void an_equals_graal_spec_is_required() {
        JkBuild parsed = JkBuildParser.parse(graal("\"=25.2.4-graalce\""));
        ToolchainSpec spec = parsed.nativeConfig().orElseThrow().graalSpec();
        assertThat(spec.requiredVendor()).isEqualTo("graalce");
        assertThat(spec.requiredVersion()).isEqualTo("25.2.4");
    }

    @Test
    void accepts_unquoted_integer_jdk_as_bare_major() {
        // Unquoted integer {@code jdk} coerces to a bare-major string.
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = 25
                """);
        assertThat(parsed.project().jdk()).isEqualTo("25");
    }

    @Test
    void accepts_a_jdk_point_release_as_a_floor_on_the_major() {
        // A bare point release records what built the lock. It is not a pin: the major is the
        // floor, so 25.0.1 and 26.x both clear it. Pinning takes an = (jdk = "=25.0.3").
        ToolchainSpec spec = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "25.0.3"
                """).project().jdkSpec();
        assertThat(spec.suggestedVersion()).isEqualTo("25.0.3");
        assertThat(spec.requiredVersion()).isEmpty();
    }

    @Test
    void an_equals_jdk_spec_requires_the_vendor_and_the_version() {
        ToolchainSpec spec = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "=temurin-25.0.4"
                """).project().jdkSpec();
        assertThat(spec.requiredVendor()).isEqualTo("temurin");
        assertThat(spec.requiredVersion()).isEqualTo("25.0.4");
        assertThat(spec.suggestedVendor()).isEmpty();
        assertThat(spec.suggestedVersion()).isEmpty();
    }

    @Test
    void the_vendor_and_version_keys_carry_requiredness_separately() {
        ToolchainSpec spec = JkBuildParser.parse("""
                group       = "com.example"
                name        = "widget"
                version     = "1.0.0"
                jdk-vendor  = "=Corretto"
                jdk-version = 25
                """).project().jdkSpec();
        assertThat(spec.requiredVendor()).isEqualTo("corretto");
        assertThat(spec.suggestedVersion()).isEqualTo("25");
        assertThat(spec.requiredVersion()).isEmpty();
    }

    @Test
    void naming_both_the_combined_key_and_the_pair_is_an_error() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group       = "com.example"
                name        = "widget"
                version     = "1.0.0"
                jdk         = "temurin-25"
                jdk-version = 25
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("both set");
    }

    @Test
    void an_equals_bare_major_with_no_vendor_pins_nothing() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "=25"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("pins nothing");
    }

    @Test
    void a_vendor_and_point_release_reduces_to_a_bare_major_for_the_resolver() {
        // `jdk` stays the resolver-facing projection: vendor plus major, no patch.
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "temurin-25.0.3"
                """);
        assertThat(parsed.project().jdk()).isEqualTo("temurin-25");
        assertThat(parsed.project().jdkSpec().suggestedVersion()).isEqualTo("25.0.3");
    }
}
