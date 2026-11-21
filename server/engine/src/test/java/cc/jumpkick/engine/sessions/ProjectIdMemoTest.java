// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A resident engine remembers the ids of the project directories it asked for last, not of every one. */
class ProjectIdMemoTest {

    @Test
    void the_directories_asked_for_last_are_remembered(@TempDir Path root) {
        ProjectIdMemo memo = new ProjectIdMemo();
        for (int i = 0; i < ProjectIdMemo.KEPT + 10; i++) {
            assertThat(memo.idOf(root.resolve("p" + i).toString()))
                    .as("no manifest, no id")
                    .isNull();
        }

        assertThat(memo.size()).isEqualTo(ProjectIdMemo.KEPT);
        assertThat(memo.knows(root.resolve("p0").toString())).isFalse();
        assertThat(memo.knows(root.resolve("p" + (ProjectIdMemo.KEPT + 9)).toString()))
                .isTrue();
    }
}
