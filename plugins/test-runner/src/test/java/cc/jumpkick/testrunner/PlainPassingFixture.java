// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import org.junit.jupiter.api.Test;

/**
 * A top-level class with one passing test, copied into a classpath root beside {@link
 * TagEmptiedFixture} by {@code LauncherPathTest} so a root holds more than one class.
 */
class PlainPassingFixture {

    @Test
    void passes() {}
}
