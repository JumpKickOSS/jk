// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * The headless-IDE tests in {@link IdeHostedTest#DRIVE_JK}. They drive the jk on PATH in the test's
 * sandbox home, seeded with that client's own installed engine and workers ({@link InstalledJk}); the
 * builds they run resolve from Maven Central: network tier.
 */
@Tag("network")
class IdeHostedJkTest {

    @TestFactory
    Stream<DynamicTest> ide_hosted_jk_suites() throws IOException {
        var unknown = IdeHostedTest.unknownDriveJk();
        if (!unknown.isEmpty()) throw new IllegalStateException("no such IDE-hosted test: " + unknown);
        InstalledJk.seedSandbox();
        return IdeHostedTest.tests(IdeHostedTest.DRIVE_JK::contains);
    }
}
