// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A dependency-only change (new lock, unchanged main jar) must discard
 * {@code target/aot-cache} — run.sh would otherwise keep executing the old lib/ copies.
 */
class AotCacheStalenessTest {

    private static void writeCache(Path projectDir, String lockSha) throws Exception {
        Path jar = projectDir.resolve("target").resolve("app.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar-bytes");
        Path outDir = Files.createDirectories(projectDir.resolve("target").resolve("aot-cache"));
        Files.writeString(outDir.resolve(AotCachePackage.MANIFEST), """
                cache        = "app.aot"
                built-from   = "%s"
                app-sha256   = "%s"
                lock-sha256  = "%s"
                """.formatted(
                        jar.toAbsolutePath(), cc.jumpkick.host.Hashing.sha256Hex(jar), lockSha));
    }

    @Test
    void lock_change_discards_the_cache(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("jk-lock.toml"), "old lock");
        writeCache(projectDir, cc.jumpkick.host.Hashing.sha256Hex(projectDir.resolve("jk-lock.toml")));
        Files.writeString(projectDir.resolve("jk-lock.toml"), "new lock after a dep bump");

        AotCachePackage.discardIfStale(projectDir);

        assertThat(projectDir.resolve("target").resolve("aot-cache")).doesNotExist();
    }

    @Test
    void matching_lock_and_jar_keep_the_cache(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("jk-lock.toml"), "stable lock");
        writeCache(projectDir, cc.jumpkick.host.Hashing.sha256Hex(projectDir.resolve("jk-lock.toml")));

        AotCachePackage.discardIfStale(projectDir);

        assertThat(projectDir.resolve("target").resolve("aot-cache")).isDirectory();
    }

    @Test
    void manifest_without_a_lock_pin_is_unverifiable_and_discarded(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("jk-lock.toml"), "lock");
        writeCache(projectDir, "");
        // Rewrite the manifest without the lock-sha256 line (an old-format cache).
        Path manifest = projectDir.resolve("target").resolve("aot-cache").resolve(AotCachePackage.MANIFEST);
        String stripped = Files.readString(manifest).replaceAll("(?m)^lock-sha256.*\\n", "");
        Files.writeString(manifest, stripped);

        AotCachePackage.discardIfStale(projectDir);

        assertThat(projectDir.resolve("target").resolve("aot-cache")).doesNotExist();
    }
}
