// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.runtime.base.TestStorePool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The flags a test JVM gets when its module's {@code [test] env} sandboxes it: CLI integration
 * tests use FFM ({@code EngineClient}, {@code MemoryProbe}) and JUnit autodetection of {@code
 * EngineTestExtension}, and a sandbox {@code JK_HOME} still reaches the host's shared caches.
 */
final class SandboxJvmFlags {

    private SandboxJvmFlags() {}

    /**
     * The flags for a test JVM whose environment adds {@code extras}; none when it adds nothing.
     * {@code shortTempDirFactory} and {@code cliTempDirSupport} say whether jk-cli's temp-dir
     * factory and deletion strategy are on the test classpath.
     */
    static List<String> of(Map<String, String> extras, boolean shortTempDirFactory, boolean cliTempDirSupport) {
        if (extras.isEmpty()) return List.of();
        List<String> flags = new ArrayList<>();
        flags.add("--enable-native-access=ALL-UNNAMED");
        flags.add("-Djunit.jupiter.extensions.autodetection.enabled=true");
        // Short /tmp factory + soft-fail delete. Nested engines
        // hardlink into @TempDir caches; macOS can fail Standard delete. The strategy
        // reports success anyway. Cleanup stays ALWAYS: NEVER left tens of thousands of
        // dirs on tmpfs /tmp until the next @TempDir could not allocate an inode.
        //
        // Only where the classes exist. These two live in jk-cli's TEST tree, and a
        // non-empty [test] env is not a test for "this is jk-cli" — server/engine sets one
        // too, and named a factory it cannot load JUnit logs a stack trace per @TempDir and
        // silently falls back, which costs the soft-fail delete this exists to provide.
        if (shortTempDirFactory) {
            flags.add("-Djunit.jupiter.tempdir.factory.default=cc.jumpkick.testing.ShortTempDirFactory");
        }
        if (cliTempDirSupport) {
            flags.add("-Djunit.jupiter.tempdir.deletion.strategy.default="
                    + "cc.jumpkick.cli.engine.JkTempDirDeletionStrategy");
        }
        flags.add("-Djunit.jupiter.tempdir.cleanup.mode.default=always");
        String jkHome = extras.get("JK_HOME");
        if (jkHome != null && !jkHome.isBlank()) {
            // Sibling of the sandbox home: <slot>/test-shared-cache (TestCaches).
            Path jkHomeParent = Path.of(jkHome).getParent();
            if (jkHomeParent != null) {
                flags.add("-Djk.test.cache.dir=" + jkHomeParent.resolve("test-shared-cache"));
            }
            // The host pool of verified downloads every sandbox shares (TestCaches.pooled).
            flags.add("-D" + TestStorePool.PROPERTY + "=" + TestStorePool.root());
            // Sandbox JK_HOME hides the product store; first-party workers still resolve
            // Zinc from the host engine's store.
            flags.add("-D"
                    + PomRuntimeClasspath.HOST_STORE_PROPERTY
                    + "="
                    + JkDirs.store().toAbsolutePath().normalize());
            // The host store's Central artifacts live in the host's Maven local repository, not
            // in the sandbox's.
            flags.add("-D"
                    + PomRuntimeClasspath.HOST_M2_PROPERTY
                    + "="
                    + M2Dirs.localRepository().toAbsolutePath().normalize());
        }
        return flags;
    }
}
