// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import org.junit.jupiter.api.Test;

class ToolchainPathTest {

    private static final String SEP = File.pathSeparator;

    @Test
    void swap_preserves_unrelated_entries_like_nvm() {
        String live = "/old-jdk/bin" + SEP + "/home/u/.nvm/versions/node/v24/bin" + SEP + "/usr/bin";
        String next = ToolchainPath.swap(live, "/old-jdk", null, "/new-jdk", null);
        assertThat(next).isEqualTo("/new-jdk/bin" + SEP + "/home/u/.nvm/versions/node/v24/bin" + SEP + "/usr/bin");
    }

    @Test
    void swap_prepends_distinct_graal_bin_under_java() {
        String live = "/usr/bin";
        String next = ToolchainPath.swap(live, null, null, "/jdk", "/graal");
        assertThat(next).isEqualTo("/jdk/bin" + SEP + "/graal/bin" + SEP + "/usr/bin");
    }

    @Test
    void swap_with_same_java_and_graal_home_prepends_once() {
        String next = ToolchainPath.swap("/usr/bin", null, null, "/graal", "/graal");
        assertThat(next).isEqualTo("/graal/bin" + SEP + "/usr/bin");
    }

    @Test
    void swap_null_targets_strips_live_toolchain_bins_only() {
        String live = "/jdk/bin" + SEP + "/home/u/.nvm/bin" + SEP + "/usr/bin";
        String next = ToolchainPath.swap(live, "/jdk", null, null, null);
        assertThat(next).isEqualTo("/home/u/.nvm/bin" + SEP + "/usr/bin");
    }
}
