// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import org.junit.jupiter.api.Test;

/** The interface {@link InheritedFromInterfaceFixture} takes its test from. */
interface InheritedFromInterfaceFixtureContract {

    @Test
    default void contract() {}
}
