// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test.fixture;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Production-like types {@link ReferencesFixtureTest} names, each through one kind of reference. */
public final class Referenced {

    private Referenced() {}

    /** Named only by a class literal. */
    public static final class Literal {}

    /** Named only by a method reference. */
    public static final class MethodRef {
        public static void run() {}
    }

    /** Named only as a caught exception type. */
    public static final class Caught extends RuntimeException {}

    /** Named only as an annotation's class value. */
    public static final class AnnotationValue {}

    /** An annotation whose element is a class, as {@code @SpringBootTest(classes = …)} is. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Uses {
        Class<?> value();
    }
}
