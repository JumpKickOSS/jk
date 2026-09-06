// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test.fixture;

import cc.jumpkick.test.TestClassIndex;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;

/**
 * Compiled bytes for {@code TestClassIndexFactsTest}: class-level tags through the container, a
 * method-level tag, and a reference to a production type. Not a test; nothing here runs.
 */
@SuppressWarnings("unused")
@Tags({@Tag("slow"), @Tag("network")})
public final class TaggedFixtureTest {

    private TaggedFixtureTest() {}

    @Tag("integration")
    void usesProduction() {
        TestClassIndex.nameMatchSimple("x");
    }
}
