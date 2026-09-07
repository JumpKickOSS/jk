// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A class of guard tests under {@code src/guard}. The scope decides which facts its methods receive
 * and which lane runs them; a {@link Scope#MODULE} suite that asks for {@link Text} is a load error
 * unless the module's own sources are all it reads.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface GuardSuite {
    Scope scope() default Scope.MODULE;
}
