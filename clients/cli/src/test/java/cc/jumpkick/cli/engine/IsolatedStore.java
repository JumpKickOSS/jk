// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Gives every test method in the class its own fresh artifact store.
 *
 * <p>Since the cache/store split, mirrors and the CAS are shared per module test harness
 * ({@code test-jk-home/store}) by design — so fixtures that mint the same coordinate
 * ({@code com.foo:root:1.0}, …) with different bytes poison each other deterministically: the
 * first write wins the mirror and later tests lock stale checksums. Annotate classes whose tests
 * assert store-mediated behavior end-to-end (install → resolve, journals, checksums); everything
 * else should prefer unique fixture coordinates and keep the shared-store speed.
 *
 * <p>The state root has its own twin, {@link IsolatedState}; a class may carry both.
 *
 * <p>Mechanics: sets the {@code jk.env.JK_STORE_DIR} overlay ({@link cc.jumpkick.util.JkDirs}
 * test seam) to a per-method temp dir. Engine identity is keyed by (state, store), so commands
 * spawn a dedicated engine; it is force-stopped after each test before the overlay is cleared
 * and the temp store deleted.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(IsolatedRootsExtension.class)
public @interface IsolatedStore {}
