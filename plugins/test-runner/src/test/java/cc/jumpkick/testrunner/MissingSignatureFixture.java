// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import org.junit.jupiter.api.Test;

/**
 * Loads with {@link MissingSignatureFixtureType} out of reach — nothing in a class's own loading
 * resolves a method's parameter types — and fails only when its declared methods are read, which
 * is what the Platform's test-class filter does; see {@code DiscoveryFailuresTest}. Its one test
 * passes, so the scan over this module's own root sees green.
 */
class MissingSignatureFixture {

    @Test
    void passes() {}

    /** Never called: the signature alone is what the probe must trip over. */
    void uses(MissingSignatureFixtureType provided) {}
}
