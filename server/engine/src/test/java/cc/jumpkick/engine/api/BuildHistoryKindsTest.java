// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildHistoryKindsTest {

    @Test
    void build_like_product_verbs() {
        assertThat(BuildHistoryKinds.isBuildLike("build")).isTrue();
        assertThat(BuildHistoryKinds.isBuildLike("test")).isTrue();
        assertThat(BuildHistoryKinds.isBuildLike("compile")).isTrue();
        assertThat(BuildHistoryKinds.isBuildLike("native")).isTrue();
        assertThat(BuildHistoryKinds.isBuildLike("image")).isTrue();
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
            "publish",
            "import",
            "provision",
            "install",
            "git-fetch",
            "script",
            "tool",
            "cache",
            "optimize",
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
