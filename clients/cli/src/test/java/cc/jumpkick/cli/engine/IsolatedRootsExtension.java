// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.PathUtil;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * One per-method throwaway-root mechanism behind {@link IsolatedStore} and {@link IsolatedState}.
 *
 * <p>Both annotations register this class, and JUnit registers a declaratively-requested extension
 * type once — so a class carrying both gets one instance that isolates both roots, in one temp-dir
 * lifecycle with one engine stop.
 */
public final class IsolatedRootsExtension implements BeforeEachCallback, AfterEachCallback {

    /** A jk layout root this extension can throw away: its marker annotation and its env name. */
    private record Root(Class<? extends Annotation> marker, String env, String tempPrefix) {
        /** The {@code JkDirs} in-process seam: a {@code jk.env.<NAME>} property beats the real env. */
        String property() {
            return "jk.env." + env;
        }
    }

    private static final List<Root> ROOTS = List.of(
            new Root(IsolatedStore.class, "JK_STORE_DIR", "jk-iso-store"),
            new Root(IsolatedState.class, "JK_STATE_DIR", "jk-iso-state"));

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(IsolatedRootsExtension.class);

    /** What {@link #beforeEach} redirected, so {@link #afterEach} can put it back exactly. */
    private record Redirect(Root root, Path dir, String previous) {}

    @Override
    public void beforeEach(ExtensionContext ctx) throws Exception {
        Class<?> testClass = ctx.getRequiredTestClass();
        List<Redirect> redirects = new ArrayList<>();
        for (Root root : ROOTS) {
            if (!testClass.isAnnotationPresent(root.marker())) continue;
            Path dir = Files.createTempDirectory(root.tempPrefix());
            redirects.add(new Redirect(root, dir, System.getProperty(root.property())));
            System.setProperty(root.property(), dir.toString());
        }
        ctx.getStore(NS).put("redirects", redirects);
    }

    @Override
    public void afterEach(ExtensionContext ctx) {
        @SuppressWarnings("unchecked")
        List<Redirect> redirects = (List<Redirect>) ctx.getStore(NS).get("redirects");
        if (redirects == null || redirects.isEmpty()) return;
        try {
            // While the overlays are still set, EnginePaths.current() resolves to the isolated
            // engine key — stop exactly that engine so per-method daemons never accumulate.
            EngineTestSupport.stopEngineOnly();
        } finally {
            for (Redirect r : redirects) {
                if (r.previous() != null) {
                    System.setProperty(r.root().property(), r.previous());
                } else {
                    System.clearProperty(r.root().property());
                }
                try {
                    PathUtil.deleteRecursivelyOrThrow(r.dir());
                } catch (Exception ignored) {
                    // Temp-root leftovers are ephemeral; never fail the test on cleanup.
                }
            }
        }
    }
}
