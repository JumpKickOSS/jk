// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;

/** A test annotation composed of Jupiter's {@code @Test}, the way a project's own {@code @IntegrationTest} is. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Test
@interface ComposedTestFixture {}
