// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Restores the system-property table around a test class and around each of its methods, so a
 * suite cannot leave a property set for whatever runs next in the same fork.
 *
 * <p>The leak this closes is not hypothetical. Nineteen test classes set a property and restored
 * nothing: eight {@code :cli} command suites and {@code :resolver}'s {@code CacheSyncTest} pointed
 * {@code jk.m2.local} at a {@code @TempDir} in {@code @BeforeAll}, so every *later* class in the
 * fork resolved artifacts into a directory JUnit had already deleted; eight {@code :engine} Android
 * suites left {@code AndroidSdk.ROOT_PROPERTY} pointing at their own fixture SDK. Whether that
 * mattered depended on class order, which is why it survived — the leak is invisible until the
 * suite that inherits it is the one that reads the property.
 *
 * <p>Snapshot/restore is whole-table rather than a declared list on purpose: a test that sets a
 * property the extension was not told about is exactly the case that leaked before, and a list is
 * one more thing to keep in step with the body.
 *
 * <pre>{@code
 * @ExtendWith(SysProps.class)                  // restore only
 * @SysProps.TempRoots("jk.m2.local")           // restore, and point the property at a fresh dir
 * }</pre>
 */
public final class SysProps implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    /**
     * Properties whose value should be a throwaway directory, created fresh for the annotated
     * class and deleted with it. Registers {@link SysProps}, so the redirect is restored too.
     *
     * <p>{@code jk.m2.local} is the one that matters today: a suite whose fixtures reuse real
     * coordinates (junit-jupiter, and every plugin manifest that names one) must not mirror stub
     * jars into the shared local repository, or the first writer wins and later suites lock stale
     * checksums.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @ExtendWith(SysProps.class)
    public @interface TempRoots {
        String[] value();
    }

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(SysProps.class);
    private static final String CLASS_SNAPSHOT = "class-snapshot";
    private static final String METHOD_SNAPSHOT = "method-snapshot";
    private static final String TEMP_ROOTS = "temp-roots";

    @Override
    public void beforeAll(ExtensionContext ctx) throws IOException {
        ctx.getStore(NS).put(CLASS_SNAPSHOT, snapshot());
        TempRoots roots = ctx.getRequiredTestClass().getAnnotation(TempRoots.class);
        if (roots == null) return;
        Path root = Files.createTempDirectory("jk-temp-roots");
        ctx.getStore(NS).put(TEMP_ROOTS, root);
        int i = 0;
        for (String property : roots.value()) {
            System.setProperty(
                    property, Files.createDirectories(root.resolve("r" + i++)).toString());
        }
    }

    @Override
    public void afterAll(ExtensionContext ctx) {
        restore((Properties) ctx.getStore(NS).get(CLASS_SNAPSHOT));
        Path root = (Path) ctx.getStore(NS).get(TEMP_ROOTS);
        if (root != null) PathUtil.deleteRecursively(root);
    }

    @Override
    public void beforeEach(ExtensionContext ctx) {
        ctx.getStore(NS).put(METHOD_SNAPSHOT, snapshot());
    }

    @Override
    public void afterEach(ExtensionContext ctx) {
        restore((Properties) ctx.getStore(NS).get(METHOD_SNAPSHOT));
    }

    /** A detached copy — {@code System.getProperties()} is live, so a reference would track edits. */
    private static Properties snapshot() {
        Properties copy = new Properties();
        copy.putAll(System.getProperties());
        return copy;
    }

    /**
     * Put {@code saved} back exactly: values reset, and anything added since removed. Both halves
     * matter — restoring only the values leaves a brand-new property set for the next class.
     */
    private static void restore(Properties saved) {
        if (saved == null) return;
        for (String name : System.getProperties().stringPropertyNames()) {
            if (!saved.containsKey(name)) System.clearProperty(name);
        }
        for (String name : saved.stringPropertyNames()) {
            System.setProperty(name, saved.getProperty(name));
        }
    }
}
