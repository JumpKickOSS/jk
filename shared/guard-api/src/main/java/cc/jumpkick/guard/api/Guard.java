// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One guard: a method in a {@link GuardSuite} class that receives any of {@link Facts},
 * {@link Model}, {@link Text}, {@link Output} and {@link Violations} and reports what it finds.
 * {@code id} is the diagnostic code and shares its namespace with the TOML rule ids — a duplicate is
 * a load error. {@code why} is the invariant in one sentence; {@code instead} the sanctioned
 * alternative an agent applies at a site. Outcomes, the population floor, the baseline and thrash
 * detection are the engine's, exactly as for a TOML rule.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Guard {
    /** The rule id: lower-case letters, digits and hyphens. */
    String id();

    /** One sentence: the defect this rule prevents. */
    String why();

    /** The sanctioned alternative; empty when the violation says it all. */
    String instead() default "";
}
