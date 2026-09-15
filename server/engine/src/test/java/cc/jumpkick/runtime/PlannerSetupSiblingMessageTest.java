// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.base.SiblingArtifacts;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What a step says when a sibling's output it reads is absent: the sibling's failing step when the
 * schedule saw it fail, "not built" otherwise.
 */
class PlannerSetupSiblingMessageTest {

    private static final Path HOST = Path.of("/ws/shared/host");
    private static final Path GUARD = Path.of("/ws/server/guard");
    private static final String LABEL =
            "cc.jumpkick:jk-host fixtures (expected at /ws/target/shared/host/test-fixtures/classes)";

    @Test
    void a_sibling_that_failed_this_build_is_named_with_its_failing_step() {
        SiblingArtifacts siblings =
                new SiblingArtifacts(Map.of(HOST, Set.of(), GUARD, Set.of(HOST)), List.of(HOST, GUARD));
        siblings.failed("cc.jumpkick:jk-host", "compile-test-fixtures");

        assertThat(PlannerSetup.missingSiblingMessage("test sibling not built — ", LABEL, siblings.gateFor(GUARD)))
                .isEqualTo("test sibling cc.jumpkick:jk-host failed at compile-test-fixtures"
                        + " — fixtures (expected at /ws/target/shared/host/test-fixtures/classes)");
        assertThat(PlannerSetup.missingSiblingMessage(
                        "sibling not built — ",
                        "cc.jumpkick:jk-host (expected at /ws/target/shared/host/lib/jk-host.jar)",
                        siblings.gateFor(GUARD)))
                .startsWith("sibling cc.jumpkick:jk-host failed at compile-test-fixtures — ");
    }

    @Test
    void a_sibling_the_schedule_did_not_see_fail_reads_as_not_built() {
        SiblingArtifacts siblings =
                new SiblingArtifacts(Map.of(HOST, Set.of(), GUARD, Set.of(HOST)), List.of(HOST, GUARD));

        assertThat(PlannerSetup.missingSiblingMessage("test sibling not built — ", LABEL, siblings.gateFor(GUARD)))
                .isEqualTo("test sibling not built — " + LABEL);
        assertThat(PlannerSetup.missingSiblingMessage("test sibling not built — ", LABEL, SiblingArtifacts.NONE))
                .as("outside a schedule nothing failed")
                .isEqualTo("test sibling not built — " + LABEL);
    }
}
