// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static cc.jumpkick.config.JkBuildParserFixtures.graal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
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
    void rejects_graal_point_release() {
        assertThatThrownBy(() -> JkBuildParser.parse(graal("\"graalvm-25.0.3\"")))
                .hasMessageContaining("[native].graal");
    }

    @Test
    void accepts_unquoted_integer_jdk_as_bare_major() {
        // Back-compat: the old integer form coerces to a bare-major string.
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = 25
                """);
        assertThat(parsed.project().jdk()).isEqualTo("25");
    }

    @Test
    void rejects_jdk_point_release() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "25.0.3"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("point release");
    }

    @Test
    void rejects_jdk_vendor_point_release() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "temurin-25.0.3"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("point release");
    }
}
