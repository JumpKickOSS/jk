// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.runtime.base.LockMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A stale lock a newer jk wrote is not relocked by this one, on either entry: the build's
 * invisible freshen fails with the refusal instead of writing an older-format lock, and the
 * pipeline every lock verb runs refuses before it resolves. Nothing is fetched, so no repository
 * is needed.
 */
class LockRewriteRefusalTest {

    private static final String NEWER = "99.0.0";

    /** A project whose lock a newer jk wrote, stamped for other manifests so it reads as stale. */
    private static Path staleProjectLockedByNewerJk(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.acme"
                name = "app"
                version = "0.1.0"
                """);
        Path lock = tmp.resolve("jk-lock.toml");
        LockfileWriter.write(Lockfile.empty(NEWER), lock, "0".repeat(64));
        LockfileReader.clearCache();
        return lock;
    }

    @Test
    void the_builds_freshen_fails_instead_of_relocking(@TempDir Path tmp) throws Exception {
        Path lock = staleProjectLockedByNewerJk(tmp);
        Lockfile existing = LockfileReader.read(lock);
        assertThat(AutoLock.isStale(tmp, lock)).isTrue();

        assertThatThrownBy(() -> AutoLock.maybeReLock(
                        tmp, existing, lock, tmp.resolve("cache"), null, List.of(), true, ResolveObserver.NOOP, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("written by jk " + NEWER)
                .hasMessageContaining("this engine is jk " + JkVersion.VERSION);
        assertThat(LockfileReader.read(lock).generatedBy())
                .as("the lock on disk is untouched")
                .isEqualTo("jk " + NEWER);
    }

    @Test
    void the_pipeline_refuses_before_it_resolves(@TempDir Path tmp) throws Exception {
        Path lock = staleProjectLockedByNewerJk(tmp);
        LockPlans.LockScope scope = LockPlans.lockScope(tmp);
        LockPipeline pipeline = new LockPipeline(
                scope.lockDir(),
                scope.effective(),
                tmp.resolve("cache"),
                null,
                List.of(),
                true,
                new LockMode.Keep(false));

        assertThatThrownBy(() ->
                        pipeline.run(LockfileReader.read(lock), ResolveObserver.NOOP, LockPipeline.Progress.SILENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("written by jk " + NEWER);
        assertThat(LockfileReader.read(lock).generatedBy()).isEqualTo("jk " + NEWER);
    }
}
