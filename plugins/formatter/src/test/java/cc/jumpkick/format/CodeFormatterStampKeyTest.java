// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The worker half of the format cache key. The host owns the whole configuration and sends one
 * digest ({@code FormatKey}); the worker derives nothing of its own — the second, independently
 * hand-maintained derivation that used to live here is exactly how the ktfmt width and the
 * remove-unused-imports google-java-format version came to key neither store.
 */
class CodeFormatterStampKeyTest {

    @Test
    void the_spec_field_the_host_writes_is_the_one_the_worker_reads(@TempDir Path tmp) throws Exception {
        Path spec = tmp.resolve("fmt.spec");
        Files.write(
                spec,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMMAND, "format", "jk-formatter")
                        .configString("cacheDir", tmp.resolve("cache").toString())
                        .configString("configKey", "deadbeefcafe")
                        .lines(),
                StandardCharsets.UTF_8);

        CodeFormatter.Spec parsed = CodeFormatter.Spec.from(PluginSpec.read(spec));

        assertThat(parsed.configKey).isEqualTo("deadbeefcafe");
        assertThat(parsed.cacheDir).isEqualTo(tmp.resolve("cache"));
    }

    /**
     * A different config digest is a different stamp for identical bytes — the mechanism by which
     * bumping the Kotlin width (or google-java-format) actually re-formats instead of hitting a
     * stamp written under the old configuration.
     */
    @Test
    void a_stamp_written_under_one_config_is_not_a_hit_under_another(@TempDir Path tmp) {
        byte[] source = "fun main() { println(\"hi\") }\n".getBytes(StandardCharsets.UTF_8);
        Path root = tmp.resolve("format-stamps");
        FormatStampCache width120 = new FormatStampCache(root, "config-digest-width-120");
        FormatStampCache width100 = new FormatStampCache(root, "config-digest-width-100");

        String stamped = width120.keyFor(source);
        width120.record(stamped);

        assertThat(width120.contains(stamped)).isTrue();
        assertThat(width100.keyFor(source)).isNotEqualTo(stamped);
        assertThat(width100.contains(width100.keyFor(source)))
                .as("the old config's stamp must not satisfy the new config")
                .isFalse();
    }

    /** Same config, same bytes: the skip that makes a clean re-run free still works. */
    @Test
    void the_same_config_and_bytes_hit(@TempDir Path tmp) {
        byte[] source = "class A {}\n".getBytes(StandardCharsets.UTF_8);
        Path root = tmp.resolve("format-stamps");
        FormatStampCache cache = new FormatStampCache(root, "config-digest");
        cache.record(cache.keyFor(source));

        assertThat(new FormatStampCache(root, "config-digest").contains(cache.keyFor(source)))
                .isTrue();
    }
}
