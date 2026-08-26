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

    /**
     * Same config, same bytes: the skip that makes a clean re-run free still works.
     *
     * <p>The {@code save()} is the contract, not ceremony. The store is write-behind since JK-1034 —
     * {@code record} is a map write and the whole index reaches disk once at the end of a run — so
     * what a later run sees is what the previous run flushed. That is the real worker lifecycle:
     * {@code CodeFormatter} saves beside {@code workers.close()}.
     */
    @Test
    void the_same_config_and_bytes_hit_across_runs(@TempDir Path tmp) {
        byte[] source = "class A {}\n".getBytes(StandardCharsets.UTF_8);
        Path root = tmp.resolve("format-stamps");
        FormatStampCache cache = new FormatStampCache(root, "config-digest");
        cache.record(cache.keyFor(source));
        cache.save();

        assertThat(new FormatStampCache(root, "config-digest").contains(cache.keyFor(source)))
                .isTrue();
    }

    /** A run that records and never flushes leaves nothing behind — one write, or none. */
    @Test
    void an_unflushed_run_persists_nothing(@TempDir Path tmp) {
        byte[] source = "class A {}\n".getBytes(StandardCharsets.UTF_8);
        Path root = tmp.resolve("format-stamps");
        FormatStampCache cache = new FormatStampCache(root, "config-digest");
        cache.record(cache.keyFor(source));

        assertThat(new FormatStampCache(root, "config-digest").contains(cache.keyFor(source)))
                .as("record is a map write; only save() reaches disk")
                .isFalse();
    }

    /** The whole point: N stamps cost one file, not N files in N directories. */
    @Test
    void the_store_is_one_file_per_configuration(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("format-stamps");
        FormatStampCache cache = new FormatStampCache(root, "config-digest");
        for (int i = 0; i < 500; i++) {
            cache.record(cache.keyFor(("class A" + i + " {}\n").getBytes(StandardCharsets.UTF_8)));
        }
        cache.save();

        try (var walk = java.nio.file.Files.walk(root)) {
            var files = walk.filter(java.nio.file.Files::isRegularFile).toList();
            assertThat(files).as("500 stamps, one index file").hasSize(1);
        }
        try (var walk = java.nio.file.Files.walk(root)) {
            assertThat(walk.filter(java.nio.file.Files::isDirectory).toList())
                    .as("and no shard directories")
                    .hasSize(1);
        }
        assertThat(new FormatStampCache(root, "config-digest").size()).isEqualTo(500);
    }

    /** The old sharded layout cleans itself up on load, so no `jk cache nuke` is needed. */
    @Test
    void loading_sweeps_the_old_sharded_layout(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("format-stamps");
        // A stamp the previous layout would have written: <aa>/<bb>/<60-hex>, zero bytes.
        Path stale = root.resolve("ab").resolve("cd").resolve("e".repeat(60));
        java.nio.file.Files.createDirectories(stale.getParent());
        java.nio.file.Files.writeString(stale, "");
        // And a sibling configuration's index, which must survive.
        FormatStampCache other = new FormatStampCache(root, "other-config");
        other.record(other.keyFor("class B {}\n".getBytes(StandardCharsets.UTF_8)));
        other.save();

        new FormatStampCache(root, "config-digest");

        assertThat(java.nio.file.Files.exists(stale)).as("the sharded tree is gone").isFalse();
        assertThat(java.nio.file.Files.exists(root.resolve("ab"))).isFalse();
        assertThat(java.nio.file.Files.exists(root.resolve("other-config.keys")))
                .as("another configuration's index is not collateral")
                .isTrue();
    }
}
