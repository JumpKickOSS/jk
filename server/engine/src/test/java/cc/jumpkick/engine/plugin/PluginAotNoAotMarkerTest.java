// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Read-time TTL on sticky {@code .noaot} markers (JK-1431): failures back off, but a marker
 * older than {@link PluginAot#NOAOT_RETRY_MILLIS} expires so the key gets a fresh training
 * attempt — the sweep-side expiry only ever runs from a successful sibling train.
 */
class PluginAotNoAotMarkerTest {

    @TempDir
    Path tmp;

    @Test
    void a_fresh_marker_blocks_training() throws IOException {
        Path cache = tmp.resolve("kotlinc-0123456789abcdef.aot");
        Files.createFile(PluginAot.noaotMarker(cache));

        assertThat(PluginAot.noAotBlocked(cache)).isTrue();
        assertThat(PluginAot.noaotMarker(cache)).exists(); // still backing off
    }

    @Test
    void a_marker_older_than_the_ttl_is_expired_and_removed_at_read_time() throws IOException {
        Path cache = tmp.resolve("kotlinc-0123456789abcdef.aot");
        Path marker = Files.createFile(PluginAot.noaotMarker(cache));
        Files.setLastModifiedTime(
                marker, FileTime.fromMillis(System.currentTimeMillis() - PluginAot.NOAOT_RETRY_MILLIS - 60_000));

        assertThat(PluginAot.noAotBlocked(cache)).isFalse(); // the key retrains
        assertThat(marker).doesNotExist(); // expired marker is gone, not consulted again
    }

    @Test
    void no_marker_means_not_blocked() {
        assertThat(PluginAot.noAotBlocked(tmp.resolve("kotlinc-0123456789abcdef.aot")))
                .isFalse();
    }
}
