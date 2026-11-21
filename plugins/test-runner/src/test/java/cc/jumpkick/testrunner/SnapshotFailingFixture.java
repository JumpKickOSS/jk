// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A test that fails only in the JVM {@code FailureSnapshotsTest} starts, which sets {@link #FAIL}:
 * any other discoverer sees it pass. Its failure is an assertion thrown inside AssertJ, as most are.
 */
class SnapshotFailingFixture {

    static final String FAIL = "jk.fixture.snapshot.fail";

    @Test
    void counts_the_lines() {
        if (!Boolean.getBoolean(FAIL)) return;
        List<String> lines = List.of("A1", "B2");
        int expected = 3;
        assertThat(lines).hasSize(expected);
    }
}
