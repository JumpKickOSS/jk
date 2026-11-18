// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.ProjectInfo;
import org.junit.jupiter.api.Test;

/**
 * {@code jk explain}'s line naming the members {@code [publish] skip} keeps out of a release, read
 * off the summary the engine sends, and the wire that carries it.
 */
class ReleaseLineSkippedTest {

    private static ProjectInfo summary(String fields) {
        return ProjectInfo.decode("{\"type\":\"project-info-ack\",\"error\":null,\"name\":\"ws\"" + fields + "}");
    }

    @Test
    void a_workspace_with_skipped_members_names_them() {
        ProjectInfo info = summary(",\"publishSkipped\":[\"corpus-checks\",\"jmh\",\"site\"]");
        assertThat(info.publishSkipped()).containsExactly("corpus-checks", "jmh", "site");
        assertThat(ProjectInfo.decode(info.encode()).publishSkipped()).containsExactly("corpus-checks", "jmh", "site");
        assertThat(ReleaseLine.skippedLine(info))
                .isEqualTo("jk publish and jk install leave out 3 modules ([publish] skip): corpus-checks, jmh, site;"
                        + " every other module with a jar ships");
    }

    @Test
    void a_skipped_module_on_its_own_names_itself_and_none_skipped_says_nothing() {
        ProjectInfo self = summary(",\"publishSkip\":true");
        assertThat(self.publishSkip()).isTrue();
        assertThat(ReleaseLine.skippedLine(self))
                .startsWith("jk publish and jk install leave out 1 module ([publish] skip): ws;");
        ProjectInfo none = summary("");
        assertThat(none.publishSkip()).isFalse();
        assertThat(ReleaseLine.skippedLine(none)).isNull();
        assertThat(none.encode()).as("an unset field stays off the wire").doesNotContain("publishSkip");
    }
}
