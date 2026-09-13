// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.nio.file.Path;

/**
 * Where an end-to-end test keeps a cache that outlives the run — a spike's CAS, the managed Android
 * SDK, a Grails or Scala fixture's store — so a repeat run downloads nothing. Under the jk sandbox
 * that is the shared test cache beside the sandbox home, the directory the launcher names as {@code
 * -Djk.test.cache.dir}; outside it (an IDE run) it is {@code build/} under the working directory.
 * The working directory alone is not a root: a sandboxed test JVM inherits the engine's, which is
 * the real home's state directory, and a cache rooted there escapes the sandbox.
 */
public final class TestCaches {

    private TestCaches() {}

    /** The persistent cache directory {@code name}; whoever writes it creates it. */
    public static Path dir(String name) {
        String shared = System.getProperty("jk.test.cache.dir");
        Path root = shared != null && !shared.isBlank()
                ? Path.of(shared)
                : Path.of(System.getProperty("user.dir"), "build");
        return root.resolve(name);
    }
}
