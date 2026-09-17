// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkBuildParserFrameworkTest {

    @Test
    void the_bom_a_framework_table_implies_carries_the_table_as_its_provenance() {
        JkBuild b = JkBuildParser.parse("""
                group = "com.example"
                name = "api"
                version = "1.0"

                [quarkus]
                version = "3.39.2"

                [platform-dependencies]
                junit-bom = { group = "org.junit", version = "6.1.3" }
                """);
        var platform = b.dependencies().of(Scope.PLATFORM);
        assertThat(platform).hasSize(2);
        assertThat(platform.get(0).module()).isEqualTo("org.junit:junit-bom");
        assertThat(platform.get(0).impliedBy())
                .as("a declared BOM has no implying table")
                .isNull();
        assertThat(platform.get(1).module()).isEqualTo("io.quarkus.platform:quarkus-bom");
        assertThat(platform.get(1).version().raw()).isEqualTo("3.39.2");
        assertThat(platform.get(1).impliedBy()).isEqualTo("quarkus");
    }

    @Test
    void spring_boot_table_parses_and_auto_imports_the_bom() {
        JkBuild b = JkBuildParser.parse("""
                group = "com.example"
                name = "shop"
                version = "1.0"

                [spring-boot]
                version = "4.0.0"

                [dependencies]
                starter-webmvc = { group = "org.springframework.boot", name = "spring-boot-starter-webmvc" }
                """);
        assertThat(b.isSpringBoot()).isTrue();
        var sb = b.pluginConfig(JkBuild.SPRING_BOOT_ID).orElseThrow();
        assertThat(sb.string("version")).isEqualTo("4.0.0");
        assertThat(sb.bool("include-tools", true)).isTrue();
        assertThat(sb.bool("aot")).isEmpty(); // unset aot = tri-state auto (follows [native] presence)
        // version = "4.0.0" alone imports the BOM — no [platform-dependencies] boilerplate.
        var platform = b.dependencies().of(Scope.PLATFORM);
        assertThat(platform).hasSize(1);
        assertThat(platform.get(0).module()).isEqualTo("org.springframework.boot:spring-boot-dependencies");
        assertThat(platform.get(0).version().raw()).isEqualTo("4.0.0");
        assertThat(platform.get(0).version()).isInstanceOf(VersionSelector.Exact.class);
        // ...which makes the versionless starter platform-managed.
        assertThat(b.dependencies().of(Scope.MAIN).get(0).isPlatformManaged()).isTrue();
    }

    @Test
    void spring_boot_table_requires_a_version() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [spring-boot]
                include-tools = false
                """))
                .hasMessageContaining("[spring-boot].version is required");
    }

    @Test
    void spring_boot_options_parse() {
        JkBuild b = JkBuildParser.parse(PROJECT + """

                [spring-boot]
                version = "4.0.0"
                aot = true
                include-tools = false
                aot-args = ["--spring.profiles.active=prod"]
                """);
        var sb = b.pluginConfig(JkBuild.SPRING_BOOT_ID).orElseThrow();
        assertThat(sb.bool("aot")).contains(true); // explicit aot wins over [native] absence
        assertThat(sb.bool("include-tools", true)).isFalse();
        assertThat(sb.stringList("aot-args")).containsExactly("--spring.profiles.active=prod");
    }

    @Test
    void spring_boot_bom_is_not_duplicated_when_user_declares_it() {
        // A deliberate [platform-dependencies] spring-boot-dependencies entry wins
        // the auto-import must not add a second (conflicting) BOM row.
        JkBuild b = JkBuildParser.parse(PROJECT + """

                [spring-boot]
                version = "4.0.0"

                [platform-dependencies]
                spring-boot = { group = "org.springframework.boot", name = "spring-boot-dependencies", version = "4.0.1" }
                """);
        var platform = b.dependencies().of(Scope.PLATFORM);
        assertThat(platform).hasSize(1);
        assertThat(platform.get(0).version().raw()).isEqualTo("4.0.1");
    }

    /**
     * the parse memo must hold one entry per file. Keying it by (path, size, mtime) made
     * every save of a jk.toml strand the previous JkBuild for the process's lifetime.
     *
     * <p>The memo is process-wide, so other tests may already hold entries — assert rewrites do not
     * grow the cache, not that size is exactly 1.
     */
    @Test
    void parse_memo_replaces_the_entry_for_a_rewritten_file(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("jk.toml");
        Files.writeString(file, PROJECT);
        JkBuild first = JkBuildParser.parseLocal(file);
        assertThat(JkBuildParser.parseLocal(file)).isSameAs(first); // warm hit
        int sizeAfterFirst = JkBuildParser.parseCacheSizeForTest();

        for (int i = 0; i < 20; i++) {
            Files.writeString(file, PROJECT + "\n# edit " + i + "\n");
            JkBuildParser.parseLocal(file);
        }
        assertThat(JkBuildParser.parseCacheSizeForTest())
                .as("one entry per file, not one per revision")
                .isEqualTo(sizeAfterFirst);
    }

    @Test
    void parse_memo_does_not_reuse_a_same_length_rewrite(@TempDir Path tmp) throws Exception {
        // Size+mtime stamps miss this on Windows: same length, same tick, different bytes.
        Path file = tmp.resolve("jk.toml");
        Files.writeString(file, PROJECT + "description = \"a\"\n");
        JkBuild first = JkBuildParser.parseLocal(file);
        Files.writeString(file, PROJECT + "description = \"b\"\n");
        JkBuild second = JkBuildParser.parseLocal(file);
        assertThat(second).isNotSameAs(first);
        assertThat(second.project().description()).isEqualTo("b");
    }

    @Test
    void micronaut_table_imports_platform_bom_with_caret_version() {
        var b = JkBuildParser.parse(PROJECT + """
                [micronaut]
                version = "^5"

                [dependencies]
                micronaut-http-server-netty = { group = "io.micronaut", name = "micronaut-http-server-netty" }
                """);
        assertThat(b.isMicronaut()).isTrue();
        var platform = b.dependencies().of(Scope.PLATFORM);
        assertThat(platform).hasSize(1);
        assertThat(platform.get(0).module()).isEqualTo("io.micronaut.platform:micronaut-platform");
        assertThat(platform.get(0).version().raw()).isEqualTo("^5");
        assertThat(platform.get(0).version()).isInstanceOf(VersionSelector.Caret.class);
        assertThat(b.dependencies().of(Scope.MAIN).get(0).isPlatformManaged()).isTrue();
    }
}
