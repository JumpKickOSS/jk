// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * {@code effective-aot.properties} is the file you read to answer "what config did AOT
 * actually run with?", and it is a declared step output. Two identical runs must produce identical
 * bytes.
 */
class MicronautAotPropertiesTest {

    @Test
    void two_renders_of_the_same_config_are_byte_identical() {
        assertThat(MicronautPlugin.renderProperties(props("b", "2", "a", "1")))
                .isEqualTo(MicronautPlugin.renderProperties(props("a", "1", "b", "2")));
    }

    @Test
    void keys_are_sorted_and_there_is_no_date_comment() {
        String rendered = MicronautPlugin.renderProperties(props("zeta", "1", "alpha", "2", "mid", "3"));

        assertThat(rendered).isEqualTo("""
                        # Effective Micronaut AOT configuration (jk)
                        alpha=2
                        mid=3
                        zeta=1
                        """);
    }

    @Test
    void values_needing_escapes_round_trip_through_properties_load() throws Exception {
        Properties original = props(
                "plain", "true",
                "with.equals", "a=b",
                "with.colon", "http://example.com",
                "with spaces", "  leading and trailing  ",
                "with.newline", "line1\nline2",
                "with.backslash", "C:\\path\\to",
                "with.hash", "#not-a-comment",
                "with.unicode", "café — ☕");

        Properties reloaded = new Properties();
        reloaded.load(new StringReader(MicronautPlugin.renderProperties(original)));

        assertThat(reloaded).isEqualTo(original);
    }

    @Test
    void the_native_defaults_do_not_bake_environment_state() {
        var nativeDefaults = MicronautPlugin.defaultsFor(MicronautPlugin.RUNTIME_NATIVE);

        // These resolve state at build time; under native that state lands in the image heap and
        // native-image refuses it (Inet4Address was the observed casualty).
        assertThat(nativeDefaults)
                .containsEntry("cached.environment.enabled", "false")
                .containsEntry("deduce.environment.enabled", "false")
                .containsEntry("precompute.environment.properties.enabled", "false");
    }

    @Test
    void the_jit_defaults_keep_the_startup_optimizers() {
        assertThat(MicronautPlugin.defaultsFor(MicronautPlugin.RUNTIME_JIT))
                .containsEntry("cached.environment.enabled", "true")
                .containsEntry("deduce.environment.enabled", "true")
                .containsEntry("precompute.environment.properties.enabled", "true");
    }

    @Test
    void service_loading_follows_the_runtime() {
        assertThat(MicronautPlugin.defaultsFor(MicronautPlugin.RUNTIME_NATIVE))
                .containsEntry("serviceloading.native.enabled", "true")
                .containsEntry("serviceloading.jit.enabled", "false");
        assertThat(MicronautPlugin.defaultsFor(MicronautPlugin.RUNTIME_JIT))
                .containsEntry("serviceloading.jit.enabled", "true")
                .containsEntry("serviceloading.native.enabled", "false");
    }

    @Test
    void source_translation_is_on_for_both() {
        for (String runtime : new String[] {MicronautPlugin.RUNTIME_JIT, MicronautPlugin.RUNTIME_NATIVE}) {
            assertThat(MicronautPlugin.defaultsFor(runtime))
                    .as(runtime)
                    .containsEntry("logback.xml.to.java.enabled", "true")
                    .containsEntry("yaml.to.java.config.enabled", "true");
        }
    }

    @Test
    void the_two_runtimes_declare_the_same_keys() {
        assertThat(MicronautPlugin.defaultsFor(MicronautPlugin.RUNTIME_NATIVE).keySet())
                .isEqualTo(
                        MicronautPlugin.defaultsFor(MicronautPlugin.RUNTIME_JIT).keySet());
    }

    private static Properties props(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) p.setProperty(keyValues[i], keyValues[i + 1]);
        return p;
    }
}
