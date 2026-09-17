// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

/**
 * A helper whose static initializer throws, copied into a classpath root of its own by {@code
 * DiscoveryFailuresTest} and loaded through a context loader that initializes on load. Not a test
 * class: the probe over the classes discovery dropped leaves it alone, and the scan over this
 * module's own root never initializes it.
 */
final class StaticInitFixture {

    static final int BOOT = boot();

    private StaticInitFixture() {}

    private static int boot() {
        throw new IllegalStateException("application bootstrap failed in the static initializer");
    }
}
