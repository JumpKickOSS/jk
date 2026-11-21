// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lock that chose its Gradle variants for a build JDK is stale once the build runs on a JDK that
 * would choose others, and fresh on any JDK that chooses the same ones.
 */
class AutoLockBuildJdkTest {

    @AfterEach
    void forgetTheBuildJdk() {
        LockFreshness.installBuildJdk(owner -> 0);
    }

    @Test
    void a_build_on_a_jdk_the_variants_do_not_hold_for_relocks(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "0.1.0"
                """);
        Path lockFile = dir.resolve("jk-lock.toml");
        AutoLock.installBuildJdk();
        int jdk = AutoLock.buildJdkLevel(dir);

        LockfileWriter.write(Lockfile.empty("test").withVariants(new Lockfile.VariantJdk(jdk, jdk, jdk)), lockFile);
        boolean heldStale = AutoLock.isStale(dir, lockFile);
        LockfileWriter.write(
                Lockfile.empty("test").withVariants(new Lockfile.VariantJdk(jdk - 1, 0, jdk - 1)), lockFile);
        String movedWhy = AutoLock.staleReason(dir, lockFile);

        assertThat(jdk).isPositive();
        assertThat(heldStale).isFalse();
        assertThat(movedWhy).startsWith("jk-lock.toml is stale: its Gradle variants were chosen for JDK ");
        assertThat(LockFreshness.jdkMoved(LockFreshness.staleReason(dir, lockFile)))
                .isTrue();
    }
}
