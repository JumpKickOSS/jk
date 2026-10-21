// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Gives every test method in the class, or the one method annotated, its own fresh artifact store.
 *
 * <p>The ambient store is the module sandbox's, shared by every test of the module, so fixtures
 * that mint one coordinate ({@code com.foo:root:1.0}, …) with different bytes poison each other: the
 * first write wins and later tests lock stale checksums. Annotate tests that assert store-mediated
 * behavior end-to-end (install → resolve, journals, checksums); everything else should prefer
 * unique fixture coordinates and keep the shared store's speed.
 *
 * <p>Required of any test that writes into or deletes from the ambient store's {@code repos/central},
 * {@code google} or {@code jumpkick}: those trees are links into the pool every test sandbox on the
 * host shares, so a fake there reaches every worktree's suites.
 *
 * <p>The state root has its own twin, {@link IsolatedState}; a class may carry both.
 *
 * <p>Mechanics: sets the {@code jk.env.JK_STORE_DIR} overlay ({@link cc.jumpkick.util.JkDirs}
 * test seam) to a per-method temp dir. Engine identity is keyed by (state, store), so commands
 * spawn a dedicated engine; it is force-stopped after each test before the overlay is cleared
 * and the temp store deleted.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(IsolatedRootsExtension.class)
public @interface IsolatedStore {}
