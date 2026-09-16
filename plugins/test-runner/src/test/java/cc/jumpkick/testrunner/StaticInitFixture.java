// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

/**
 * A top-level class whose static initializer throws, copied into a classpath root of its own by
 * {@code DiscoveryFailuresTest} and loaded through a context loader that initializes on load — the
 * shape of a framework loader that boots the application while loading a test class. Not a test
 * class, so the scan over this module's own root never initializes it.
 */
final class StaticInitFixture {

    static final int BOOT = boot();

    private StaticInitFixture() {}

    private static int boot() {
        throw new IllegalStateException("application bootstrap failed in the static initializer");
    }
}
