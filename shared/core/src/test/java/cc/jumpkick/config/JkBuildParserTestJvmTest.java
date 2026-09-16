// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.TestJvm;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code [test] jvm-args} and {@code [test] system-properties}: what the forked test JVM gets. */
class JkBuildParserTestJvmTest {

    @Test
    void jvm_args_and_system_properties_parse_in_manifest_order() {
        JkBuild build = JkBuildParser.parse(PROJECT + """
                [test]
                jvm-args = ["-Dprobe=1", "--add-opens", "java.base/java.lang=ALL-UNNAMED"]
                system-properties = { "spring.profiles.active" = "test", answer = 42, headless = true }
                """);
        TestJvm jvm = build.build().testJvm();
        assertThat(jvm.jvmArgs()).containsExactly("-Dprobe=1", "--add-opens", "java.base/java.lang=ALL-UNNAMED");
        assertThat(jvm.systemProperties())
                .containsExactly(
                        Map.entry("spring.profiles.active", "test"),
                        Map.entry("answer", "42"),
                        Map.entry("headless", "true"));
        assertThat(jvm.flags())
                .as("the command-line form: the args, then one -D per property")
                .containsExactly(
                        "-Dprobe=1",
                        "--add-opens",
                        "java.base/java.lang=ALL-UNNAMED",
                        "-Dspring.profiles.active=test",
                        "-Danswer=42",
                        "-Dheadless=true");
    }

    @Test
    void a_manifest_without_the_keys_has_an_empty_test_jvm() {
        assertThat(JkBuildParser.parse(PROJECT).build().testJvm()).isEqualTo(TestJvm.EMPTY);
        assertThat(JkBuildParser.parse(PROJECT + "[test]\nworkers = 1\n")
                        .build()
                        .testJvm()
                        .isEmpty())
                .isTrue();
    }

    @Test
    void a_non_string_flag_and_a_nested_property_are_refused() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "[test]\njvm-args = [1]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].jvm-args must be an array of JVM flags");
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "[test]\nsystem-properties = \"-Dk=v\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].system-properties must be a table");
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "[test]\nsystem-properties = { k = [1] }\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].system-properties.k must be a string, number or boolean");
    }
}
