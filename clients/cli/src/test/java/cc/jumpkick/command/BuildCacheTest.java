// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class BuildCacheTest {

    @Test
    void second_build_is_up_to_date_via_freshness_stamp(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "hi"; } }
                """);

        // Backdate the source so its mtime is unambiguously older than the
        // freshness stamp. Without this the test races filesystem mtime
        // granularity (a coarse mount can truncate the source's mtime into the
        // same second as the stamp), making the fast-skip nondeterministic.
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 5_000));

        Path cache = tempDir.resolve("cache");
        // First build: stamp is absent, action cache misses, real compile.
        int first = run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());
        assertThat(first).isEqualTo(0);

        // Second build: stamp is fresh, no input newer → fast skip without
        // even hashing source content for an action-key lookup.
        String stdout = Capture.stdout(() -> run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).contains("project up to date");
    }

    /**
     * A restore from backup or an {@code mv} of an older copy puts new bytes behind an older
     * mtime; the build compiles them rather than keeping the classes of the bytes it replaced.
     */
    @Test
    void a_source_replaced_under_an_older_mtime_is_recompiled(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "first"; } }
                """);
        long settled = System.currentTimeMillis() - 60_000;
        Files.setLastModifiedTime(src, FileTime.fromMillis(settled));
        Path cache = tempDir.resolve("cache");
        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        Path classFile = tempDir.resolve("target/classes/example/Hello.class");
        assertThat(Files.readString(classFile, StandardCharsets.ISO_8859_1)).contains("first");

        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "restored-from-backup"; } }
                """);
        Files.setLastModifiedTime(src, FileTime.fromMillis(settled - 60_000));
        String stdout = Capture.stdout(() -> run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).doesNotContain("project up to date");
        assertThat(Files.readString(classFile, StandardCharsets.ISO_8859_1)).contains("restored-from-backup");
    }

    @Test
    void rebuild_flag_bypasses_the_up_to_date_fast_path(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "hi"; } }
                """);

        Path cache = tempDir.resolve("cache");
        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 5_000));

        // --redo: stamps and action cache are distrusted — a genuine recompile, never the
        // "project up to date" fast path (and unlike --force, no dependency re-fetch).
        // (--rebuild remains a supported alias for --redo.)
        String stdout =
                Capture.stdout(() -> run("build", "--redo", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).doesNotContain("project up to date");
        assertThat(stdout).contains("Build successful");
    }

    @Test
    void wiping_classes_dir_falls_through_to_action_cache(@TempDir Path tempDir) throws Exception {
        // The freshness stamp lives inside the classes dir; deleting the
        // dir (e.g. `jk clean`) removes the stamp and forces the action-key
        // path, which restores from CAS on a hit.
        run("new", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "hi"; } }
                """);

        Path cache = tempDir.resolve("cache");
        run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());

        // Simulate `jk clean` — wipe both the intermediates (build/, where
        // the freshness stamp lives) and the artifacts (target/).
        deleteRecursively(tempDir.resolve("build"));
        deleteRecursively(tempDir.resolve("target"));

        String stdout = Capture.stdout(() -> run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).contains("Built");
    }

    /**
     * The action cache is every checkout's: clean, with or without --force, leaves it; a forced
     * build runs every step without reading it and overwrites what it produces, deleting nothing.
     */
    @Test
    void clean_leaves_the_shared_action_cache_and_a_forced_build_overwrites_it(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "hi"; } }
                """);
        Path cache = tempDir.resolve("cache");
        run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());
        Path keys = cache.resolve("actions/keys");
        long built = countKeys(keys);
        assertThat(built).as("build populated the action cache").isPositive();

        assertThat(run("clean", "-C", tempDir.toString())).isEqualTo(0);
        assertThat(tempDir.resolve("target")).doesNotExist();
        assertThat(countKeys(keys)).isEqualTo(built);

        assertThat(run("clean", "--force", "-C", tempDir.toString())).isEqualTo(0);
        assertThat(countKeys(keys))
                .as("clean --force leaves every checkout's records")
                .isEqualTo(built);

        assertThat(run("build", "--force", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        assertThat(countKeys(keys))
                .as("a forced build overwrites, deleting nothing")
                .isGreaterThanOrEqualTo(built);
    }

    /** Count action-cache key files; always closes the directory stream (macOS handle leak). */
    private static long countKeys(Path keys) throws IOException {
        if (!Files.isDirectory(keys)) return 0;
        try (var stream = Files.list(keys)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void editing_a_source_invalidates_cache(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "hi"; } }
                """);

        Path cache = tempDir.resolve("cache");
        run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());

        // Edit the source — cache should miss.
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "ho"; } }
                """);
        // Forward-date the edit so its mtime is unambiguously newer than the
        // freshness stamp. Without this the test races filesystem mtime
        // granularity (a coarse mount truncates the edit into the same second
        // as the stamp, comparing below the millisecond-precise stamp clock),
        // which let a real content change look "up to date".
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        String stdout = Capture.stdout(() -> run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).doesNotContain("Cache hit");
        assertThat(stdout).contains("Built");
    }
}
