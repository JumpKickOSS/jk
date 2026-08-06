// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Clean-skip must notice a resource tree that drifted from its copy in the output dir. */
class TaskForecasterResourceTest {

    @Test
    void in_sync_copy_is_clean(@TempDir Path tmp) throws Exception {
        Path res = Files.createDirectories(tmp.resolve("res"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(res.resolve("app.properties"), "a=1\n");
        Files.writeString(out.resolve("app.properties"), "a=1\n");
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isFalse();
    }

    @Test
    void missing_copy_is_dirty(@TempDir Path tmp) throws Exception {
        Path res = Files.createDirectories(tmp.resolve("res"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(res.resolve("new.txt"), "x\n");
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isTrue();
    }

    @Test
    void size_change_is_dirty(@TempDir Path tmp) throws Exception {
        Path res = Files.createDirectories(tmp.resolve("res"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(res.resolve("f"), "longer content\n");
        Files.writeString(out.resolve("f"), "short\n");
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isTrue();
    }

    @Test
    void same_size_newer_source_with_different_bytes_is_dirty(@TempDir Path tmp) throws Exception {
        Path res = Files.createDirectories(tmp.resolve("res"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(out.resolve("f"), "a=1\n");
        Files.writeString(res.resolve("f"), "a=2\n");
        Files.setLastModifiedTime(out.resolve("f"), FileTime.fromMillis(1_000_000));
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isTrue();
    }

    @Test
    void nested_dirs_and_absent_resource_root_are_handled(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path out = Files.createDirectories(tmp.resolve("out"));
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isFalse(); // no resources at all
        Files.createDirectories(res.resolve("sub"));
        Files.writeString(res.resolve("sub/deep.txt"), "d\n");
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isTrue();
        Files.createDirectories(out.resolve("sub"));
        Files.writeString(out.resolve("sub/deep.txt"), "d\n");
        assertThat(TaskForecaster.resourcesOutOfSync(res, out)).isFalse();
    }
}
