// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobKind;
import org.junit.jupiter.api.Test;

/**
 * The toolchain verbs act on the machine and not on the project's sources: like the maintenance
 * kinds they leave no {@code target/jk-profile.json} behind, while still counting as live plans.
 */
class ToolchainVerbsTest {

    @Test
    void provisioning_and_tool_resolution_write_no_timeline_and_still_join_the_live_plans() {
        for (JobKind kind : new JobKind[] {
            new ProvisionVerb(new InertVerbHost()).jobKind(), new ToolResolveVerb(new InertVerbHost()).jobKind()
        }) {
            assertThat(kind.writesTimeline()).as(kind.verb()).isFalse();
            assertThat(kind.joinsActivePlans()).as(kind.verb()).isTrue();
            assertThat(kind.workspaceTerminal()).as(kind.verb()).isFalse();
        }
    }
}
