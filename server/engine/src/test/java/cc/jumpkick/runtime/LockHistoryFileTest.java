// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.JkDirs;
import org.junit.jupiter.api.Test;

/** The kept lock's file is named by the project id, which must never escape the history directory. */
class LockHistoryFileTest {

    @Test
    void a_path_like_id_names_no_file() {
        assertThatThrownBy(() -> LockHistory.file("../../config")).isInstanceOf(IllegalArgumentException.class);
        assertThat(LockHistory.read("../../config")).isNull();
    }

    @Test
    void a_valid_id_stays_inside_the_history_directory() {
        assertThat(LockHistory.file("ABC123").getFileName().toString()).isEqualTo("abc123.toml");
        assertThat(LockHistory.file("abc123")).hasParentRaw(JkDirs.state().resolve("lock-history"));
    }
}
