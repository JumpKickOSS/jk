// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A reviewed exemption: sites in {@code in} — a module glob, a class glob, a fingerprint or a path
 * glob — do not count, for the stated reason. A path is the file's real location from the workspace
 * root: the module directory, then whichever source root on disk holds the file —
 * {@code web/src/main/kotlin/com/example/web/Boot.kt} for a Kotlin class,
 * {@code tools/src/com/example/tools/Main.java} in a compact module; {@code src/main/java} is the
 * spelling only when no root on disk holds the file. A workspace-scoped guard's site is spelled
 * under the member that owns the class, and a module glob is judged against that member. An allow
 * that matches nothing is {@code stale-allow}, red, exactly as in TOML; there is no suppression
 * comment.
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
