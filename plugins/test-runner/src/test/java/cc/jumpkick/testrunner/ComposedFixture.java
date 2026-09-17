// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

/** A test class whose only test wears the composed {@link ComposedTestFixture} annotation; it passes. */
class ComposedFixture {

    @ComposedTestFixture
    void composed() {}
}
