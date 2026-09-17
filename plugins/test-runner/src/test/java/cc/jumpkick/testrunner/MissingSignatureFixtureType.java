// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

/**
 * The type {@code DiscoveryFailuresTest} hides from a class loader so that reflecting on {@link
 * MissingSignatureFixture}'s methods fails the way a test compiled against a provided dependency
 * absent from the test classpath fails: the class loads, its members do not.
 */
final class MissingSignatureFixtureType {}
