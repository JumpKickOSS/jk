// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The line a module hears when its suites are keyed on the whole environment names the fix for the
 * framework that read it, exactly as jk.toml spells it, and is never said to a module that already
 * scopes, declares or is strict.
 */
class EnvScopeHintTest {

    private static final String PROJECT = """
            group = "com.example"
            name = "shop"
            version = "1.0"
            """;

    private static final SuiteEnvReads.Record LISTED =
            new SuiteEnvReads.Record(Set.of("SPRING_PROFILES_ACTIVE"), true, false);

    @Test
    void a_spring_boot_module_hears_the_spring_scope() {
        JkBuild b = JkBuildParser.parse(PROJECT + """

                [spring-boot]
                version = "4.0.0"

                [dependencies]
                starter-webmvc = { group = "org.springframework.boot", name = "spring-boot-starter-webmvc" }
                """);
        EnvScopeHint hint = EnvScopeHint.of(b);
        assertThat(hint.scopeLine()).isEqualTo("[env] scope = [\"SPRING_*\", \"<YOURAPP>_*\"]");
        assertThat(hint.message(LISTED, SuiteEnvReads.Policy.of(b)))
                .startsWith("Spring Boot reads your whole environment")
                .contains("[env] scope = [\"SPRING_*\", \"<YOURAPP>_*\"]")
                .doesNotContain("\n");
    }

    @Test
    void each_framework_adds_the_prefixes_it_reads() {
        JkBuild b = JkBuildParser.parse(PROJECT + """

                [dependencies]
                rest = { group = "io.quarkus", name = "quarkus-rest", version = "3.20.0" }

                [test-dependencies]
                tc = { group = "org.testcontainers", name = "postgresql", version = "1.21.0" }
                """);
        EnvScopeHint hint = EnvScopeHint.of(b);
        assertThat(hint.frameworks()).containsExactly("Quarkus", "Testcontainers");
        assertThat(hint.scope()).containsExactly("QUARKUS_*", "TESTCONTAINERS_*", "DOCKER_*", "<YOURAPP>_*");
        assertThat(EnvScopeHint.of(JkBuildParser.parse(PROJECT)).scope()).containsExactly("<YOURAPP>_*");
    }

    @Test
    void a_module_that_scopes_declares_or_is_strict_hears_nothing_and_a_narrow_suite_neither() {
        String deps = """

                [dependencies]
                core = { group = "io.micronaut", name = "micronaut-http", version = "4.8.0" }
                """;
        JkBuild plain = JkBuildParser.parse(PROJECT + deps);
        assertThat(EnvScopeHint.of(plain).message(LISTED, SuiteEnvReads.Policy.of(plain)))
                .contains("Micronaut reads your whole environment")
                .contains("\"MICRONAUT_*\"");
        assertThat(EnvScopeHint.of(plain)
                        .message(new SuiteEnvReads.Record(Set.of("FOO"), false, false), SuiteEnvReads.Policy.of(plain)))
                .as("a suite that reads one variable by name is keyed on that one alone")
                .isNull();
        for (String env : new String[] {
            "[env]\nscope = [\"MICRONAUT_*\"]\n", "[env]\noptional = [\"DB_URL\"]\n", "[env]\ninherit = false\n"
        }) {
            JkBuild b = JkBuildParser.parse(PROJECT + "\n" + env + deps);
            assertThat(EnvScopeHint.of(b).message(LISTED, SuiteEnvReads.Policy.of(b)))
                    .as(env)
                    .isNull();
        }
    }
}
