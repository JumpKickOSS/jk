// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test.fixture;

/**
 * Compiled bytes for {@code TestClassIndexRefsTest}: a class literal, a method reference, a caught
 * exception type and an annotation's class value, each the only reference to its type. Not a test;
 * nothing here runs.
 */
@SuppressWarnings("unused")
@Referenced.Uses(Referenced.AnnotationValue.class)
public final class ReferencesFixtureTest {

    private ReferencesFixtureTest() {}

    Object literal() {
        return Referenced.Literal.class;
    }

    Runnable methodRef() {
        return Referenced.MethodRef::run;
    }

    void caught(Runnable r) {
        try {
            r.run();
        } catch (Referenced.Caught e) {
            r.run();
        }
    }
}
