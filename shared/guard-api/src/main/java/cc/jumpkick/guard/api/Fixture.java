// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Must-bite evidence: a tree (workspace-relative) the guard fails on. {@code jk guard test} builds
 * the fixture and runs the guard over it; a guard with no fixture and no site in the real tree is
 * {@code no-bite}, red, like a TOML rule with no owner and no hit.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Fixture {
    /** Workspace-relative directory, e.g. {@code guard-fixtures/one-json-codec}. */
    String value();
}
