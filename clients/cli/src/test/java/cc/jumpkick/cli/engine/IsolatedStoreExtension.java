// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** See {@link IsolatedStore}. */
public final class IsolatedStoreExtension implements BeforeEachCallback, AfterEachCallback {

    private static final String PROP = "jk.env.JK_STORE_DIR";
    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(IsolatedStoreExtension.class);

    @Override
    public void beforeEach(ExtensionContext ctx) throws Exception {
        Path store = Files.createTempDirectory("jk-iso-store");
        ctx.getStore(NS).put("store", store);
        ctx.getStore(NS).put("prev", System.getProperty(PROP));
        System.setProperty(PROP, store.toString());
    }

    @Override
    public void afterEach(ExtensionContext ctx) {
        try {
            // While the overlay is still set, EnginePaths.current() resolves to the isolated
            // engine key — stop exactly that engine so per-method daemons never accumulate.
            EngineTestSupport.stopEngineOnly();
        } finally {
            String prev = (String) ctx.getStore(NS).get("prev");
            if (prev != null) {
                System.setProperty(PROP, prev);
            } else {
                System.clearProperty(PROP);
            }
            Path store = (Path) ctx.getStore(NS).get("store");
            if (store != null) {
                try {
                    cc.jumpkick.util.PathUtil.deleteRecursivelyOrThrow(store);
                } catch (Exception ignored) {
                    // /tmp leftovers are ephemeral; never fail the test on cleanup.
                }
            }
        }
    }
}
