// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import org.junit.jupiter.api.Test;

/**
 * The supertype {@code DiscoveryFailuresTest} hides from a class loader so that loading {@link
 * MissingBaseFixture} fails the way a test compiled against a dependency missing from the test
 * classpath fails. Its one test passes, so the scan over this module's own root sees green.
 */
abstract class MissingBaseFixtureBase {

    @Test
    void inherited() {}
}
