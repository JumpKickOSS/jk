// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.task.ActionCache.ActionRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1258: restoring an artifact that is already byte-identical on disk must not rewrite it.
 *
 * <p>{@code restoreArtifacts} used to unconditionally delete-and-copy on every cache <em>hit</em>,
 * giving an unchanged jar a fresh mtime each build. Because {@link FreshnessStamp} compares
 * classpath entries by mtime, that invalidated every downstream stamp — so a workspace's KSP round
 * and Kotlin compile re-ran on every build even though nothing had changed.
 */
class ActionCacheRestoreTest {

    @Test
    void restoring_an_identical_artifact_leaves_its_mtime_untouched(@TempDir Path tmp) throws Exception {
        Path cacheRoot = tmp.resolve("cache");
        Path baseDir = Files.createDirectories(tmp.resolve("out"));
        Path artifact = baseDir.resolve("lib/thing-1.0.0.jar");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "stable contents");

        ActionCache ac = new ActionCache(new Cas(cacheRoot), cacheRoot.resolve("actions"));
        ActionRecord record = ac.storeArtifacts("package-jar", "key-1", Map.of(), baseDir, List.of(artifact));

        FileTime before = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(artifact, before);

        assertThat(ac.restoreArtifacts(record, baseDir)).isTrue();

        assertThat(Files.getLastModifiedTime(artifact)).isEqualTo(before);
        assertThat(Files.readString(artifact)).isEqualTo("stable contents");
    }

    @Test
    void restoring_over_different_content_still_replaces_it(@TempDir Path tmp) throws Exception {
        Path cacheRoot = tmp.resolve("cache");
        Path baseDir = Files.createDirectories(tmp.resolve("out"));
        Path artifact = baseDir.resolve("lib/thing-1.0.0.jar");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "cached contents");

        ActionCache ac = new ActionCache(new Cas(cacheRoot), cacheRoot.resolve("actions"));
        ActionRecord record = ac.storeArtifacts("package-jar", "key-1", Map.of(), baseDir, List.of(artifact));

        // Someone clobbered the output: the restore must win.
        Files.writeString(artifact, "stale rubbish");
        assertThat(ac.restoreArtifacts(record, baseDir)).isTrue();
        assertThat(Files.readString(artifact)).isEqualTo("cached contents");
    }

    @Test
    void restoring_a_same_size_but_different_artifact_replaces_it(@TempDir Path tmp) throws Exception {
        // The size pre-check must not be mistaken for an equality check.
        Path cacheRoot = tmp.resolve("cache");
        Path baseDir = Files.createDirectories(tmp.resolve("out"));
        Path artifact = baseDir.resolve("a.bin");
        Files.writeString(artifact, "AAAA");

        ActionCache ac = new ActionCache(new Cas(cacheRoot), cacheRoot.resolve("actions"));
        ActionRecord record = ac.storeArtifacts("t", "key-1", Map.of(), baseDir, List.of(artifact));

        Files.writeString(artifact, "BBBB"); // same length, different bytes
        assertThat(ac.restoreArtifacts(record, baseDir)).isTrue();
        assertThat(Files.readString(artifact)).isEqualTo("AAAA");
    }

    @Test
    void restoring_a_missing_artifact_recreates_it(@TempDir Path tmp) throws Exception {
        Path cacheRoot = tmp.resolve("cache");
        Path baseDir = Files.createDirectories(tmp.resolve("out"));
        Path artifact = baseDir.resolve("lib/thing.jar");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "contents");

        ActionCache ac = new ActionCache(new Cas(cacheRoot), cacheRoot.resolve("actions"));
        ActionRecord record = ac.storeArtifacts("package-jar", "key-1", Map.of(), baseDir, List.of(artifact));

        Files.delete(artifact);
        assertThat(ac.restoreArtifacts(record, baseDir)).isTrue();
        assertThat(Files.readString(artifact)).isEqualTo("contents");
    }
}
