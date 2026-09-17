// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

/**
 * The supertype {@code DiscoveryFailuresTest} hides from a class loader so that loading {@link
 * MissingBaseHelperFixture} fails the way a fixture compiled against a compile-only dependency
 * fails at test time. Declares no test.
 */
abstract class MissingBaseHelperFixtureBase {}
