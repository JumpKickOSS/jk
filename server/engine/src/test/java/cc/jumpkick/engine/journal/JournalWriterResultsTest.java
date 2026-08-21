// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JournalWriterResultsTest {

    @Test
    void latest_path_is_target_jk_results() {
        assertThat(JournalWriter.latestPath("/ws")).isEqualTo(Path.of("/ws/target/jk-results.md"));
        assertThat(JournalWriter.latestPath("")).isNull();
        assertThat(JournalWriter.latestPath(null)).isNull();
    }

    @Test
    void clean_and_cache_do_not_recreate_target() {
        assertThat(JournalWriter.writesProjectTarget("clean")).isFalse();
        assertThat(JournalWriter.writesProjectTarget("cache")).isFalse();
        assertThat(JournalWriter.writesProjectTarget("build")).isTrue();
        assertThat(JournalWriter.writesProjectTarget("install")).isTrue();
        assertThat(JournalWriter.writesProjectTarget("publish")).isTrue();
        assertThat(JournalWriter.writesProjectTarget("native")).isTrue();
        assertThat(JournalWriter.writesProjectTarget("format")).isTrue();
    }
}
