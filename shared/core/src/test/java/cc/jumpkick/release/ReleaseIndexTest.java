// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The documents a final run writes: SHA256SUMS, index.json and latest.json, each a function of its entries. */
class ReleaseIndexTest {

    private static final String A = "a".repeat(64);
    private static final String B = "B".repeat(64);

    private static final List<ReleaseIndex.Entry> ENTRIES = List.of(
            new ReleaseIndex.Entry("app-1.0-macos-aarch64.tar.gz", "app", "macos-aarch64", 7, B, "s3://r/1.0/m"),
            new ReleaseIndex.Entry("app-1.0-all.jar", "app", null, 42, A, null));

    @Test
    void sha256sums_is_sha256sum_format_sorted_by_name() {
        assertThat(new String(ReleaseIndex.sha256sums(ENTRIES), StandardCharsets.UTF_8))
                .isEqualTo(A + "  app-1.0-all.jar\n" + "b".repeat(64) + "  app-1.0-macos-aarch64.tar.gz\n");
    }

    @Test
    void the_index_lists_every_file_with_os_and_arch_only_on_platform_files() {
        assertThat(new String(ReleaseIndex.index("app", "1.0", "v1.0", ENTRIES), StandardCharsets.UTF_8))
                .isEqualTo("{\"name\":\"app\",\"version\":\"1.0\",\"tag\":\"v1.0\",\"files\":["
                        + "{\"name\":\"app-1.0-all.jar\",\"module\":\"app\",\"size\":42,\"sha256\":\"" + A + "\"},"
                        + "{\"name\":\"app-1.0-macos-aarch64.tar.gz\",\"module\":\"app\",\"os\":\"macos\","
                        + "\"arch\":\"aarch64\",\"size\":7,\"sha256\":\"" + "b".repeat(64)
                        + "\",\"url\":\"s3://r/1.0/m\"}]}\n");
    }

    @Test
    void latest_names_the_version_and_its_index() {
        byte[] latest = ReleaseIndex.latest("1.0");
        assertThat(new String(latest, StandardCharsets.UTF_8))
                .isEqualTo("{\"version\":\"1.0\",\"index\":\"1.0/index.json\"}\n");
        assertThat(ReleaseIndex.latestVersion(latest)).isEqualTo("1.0");
    }
}
