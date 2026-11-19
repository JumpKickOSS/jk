// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.DiskGuard;
import cc.jumpkick.config.HeavyTtl;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.InputGuard;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A packaging step's heavy output (a runtime image, a native executable, a fat jar) stays out of a
 * cache whose volume is nearly full, and the step says so with the volume's figures; with room it
 * is stored. The volume is pinned, so neither case depends on the host's free space.
 */
class PackagingCacheDiskGuardTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void a_heavy_image_on_a_nearly_full_volume_is_not_stored_and_the_step_says_why(@TempDir Path tmp) throws Exception {
        Recording ctx = new Recording();
        String key = store(tmp, new JkCacheConfig.DiskSpace(100 * GIB, GIB), ctx);

        assertThat(cache(tmp).lookup(key)).as("the image stays in target/ only").isEmpty();
        assertThat(ctx.warnings)
                .containsExactly(PackagingCache.DISK_LOW
                        + "=not cached: the disk is nearly full (1.0 GiB free of 100.0 GiB (1%)); the output"
                        + " stays where it was built and JumpKick is reclaiming space");
    }

    @Test
    void a_heavy_image_with_room_is_stored_without_a_word(@TempDir Path tmp) throws Exception {
        Recording ctx = new Recording();
        String key = store(tmp, new JkCacheConfig.DiskSpace(100 * GIB, 50 * GIB), ctx);

        assertThat(cache(tmp).lookup(key)).isPresent();
        assertThat(ctx.warnings).isEmpty();
    }

    /** Stores a heavy image file as package-jlink does, with the cache's volume pinned at {@code space}. */
    private static String store(Path tmp, JkCacheConfig.DiskSpace space, Recording ctx) throws Exception {
        assumeTrue(HeavyTtl.caches(HeavyTtl.resolve()), "heavy outputs are not cached here at all (CI or heavy-ttl 0)");
        Path cache = tmp.resolve("cache");
        Path base = Files.createDirectories(tmp.resolve("target"));
        Path modules =
                heavy(Files.createDirectories(base.resolve("app-jri/lib")).resolve("modules"));
        String task = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JLINK, base.resolve("app-jri"));
        List<String> tokens = List.of("jdk:25");
        String key = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), tokens);
        DiskGuard.Pin pin = DiskGuard.pinForTest(cache, space);
        try {
            SessionContext.where(Session.defaults().withCacheDir(cache), () -> {
                PackagingCache.storePackaged(
                        ctx, InputGuard.none(), cache, task, key, tokens, base, List.of(modules), true, List.of());
                return null;
            });
        } finally {
            pin.close();
        }
        return key;
    }

    private static ActionCache cache(Path tmp) {
        Path cache = tmp.resolve("cache");
        return new ActionCache(JkStores.cacheCas(cache), cache.resolve("actions"));
    }

    /** A sparse file of exactly {@link DiskGuard#HEAVY_BYTES}: heavy by size, cheap on disk. */
    private static Path heavy(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(DiskGuard.HEAVY_BYTES);
        }
        return file;
    }

    /** The step's warnings; everything else is inert. */
    private static final class Recording implements TaskContext {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(@Nullable String description) {}

        @Override
        public void output(@Nullable String line) {}

        @Override
        public void warn(String code, String message) {
            warnings.add(code + "=" + message);
        }

        @Override
        public void error(String code, String message) {
            warnings.add("error " + code + "=" + message);
        }

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public <T> void put(BuildPlanKey<T> key, T value) {}

        @Override
        public <T> Optional<T> get(BuildPlanKey<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> T require(BuildPlanKey<T> key) {
            throw new IllegalStateException("no " + key);
        }
    }
}
