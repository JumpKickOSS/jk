// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * The headless-IDE tests in {@link IdeHostedTest#DRIVE_JK}. They drive the jk on PATH, a released
 * client running in the test's sandbox home, which holds no engine, so it downloads its engine from
 * the release site: network tier.
 */
@Tag("network")
class IdeHostedJkTest {

    @TestFactory
    Stream<DynamicTest> ide_hosted_jk_suites() {
        var unknown = IdeHostedTest.unknownDriveJk();
        if (!unknown.isEmpty()) throw new IllegalStateException("no such IDE-hosted test: " + unknown);
        return IdeHostedTest.tests(IdeHostedTest.DRIVE_JK::contains);
    }
}
