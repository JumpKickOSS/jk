// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import org.junit.platform.commons.annotation.Testable;

/**
 * A class that declares itself testable to the Platform and nothing more: no engine on any
 * classpath runs it, so a root holding it discovers no test while its bytes say a framework was
 * meant. Copied into a root of its own by the empty-tier tests.
 */
public final class TestableOnlyFixture {

    @Testable
    public void probe() {}
}
