// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A top-level class whose only test is a {@code @ParameterizedTest}, copied into a classpath root
 * of its own by {@code LauncherPathTest}: its discovery holds a container and no test until the
 * template runs. Every case passes, so any other discoverer sees green.
 */
class ParameterizedOnlyFixture {

    @ParameterizedTest
    @ValueSource(strings = {"a", "b", "c", "d"})
    void takes(String value) {}
}
