// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A reviewed exemption: sites in {@code in} — a module glob, a class glob or a path glob — do not
 * count, for the stated reason. An allow that matches nothing is {@code stale-allow}, red, exactly as
 * in TOML; there is no suppression comment.
 */
@Documented
@Repeatable(Allow.Allows.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Allow {
    String in();

    String reason();

    /** The container the compiler writes for repeated {@link Allow}s. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Allows {
        Allow[] value();
    }
}
