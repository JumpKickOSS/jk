// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Gives every test method in the class its own fresh state root — the {@link IsolatedStore} twin
 * for {@code <JK_HOME>/state} rather than {@code <JK_HOME>/data/store}.
 *
 * <p>The tier's {@code JK_HOME} ({@code clients/cli/build/test-jk-home}) is shared by every one of
 * the task's parallel forks <em>and</em> by every previous run — no task cleans it. So {@code
 * state/aot}, {@code state/builds} and {@code state/engine} are ambient input: a class that plants
 * a fixture there is asserting against whatever the last run and the sibling forks left behind.
 * That is not hypothetical — {@code EngineAotCommandTest} planted {@code
 * engine-<version>-deadbeefdeadbeef.aot} in the shared {@code state/aot} while a concurrent fork
 * running {@code EngineAotCacheTest} swept every {@code engine-<version>-<16hex>} key that was not
 * its own, and the assertion lost the race (JK-2453).
 *
 * <p>Annotate any class that reads or writes {@link cc.jumpkick.util.JkDirs#state()} — the
 * {@code :cli} guard {@code checkTestRootsDeclared} requires it. Classes that only need a throwaway
 * artifact store want {@link IsolatedStore}; a class may carry both.
 *
 * <p>Mechanics: sets the {@code jk.env.JK_STATE_DIR} overlay ({@link cc.jumpkick.util.JkDirs} test
 * seam) to a per-method temp dir. Engine identity is keyed by (state, store), so commands spawn a
 * dedicated engine; it is force-stopped after each test before the overlay is cleared and the temp
 * root deleted.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(IsolatedRootsExtension.class)
public @interface IsolatedState {}
