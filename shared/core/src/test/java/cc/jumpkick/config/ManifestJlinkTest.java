// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Jlink;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** {@code [jlink]}: the runtime image a module packages. */
class ManifestJlinkTest {

    private static JkBuild parse(String tables) {
        return JkBuildParser.parse(PROJECT + "\n[application]\nmain = \"com.example.Main\"\n" + tables);
    }

    private static Jlink jlink(String keys) {
        return Objects.requireNonNull(parse("\n[jlink]\n" + keys).build().jlink());
    }

    @Test
    void absent_means_no_image() {
        assertThat(parse("").build().jlink()).isNull();
    }

    @Test
    void the_table_alone_is_every_default() {
        assertThat(jlink("")).isEqualTo(Jlink.defaults());
        assertThat(Jlink.defaults().aotCache()).isEqualTo(Jlink.AotCache.AUTO);
        assertThat(Jlink.defaults().stripDebug()).isTrue();
        assertThat(Jlink.defaults().testStart()).isTrue();
    }

    @Test
    void every_key_is_read() {
        Jlink j = jlink("""
                modules       = ["jdk.crypto.ec", "jdk.localedata"]
                strip-debug   = false
                aot-cache     = "cds"
                jvm-args      = ["-XX:+UseSerialGC", "-Xmx256m"]
                args          = ["--port", "9090"]
                start-timeout = 120

                [jlink.training-env]
                SERVER_PORT = "0"
                """);
        assertThat(j.modules()).containsExactly("jdk.crypto.ec", "jdk.localedata");
        assertThat(j.stripDebug()).isFalse();
        assertThat(j.aotCache()).isEqualTo(Jlink.AotCache.CDS);
        assertThat(j.jvmArgs()).containsExactly("-XX:+UseSerialGC", "-Xmx256m");
        assertThat(j.args()).isEqualTo(List.of("--port", "9090"));
        assertThat(j.startTimeoutSeconds()).isEqualTo(120);
        assertThat(j.trainingEnv()).isEqualTo(Map.of("SERVER_PORT", "0"));
    }

    @Test
    void aot_cache_is_a_boolean_or_cds() {
        assertThat(jlink("aot-cache = true\n").aotCache()).isEqualTo(Jlink.AotCache.AUTO);
        assertThat(jlink("aot-cache = false\n").aotCache()).isEqualTo(Jlink.AotCache.OFF);
        assertThatThrownBy(() -> jlink("aot-cache = \"leyden\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("aot-cache");
    }

    @Test
    void skipping_the_start_needs_the_cache_off_too() {
        assertThatThrownBy(() -> jlink("test-start = false\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("aot-cache = false");
        assertThat(jlink("test-start = false\naot-cache = false\n").testStart()).isFalse();
    }

    @Test
    void bad_values_are_refused_by_name() {
        assertThatThrownBy(() -> jlink("modules = [\"not a module\"]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("modules");
        assertThatThrownBy(() -> jlink("start-timeout = 0\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("start-timeout");
        assertThatThrownBy(() -> jlink("strip = true\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown key `strip`");
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "\njlink = true\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[jlink]");
    }
}
