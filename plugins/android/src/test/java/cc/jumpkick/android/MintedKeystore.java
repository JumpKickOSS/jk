// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * One debug keystore per test JVM, minted by the plugin's own {@code keytool} call and copied
 * wherever a fixture needs a store: each {@code keytool} fork costs over half a second.
 */
final class MintedKeystore {

    private static @Nullable Path minted;

    private MintedKeystore() {}

    /** A copy of the minted keystore at {@link DebugKeystore#path} under {@code dir}. */
    static synchronized Path in(Path dir) throws Exception {
        Path source = minted;
        if (source == null) {
            Path home = Files.createTempDirectory("jk-test-keys");
            home.toFile().deleteOnExit();
            source = DebugKeystore.ensure(home, Path.of(System.getProperty("java.home")));
            source.toFile().deleteOnExit();
            minted = source;
        }
        Path target = DebugKeystore.path(Files.createDirectories(dir));
        if (!Files.exists(target)) Files.copy(source, target);
        return target;
    }
}
