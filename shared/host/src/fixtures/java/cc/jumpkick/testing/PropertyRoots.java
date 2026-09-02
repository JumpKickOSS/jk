// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import org.jspecify.annotations.Nullable;

/**
 * The one throwaway-root mechanism: point a system property at a fresh temp directory, then put
 * the property back exactly and delete the directory. {@code SysProps.TempRoots} (class-scoped,
 * plain properties) and the CLI's {@code @IsolatedStore}/{@code @IsolatedState} (method-scoped,
 * {@code jk.env.*} overlays) both redirect through here — three annotations, one lifecycle.
 *
 * <p>Deletion is best-effort and never fails a test: a daemon that outlives the method can hold a
 * socket open, and losing a temp directory is not the defect the test is looking for.
 */
public final class PropertyRoots {

    private PropertyRoots() {}

    /** One redirected property: what to restore, and the directory to reap. */
    public record Redirect(
            String property, Path dir, @Nullable String previous) {}

    /** Create a fresh root and point {@code property} at it; the returned redirect undoes both. */
    public static Redirect redirect(String property, String tempPrefix) throws IOException {
        Path dir = Files.createTempDirectory(tempPrefix);
        Redirect r = new Redirect(property, dir, System.getProperty(property));
        System.setProperty(property, dir.toString());
        return r;
    }

    /** Put the property back exactly as it was, then reap the root (best-effort). */
    public static void restore(Redirect r) {
        if (r.previous() != null) {
            System.setProperty(r.property(), r.previous());
        } else {
            System.clearProperty(r.property());
        }
        PathUtil.deleteRecursively(r.dir());
    }

    /**
     * Run every registered {@link RootTeardownHook} — call while the redirects are still in
     * force, before {@link #restore}. This is the seam that lets a module contribute teardown
     * this fixture cannot know about: the CLI stops the per-method engine daemon here, and
     * {@code :host} never grows a dependency on it.
     */
    public static void runTeardownHooks() {
        for (RootTeardownHook hook : ServiceLoader.load(RootTeardownHook.class)) {
            hook.beforeRootTeardown();
        }
    }

    /**
     * Contributed teardown that must run while redirected roots are still in force. Register via
     * {@code META-INF/services/cc.jumpkick.testing.RootTeardownHook} on the test classpath.
     */
    public interface RootTeardownHook {
        void beforeRootTeardown();
    }
}
