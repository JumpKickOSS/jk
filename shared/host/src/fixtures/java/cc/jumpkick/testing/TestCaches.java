// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where an end-to-end test keeps a cache that outlives the run — a spike's CAS, the managed Android
 * SDK, a Grails or Scala fixture's store — so a repeat run downloads nothing. Under the jk sandbox
 * that is the shared test cache beside the sandbox home, the directory the launcher names as {@code
 * -Djk.test.cache.dir}; outside it (an IDE run) it is {@code target/test-caches/} under the working
 * directory, where {@code jk clean} reaches it.
 * The working directory alone is not a root: a sandboxed test JVM inherits the engine's, which is
 * the real home's state directory, and a cache rooted there escapes the sandbox.
 */
public final class TestCaches {

    private TestCaches() {}

    /** The persistent cache directory {@code name}, created. */
    public static Path dir(String name) {
        String shared = System.getProperty("jk.test.cache.dir");
        Path root = shared != null && !shared.isBlank()
                ? Path.of(shared)
                : Path.of(System.getProperty("user.dir"), "target", "test-caches");
        Path dir = root.resolve(name);
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("creating test cache " + dir, e);
        }
    }
}
