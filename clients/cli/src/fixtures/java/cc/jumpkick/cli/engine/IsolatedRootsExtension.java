// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.templates.testing.LocalTemplateCatalog;
import cc.jumpkick.testing.PropertyRoots;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * One per-method throwaway-root mechanism behind {@link IsolatedStore}, {@link IsolatedState} and
 * {@link IsolatedHome}, on a class (every method) or on one method; an {@link IsolatedHome#perClass}
 * home is made once for the class instead.
 *
 * <p>Both annotations register this class, and JUnit registers a declaratively-requested extension
 * type once — so a class carrying both gets one instance that isolates both roots, in one temp-dir
 * lifecycle with one engine stop.
 */
public final class IsolatedRootsExtension
        implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback {

    /** A jk layout root this extension can throw away: its marker annotation and its env name. */
    private record Root(Class<? extends Annotation> marker, String env, String tempPrefix) {
        /** The {@code JkDirs} in-process seam: a {@code jk.env.<NAME>} property beats the real env. */
        String property() {
            return "jk.env." + env;
        }
    }

    private static final List<Root> ROOTS = List.of(
            new Root(IsolatedStore.class, "JK_STORE_DIR", "jk-iso-store"),
            new Root(IsolatedState.class, "JK_STATE_DIR", "jk-iso-state"),
            new Root(IsolatedHome.class, "JK_HOME", "jk-iso-home"));

    /** What an {@link IsolatedHome}'s {@code config.toml} says before the test adds to it. */
    static final String HOME_CONFIG = """
            # A repository at Central's URL stores under this home, not in the tier's pooled Maven
            # local repository.
            [m2]
            integration = false
            """;

    /**
     * {@link #HOME_CONFIG} with the official template catalog at {@code catalogUrl}, or at its
     * origin when that is null.
     */
    static String homeConfig(@Nullable String catalogUrl) {
        if (catalogUrl == null) return HOME_CONFIG;
        return """
                # The official template catalog as this checkout ships it, cloned without the network.
                [templates]
                official = "%s"

                """.formatted(catalogUrl) + HOME_CONFIG;
    }

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(IsolatedRootsExtension.class);

    /** Distinct keys: a method's store falls back to its class's, so one key would read the class's list. */
    private static final String METHOD_REDIRECTS = "redirects";

    private static final String CLASS_REDIRECTS = "class-redirects";

    /** A {@link IsolatedHome#perClass} home, made before the class's first method. */
    @Override
    public void beforeAll(ExtensionContext ctx) throws Exception {
        if (!homePerClass(ctx.getRequiredTestClass())) return;
        Root home = ROOTS.stream()
                .filter(r -> r.marker() == IsolatedHome.class)
                .findFirst()
                .orElseThrow();
        PropertyRoots.Redirect redirect = PropertyRoots.redirect(home.property(), home.tempPrefix());
        List<PropertyRoots.Redirect> redirects = new ArrayList<>();
        redirects.add(redirect);
        redirects.add(furnishHome(redirect.dir(), fromOrigin(ctx, ctx.getRequiredTestClass())));
        ctx.getStore(NS).put(CLASS_REDIRECTS, redirects);
    }

    @Override
    public void beforeEach(ExtensionContext ctx) throws Exception {
        Class<?> testClass = ctx.getRequiredTestClass();
        List<PropertyRoots.Redirect> redirects = new ArrayList<>();
        for (Root root : ROOTS) {
            boolean onMethod = ctx.getTestMethod()
                    .map(m -> m.isAnnotationPresent(root.marker()))
                    .orElse(false);
            if (!onMethod && !testClass.isAnnotationPresent(root.marker())) continue;
            if (!onMethod && root.marker() == IsolatedHome.class && homePerClass(testClass)) continue;
            PropertyRoots.Redirect redirect = PropertyRoots.redirect(root.property(), root.tempPrefix());
            redirects.add(redirect);
            if (root.marker() == IsolatedHome.class) {
                redirects.add(furnishHome(redirect.dir(), fromOrigin(ctx, testClass)));
            }
        }
        ctx.getStore(NS).put(METHOD_REDIRECTS, redirects);
    }

    private static boolean homePerClass(Class<?> testClass) {
        IsolatedHome home = testClass.getAnnotation(IsolatedHome.class);
        return home != null && home.perClass();
    }

    /** The method's {@link IsolatedHome#officialCatalogFromOrigin}, else the class's. */
    private static boolean fromOrigin(ExtensionContext ctx, Class<?> testClass) {
        IsolatedHome home = ctx.getTestMethod()
                .map(m -> m.getAnnotation(IsolatedHome.class))
                .orElseGet(() -> testClass.getAnnotation(IsolatedHome.class));
        return home != null && home.officialCatalogFromOrigin();
    }

    /**
     * The home's starting {@code config.toml}, a {@code settings.xml} overlay naming a file that is
     * not there, and the engine assembly the home's engine is spawned from. Returns the overlay's
     * redirect, so teardown restores it with the rest.
     */
    private static PropertyRoots.Redirect furnishHome(Path home, boolean catalogFromOrigin) throws IOException {
        Files.writeString(
                home.resolve("config.toml"), homeConfig(catalogFromOrigin ? null : LocalTemplateCatalog.url()));
        Path m2 = Files.createDirectories(home.resolve("m2"));
        String property = "jk.env.JK_M2_SETTINGS";
        PropertyRoots.Redirect settings = new PropertyRoots.Redirect(property, m2, System.getProperty(property));
        System.setProperty(property, m2.resolve("settings.xml").toString());
        EngineTestSupport.ensureEngineMaterialized();
        return settings;
    }

    @Override
    public void afterEach(ExtensionContext ctx) {
        tearDown(ctx, METHOD_REDIRECTS);
    }

    @Override
    public void afterAll(ExtensionContext ctx) {
        tearDown(ctx, CLASS_REDIRECTS);
    }

    private static void tearDown(ExtensionContext ctx, String key) {
        @SuppressWarnings("unchecked")
        List<PropertyRoots.Redirect> redirects =
                (List<PropertyRoots.Redirect>) ctx.getStore(NS).get(key);
        if (redirects == null || redirects.isEmpty()) return;
        try {
            // While the overlays are still set, EnginePaths.current() resolves to the isolated
            // engine key — the ServiceLoader hook (EngineStopHook) stops exactly that engine so
            // per-method (or per-class) daemons never accumulate.
            PropertyRoots.runTeardownHooks();
        } finally {
            for (PropertyRoots.Redirect r : redirects) {
                PropertyRoots.restore(r);
            }
        }
    }
}
