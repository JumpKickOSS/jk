// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ToolchainPathTest {

    private static final String SEP = File.pathSeparator;
    /** Unrelated PATH neighbor under the fake home — not a toolchain bin. */
    private static final String LOCAL_BIN = "/home/u/.local/bin";

    private static final String NVM = "/home/u/.nvm/versions/node/v24/bin";
    private static final String OLD_JDK = "/home/u/.local/share/jk/jdks/old";
    private static final String NEW_JDK = "/home/u/.local/share/jk/jdks/new";
    private static final String JDK = "/home/u/.local/share/jk/jdks/temurin-25";
    private static final String GRAAL = "/home/u/.local/share/jk/jdks/graalvm-25";

    /** Host-normalized {@code home/bin} — same form {@link ToolchainPath#binOf} emits. */
    private static String bin(String home) {
        return Path.of(home).resolve("bin").toString();
    }

    @Test
    void swap_preserves_unrelated_entries_like_nvm() {
        String live = bin(OLD_JDK) + SEP + NVM + SEP + LOCAL_BIN;
        String next = ToolchainPath.swap(live, OLD_JDK, null, NEW_JDK, null);
        assertThat(next).isEqualTo(bin(NEW_JDK) + SEP + NVM + SEP + LOCAL_BIN);
    }

    @Test
    void swap_prepends_distinct_graal_bin_under_java() {
        String next = ToolchainPath.swap(LOCAL_BIN, null, null, JDK, GRAAL);
        assertThat(next).isEqualTo(bin(JDK) + SEP + bin(GRAAL) + SEP + LOCAL_BIN);
    }

    @Test
    void swap_with_same_java_and_graal_home_prepends_once() {
        String next = ToolchainPath.swap(LOCAL_BIN, null, null, GRAAL, GRAAL);
        assertThat(next).isEqualTo(bin(GRAAL) + SEP + LOCAL_BIN);
    }

    @Test
    void swap_null_targets_strips_live_toolchain_bins_only() {
        String live = bin(JDK) + SEP + "/home/u/.nvm/bin" + SEP + LOCAL_BIN;
        String next = ToolchainPath.swap(live, JDK, null, null, null);
        assertThat(next).isEqualTo("/home/u/.nvm/bin" + SEP + LOCAL_BIN);
    }

    @Test
    void swap_removes_unix_form_bin_when_home_normalizes_differently() {
        // Live PATH may still carry a foreign-separator bin (bash-on-Windows, fixtures) while
        // binOf emits the host Path form — removal must still match.
        String live = OLD_JDK + "/bin" + SEP + LOCAL_BIN;
        String next = ToolchainPath.swap(live, OLD_JDK, null, NEW_JDK, null);
        assertThat(next).isEqualTo(bin(NEW_JDK) + SEP + LOCAL_BIN);
        assertThat(next).doesNotContain(OLD_JDK + "/bin");
    }
}
