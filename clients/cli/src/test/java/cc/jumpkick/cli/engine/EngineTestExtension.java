// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Global CLI suite extension (autodetected):
 *
 * <ul>
 * <li>BeforeAll (per class) — materialize the engine jar into the test {@code JK_HOME}
 * (once per JVM behind a static guard)
 * <li>Suite end (once per JVM) — force-stop via a root-store {@link AutoCloseable} so
 * teardown is clean
 * </ul>
 *
 * <p>Keeps one warm engine across methods and classes. TempDir cleanup uses
 * {@link JkTempDirDeletionStrategy}: stop engine only when a delete fails. A test that needs
 * a fresh engine stops it itself ({@link IsolatedStore} classes already do).
 */
public final class EngineTestExtension implements BeforeAllCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(EngineTestExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        EngineTestSupport.ensureEngineMaterialized();
        // Root-store AutoCloseable: JUnit closes it when the whole suite (this JVM) ends.
        context.getRoot().getStore(NS).computeIfAbsent("engine-teardown", _ ->
                (AutoCloseable) EngineTestSupport::stopEngineAndRelease);
    }
}
