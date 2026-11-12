// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The one method that derives a step's cache key, or a fact the key hashes. Both the build and
 * {@code jk explain} call it, so the forecast hashes what the build hashes; the engine's {@code
 * forecast-key-*} guard tests read these from bytecode and refuse a key derived anywhere else.
 * See {@code docs/contributors/lifecycle.md}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface KeyOwner {

    /** The steps the key prices, as {@code TaskNames} spells them; a trailing {@code *} names a family. */
    String[] step();

    /**
     * Why the build and the forecast do not share this body (no forecast of the step, a replay of
     * the last record, a check mirrored on the other side); empty for an owner both sides call.
     */
    String unpaired() default "";

    /** For an unpaired owner: whether the forecast still names a verdict for the step. */
    boolean forecastsStep() default false;
}
