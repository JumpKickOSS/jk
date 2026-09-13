// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Must-bite evidence: a directory (workspace-relative) the guard fails on. {@code jk guard test}
 * builds the fixture and runs the guard over it; a guard with no fixture and no site in the real
 * tree is {@code no-bite}, red, like a TOML rule with no owner and no hit.
 *
 * <p>Two forms. {@code Bad*} and {@code Ok*} <em>files</em>: each is judged on its own, {@code Bad}
 * must produce a violation and {@code Ok} none. {@code Bad*} and {@code Ok*} <em>directories</em>:
 * each is a tree the guard runs over as if it were the checkout root, so a guard that reads
 * several files at once — a workflow, a manifest, a pin — has a case per check it makes. The files
 * beside the case directories are the tree every case starts from; a case's own files are laid
 * over them.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Fixture {
    /** Workspace-relative directory, e.g. {@code guard-fixtures/one-json-codec}. */
    String value();
}
