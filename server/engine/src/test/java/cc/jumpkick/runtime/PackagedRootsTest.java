// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin package, packaged or restored, leaves no directory beside it that an earlier package of
 * another variant laid down: a release build's R8 mapping must not sit beside a debug APK it does
 * not describe.
 */
class PackagedRootsTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void a_restored_package_removes_a_directory_only_the_package_before_it_laid_down(@TempDir Path tmp)
            throws Exception {
        Path cache = tmp.resolve("cache");
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path apk = Files.writeString(target.resolve("app-debug.apk"), "debug");
        Path aab = target.resolve("app.aab");
        Path mapping = target.resolve("r8/mapping.txt");

        SessionContext.where(Session.defaults().withCacheDir(cache), () -> {
            // A debug build, packaged: no R8.
            PackagingCache.settleRoots(target, "android", List.of("app-debug.apk"));
            PackagingCache.storePackagedForTest(
                    cache, "debug", "debug-key", List.of(), target, List.of(apk), true, List.of());
            // A minified release build, packaged: R8's mapping beside its bundle.
            Files.writeString(aab, "release");
            Files.createDirectories(mapping.getParent());
            Files.writeString(mapping, "com.example.App -> a.a:\n");
            PackagingCache.settleRoots(target, "android", List.of("app.aab", "r8/mapping.txt"));
            PackagingCache.storePackagedForTest(
                    cache, "release", "release-key", List.of(), target, List.of(aab, mapping), true, List.of());

            // The debug build again, restored from the cache.
            assertThat(PackagingCache.restorePackaged(cache, "debug-key", target, null, "android"))
                    .isTrue();
            assertThat(apk).hasContent("debug");
            assertThat(target.resolve("r8")).doesNotExist();

            // The release build again, restored: its mapping comes back with it.
            assertThat(PackagingCache.restorePackaged(cache, "release-key", target, null, "android"))
                    .isTrue();
            assertThat(mapping).hasContent("com.example.App -> a.a:\n");
            return null;
        });
    }

    @Test
    void a_directory_no_package_of_the_owner_laid_down_is_left_alone(@TempDir Path tmp) throws Exception {
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path classes = Files.writeString(
                Files.createDirectories(target.resolve("classes")).resolve("A.class"), "x");
        Path mapping =
                Files.writeString(Files.createDirectories(target.resolve("r8")).resolve("mapping.txt"), "m");

        PackagingCache.settleRoots(target, "android", List.of("app.aab", "r8/mapping.txt"));
        PackagingCache.settleRoots(target, "quarkus", List.of("app.jar"));
        PackagingCache.settleRoots(target, "android", List.of("app.aab", "r8/mapping.txt"));

        assertThat(classes).exists();
        assertThat(mapping).exists();
    }
}
