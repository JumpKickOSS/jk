// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * A test class whose static initializer throws, copied into a classpath root of its own by {@code
 * DiscoveryFailuresTest} and loaded through a context loader that initializes on load — the shape
 * of a framework loader that boots the application while loading a test class. Disabled, so the
 * scan over this module's own root discovers it without ever initializing it.
 */
@Disabled("initializes only under the loader DiscoveryFailuresTest installs")
final class StaticInitTestFixture {

    static final int BOOT = boot();

    private static int boot() {
        throw new IllegalStateException("application bootstrap failed in the static initializer");
    }

    @Test
    void boots() {}
}
