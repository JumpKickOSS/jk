// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A top-level class with one tagged and one untagged test, copied into a classpath root of its
 * own by {@code LauncherPathTest}: a tier whose tag filter admits neither is empty on purpose.
 * Top-level because the runner counts only top-level class files. Both methods pass, so any
 * other discoverer sees green.
 */
class TagEmptiedFixture {

    @Test
    void untagged() {}

    @Test
    @Tag("slow")
    void tagged() {}
}
