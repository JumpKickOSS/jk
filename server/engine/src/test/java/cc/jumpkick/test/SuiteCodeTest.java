// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.BuildIdentity;
import org.junit.jupiter.api.Test;

class SuiteCodeTest {

    @Test
    void the_set_is_this_package_and_the_classes_named_for_tests_suites_and_junit() {
        assertThat(SuiteCode.contains("cc/jumpkick/test/ResultAggregator.class"))
                .isTrue();
        assertThat(SuiteCode.contains("cc/jumpkick/test/PullWorkerPool$Lease.class"))
                .isTrue();
        assertThat(SuiteCode.contains("cc/jumpkick/runtime/TestLaunch.class")).isTrue();
        assertThat(SuiteCode.contains("cc/jumpkick/runtime/PlannerTest.class")).isTrue();
        assertThat(SuiteCode.contains("cc/jumpkick/runtime/TagExcludedSuite.class"))
                .isTrue();
        assertThat(SuiteCode.contains("cc/jumpkick/runtime/base/SuiteSelection$1.class"))
                .isTrue();
        assertThat(SuiteCode.contains("cc/jumpkick/runtime/JUnitLineConflict.class"))
                .isTrue();
        assertThat(SuiteCode.contains("cc/jumpkick/config/TestSelection.class")).isTrue();
    }

    @Test
    void the_rest_of_the_engine_is_not_in_it() {
        assertThat(SuiteCode.contains("cc/jumpkick/runtime/PackagingKeys.class"))
                .isFalse();
        assertThat(SuiteCode.contains("cc/jumpkick/engine/http/mcp/McpReads.class"))
                .isFalse();
        assertThat(SuiteCode.contains("cc/jumpkick/runtime/HardwareProbe$JunitPlatformTimes.class"))
                .as("a nested class is its top-level class's")
                .isFalse();
        assertThat(SuiteCode.contains("cc/jumpkick/test/sets/junit4.txt")).isFalse();
        assertThat(SuiteCode.contains("org/junit/platform/launcher/TestPlan.class"))
                .isFalse();
        assertThat(SuiteCode.contains("META-INF/MANIFEST.MF")).isFalse();
    }

    @Test
    void its_key_version_follows_its_own_identity_not_the_engines() {
        try {
            BuildIdentity.overrideBuildIdForTests("engine000001");
            String one = SuiteCode.keyVersion();
            BuildIdentity.overrideBuildIdForTests("engine000002");
            assertThat(SuiteCode.keyVersion()).isEqualTo(one);
            SuiteCode.overrideIdForTests("suites000001");
            assertThat(SuiteCode.keyVersion()).isEqualTo(BuildIdentity.keyVersion("suites000001"));
        } finally {
            BuildIdentity.overrideBuildIdForTests(null);
            SuiteCode.overrideIdForTests(null);
        }
    }
}
