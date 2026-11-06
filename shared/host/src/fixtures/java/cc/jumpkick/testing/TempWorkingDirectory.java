// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.WorkingDirectory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Pins {@link WorkingDirectory} to a fresh empty directory under {@link ShortTempDirs#root()} for
 * each test class, and to another for each test method, deleting each afterwards.
 *
 * <p>A command a test runs in-process resolves a relative target, and searches for its project,
 * from the working directory. The test JVM's own is the module directory inside the checkout, so
 * {@code jk new demo} would create {@code demo} in the source tree and join the module's {@code
 * jk.toml}. Under this extension it lands in a temp directory below the search ceiling every test
 * JVM has. A test that means a particular directory names it, with {@code -C} or an absolute path.
 */
public final class TempWorkingDirectory
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(TempWorkingDirectory.class);

    /** The directory pinned for a scope and the pin it replaced. */
    private record Pin(Path dir, @Nullable Path previous) {}

    @Override
    public void beforeAll(ExtensionContext context) {
        push(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        push(context);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        pop(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        pop(context);
    }

    private static void push(ExtensionContext context) {
        Path dir;
        try {
            dir = Files.createTempDirectory(ShortTempDirs.root(), "cwd-");
        } catch (IOException e) {
            throw new UncheckedIOException("creating a temp working directory", e);
        }
        context.getStore(NS).put(Pin.class, new Pin(dir, WorkingDirectory.pin(dir)));
    }

    private static void pop(ExtensionContext context) {
        Pin pin = context.getStore(NS).remove(Pin.class, Pin.class);
        if (pin == null) return;
        WorkingDirectory.pin(pin.previous());
        PathUtil.deleteRecursively(pin.dir());
    }
}
