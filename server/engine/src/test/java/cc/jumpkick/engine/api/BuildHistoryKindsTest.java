// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.runtime.WorkspaceTarget;
import org.junit.jupiter.api.Test;

class BuildHistoryKindsTest {

    /** Every build-like goal journals as a build: a history row, a build number, exclusive admission. */
    @Test
    void every_goal_and_the_guard_run_are_builds() {
        for (WorkspaceTarget target : WorkspaceTarget.values()) {
            assertThat(BuildHistoryKinds.isBuildLike(target.jobKind()))
                    .as(target.name())
                    .isTrue();
        }
        assertThat(BuildHistoryKinds.isBuildLike(BuildHistoryKinds.GUARD)).isTrue();
        assertThat(BuildHistoryKinds.ALL)
                .contains("build", "test", "compile", "install", "native", "image", "publish", "train", "guard");
    }

    @Test
    void a_maven_run_driven_through_jk_mvn_is_a_build() {
        assertThat(BuildHistoryKinds.isBuildLike("mvn")).isTrue();
    }

    @Test
    void non_builds_are_not_history() {
        // Resolve / refresh / format / tooling — may run as engine plans, not project builds.
        for (String kind : new String[] {
            "lock",
            "update",
            "sync",
            "format",
            "audit",
            "import",
            "provision",
            "git-fetch",
            "script",
            "tool",
            "cache",
            "calibrate",
            null,
            "",
            "Build", // wire tokens are lowercase
        }) {
            assertThat(BuildHistoryKinds.isBuildLike(kind)).as("kind=%s", kind).isFalse();
        }
    }

    @Test
    void exclusive_kinds_match_build_history() {
        assertThat(BuildJobFingerprint.EXCLUSIVE_KINDS).isEqualTo(BuildHistoryKinds.ALL);
        for (String kind : BuildHistoryKinds.ALL) {
            assertThat(BuildJobFingerprint.isExclusiveKind(kind)).isTrue();
        }
        assertThat(BuildJobFingerprint.isExclusiveKind("lock")).isFalse();
        assertThat(BuildJobFingerprint.isExclusiveKind("format")).isFalse();
    }
}
