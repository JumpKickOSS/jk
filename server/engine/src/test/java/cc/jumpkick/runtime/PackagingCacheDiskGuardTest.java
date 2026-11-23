// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.DiskGuard;
import cc.jumpkick.config.HeavyTtl;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.config.RunNotices;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.task.KeyContract;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A packaging step's heavy output (a runtime image, a native executable, a fat jar) stays out of a
 * cache whose volume is nearly full, and the build says so once with the volume's figures; with
 * room it is stored. The volume is pinned, so neither case depends on the host's free space. {@code jk
 * explain} names whichever of the disk, {@code [cache] heavy-ttl} and {@code [build]
 * cache-heavy-outputs} keeps a heavy output out, and the build says once which setting did.
 */
class PackagingCacheDiskGuardTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void a_heavy_image_on_a_nearly_full_volume_is_not_stored(@TempDir Path tmp) throws Exception {
        Recording ctx = new Recording();
        String key = store(tmp, new JkCacheConfig.DiskSpace(100 * GIB, GIB), ctx);

        assertThat(cache(tmp).lookup(key)).as("the image stays in target/ only").isEmpty();
        assertThat(ctx.warnings).as("the build says it, not the step").isEmpty();
    }

    @Test
    void a_build_says_once_that_a_nearly_full_disk_keeps_heavy_outputs_out(@TempDir Path tmp) throws Exception {
        List<String> notices = refusals(tmp, null, HeavyTtl.DEFAULT, new JkCacheConfig.DiskSpace(100 * GIB, GIB), 2);

        assertThat(notices)
                .as("two heavy outputs, one notice")
                .containsExactly(PackagingCache.DISK_LOW
                        + "=heavy outputs not cached: the disk is nearly full (1.0 GiB free of 100.0 GiB (1%));"
                        + " they stay where they were built and JumpKick is reclaiming space");
    }

    @Test
    void a_heavy_image_with_room_is_stored_without_a_word(@TempDir Path tmp) throws Exception {
        Recording ctx = new Recording();
        String key = store(tmp, new JkCacheConfig.DiskSpace(100 * GIB, 50 * GIB), ctx);

        assertThat(cache(tmp).lookup(key)).isPresent();
        assertThat(ctx.warnings).isEmpty();
    }

    @Test
    void explain_names_the_disk_when_no_heavy_output_can_be_cached(@TempDir Path tmp) {
        Path cache = tmp.resolve("cache");
        try (DiskGuard.Pin full = DiskGuard.pinForTest(cache, new JkCacheConfig.DiskSpace(100 * GIB, GIB))) {
            assertThat(PackagingCache.heavyNotCached(cache, null, HeavyTtl.DEFAULT))
                    .isEqualTo("not cached: the disk is nearly full (1.0 GiB free of 100.0 GiB (1%))");
        }
        try (DiskGuard.Pin room =
                DiskGuard.pinForTest(cache, new JkCacheConfig.DiskSpace(228 * GIB, (long) (22.7 * GIB)))) {
            assertThat(PackagingCache.heavyNotCached(cache, null, HeavyTtl.DEFAULT))
                    .as("under 10% of a laptop disk, but 22.7 GiB of room")
                    .isNull();
        }
    }

    @Test
    void explain_names_the_setting_that_keeps_heavy_outputs_out_whatever_the_disk_holds(@TempDir Path tmp)
            throws Exception {
        Path cache = tmp.resolve("cache");
        Path dir = Files.createDirectories(tmp.resolve("app"));
        JkBuild optedOut = JkBuildParser.parse(Files.writeString(dir.resolve("jk.toml"), """
                name = "app"
                group = "g"
                version = "1.0"

                [build]
                cache-heavy-outputs = false
                """));
        try (DiskGuard.Pin room = DiskGuard.pinForTest(cache, new JkCacheConfig.DiskSpace(100 * GIB, 50 * GIB))) {
            assertThat(PackagingCache.heavyNotCached(cache, null, Duration.ZERO))
                    .as("the machine keeps no heavy output, as on CI")
                    .isEqualTo("not cached: [cache] heavy-ttl = 0");
            assertThat(PackagingCache.heavyNotCached(cache, optedOut, HeavyTtl.DEFAULT))
                    .isEqualTo("not cached: [build] cache-heavy-outputs = false");
            assertThat(PackagingCache.heavyNotCached(cache, optedOut, Duration.ZERO))
                    .as("the machine's setting is named first")
                    .isEqualTo("not cached: [cache] heavy-ttl = 0");
        }
    }

    @Test
    void a_build_says_once_that_the_machine_keeps_heavy_outputs_out(@TempDir Path tmp) throws Exception {
        List<String> notices = refusals(tmp, null, Duration.ZERO, ROOM, 2);

        assertThat(notices)
                .as("two heavy outputs, one notice")
                .containsExactly(PackagingCache.HEAVY_NOT_CACHED + "=heavy outputs not cached: [cache] heavy-ttl = 0");
    }

    @Test
    void a_build_says_once_which_module_keeps_its_heavy_outputs_out(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("app"));
        JkBuild optedOut = JkBuildParser.parse(Files.writeString(dir.resolve("jk.toml"), """
                name = "app"
                group = "g"
                version = "1.0"

                [build]
                cache-heavy-outputs = false
                """));
        List<String> notices = refusals(tmp, optedOut, HeavyTtl.DEFAULT, ROOM, 2);

        assertThat(notices)
                .containsExactly(PackagingCache.HEAVY_NOT_CACHED
                        + ":app=app: heavy outputs not cached: [build] cache-heavy-outputs = false");
    }

    private static final JkCacheConfig.DiskSpace ROOM = new JkCacheConfig.DiskSpace(100 * GIB, 50 * GIB);

    /**
     * Offers {@code times} Class-C outputs of {@code project} to the cache in one run under {@code
     * heavyTtl} on a volume with {@code space}, each refused, and returns the run's notices as
     * {@code key=message}.
     */
    private static List<String> refusals(
            Path tmp, @Nullable JkBuild project, Duration heavyTtl, JkCacheConfig.DiskSpace space, int times)
            throws Exception {
        Path cache = tmp.resolve("cache");
        Path base = Files.createDirectories(tmp.resolve("target"));
        List<String> notices = new ArrayList<>();
        try (DiskGuard.Pin pin = DiskGuard.pinForTest(cache, space)) {
            SessionContext.where(Session.defaults().withCacheDir(cache), () -> {
                RunNotices.openSink(SessionContext.current().io(), (key, message) -> notices.add(key + "=" + message));
                for (int i = 0; i < times; i++) {
                    Path jar = heavy(base.resolve("app-" + i + "-all.jar"));
                    String task = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_ASSEMBLY, jar);
                    assertThat(PackagingCache.admitted(cache, List.of(jar), task, project, heavyTtl))
                            .isFalse();
                }
                return null;
            });
        }
        return notices;
    }

    @Test
    void a_heavy_image_under_ten_percent_of_a_large_volume_is_stored(@TempDir Path tmp) throws Exception {
        Recording ctx = new Recording();
        String key = store(tmp, new JkCacheConfig.DiskSpace(228 * GIB, (long) (22.7 * GIB)), ctx);

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
        String key = ActionKey.forArtifact(task, KeyContract.ARTIFACT.keyVersion(), tokens);
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
