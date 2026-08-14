// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormatFreshnessIndexTest {

    @Test
    void first_pass_is_all_dirty_then_clean_until_mtime_changes(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("proj");
        Path cache = tmp.resolve("cache");
        Path src = project.resolve("src");
        Files.createDirectories(src);
        Path a = src.resolve("A.java");
        Path b = src.resolve("B.java");
        Files.writeString(a, "class A {}");
        Files.writeString(b, "class B {}");

        String key = FormatFreshnessIndex.configKey(
                "palantir", "2.80.0", "kotlinlang", "0.61", true, true, true, null, null);
        FormatFreshnessIndex idx = FormatFreshnessIndex.open(cache, project, key);
        FormatFreshnessIndex.Split first = idx.partition(List.of(a, b), List.of());
        assertThat(first.dirtyJava()).containsExactly(a, b);
        assertThat(first.clean()).isZero();

        idx.record(a);
        idx.record(b);
        idx.save();

        FormatFreshnessIndex reloaded = FormatFreshnessIndex.open(cache, project, key);
        FormatFreshnessIndex.Split second = reloaded.partition(List.of(a, b), List.of());
        assertThat(second.dirtyJava()).isEmpty();
        assertThat(second.clean()).isEqualTo(2);

        Files.writeString(a, "class A { int x; }");
        FormatFreshnessIndex afterEdit = FormatFreshnessIndex.open(cache, project, key);
        FormatFreshnessIndex.Split third = afterEdit.partition(List.of(a, b), List.of());
        assertThat(third.dirtyJava()).containsExactly(a);
        assertThat(third.clean()).isEqualTo(1);
    }

    @Test
    void config_change_is_a_different_index(@TempDir Path tmp) throws IOException {
        Path project = tmp.resolve("proj");
        Files.createDirectories(project);
        Path src = project.resolve("A.java");
        Files.writeString(src, "class A {}");

        String palantir = FormatFreshnessIndex.configKey(
                "palantir", "2.80.0", "kotlinlang", "0.61", true, true, true, null, null);
        String google =
                FormatFreshnessIndex.configKey("google", "1.28.0", "kotlinlang", "0.61", true, true, true, null, null);
        assertThat(palantir).isNotEqualTo(google);

        FormatFreshnessIndex idx = FormatFreshnessIndex.open(tmp.resolve("cache"), project, palantir);
        idx.record(src);
        idx.save();

        FormatFreshnessIndex other = FormatFreshnessIndex.open(tmp.resolve("cache"), project, google);
        assertThat(other.partition(List.of(src), List.of()).dirtyJava()).containsExactly(src);
    }

    @Test
    void worker_classpath_sidecar_change_is_a_different_index(@TempDir Path tmp) throws Exception {
        Path worker = tmp.resolve("jk-formatter.jar");
        Path sidecar = Path.of(worker + ".classpath");
        Files.writeString(worker, "thin");
        Files.writeString(sidecar, "/lib/rewrite-java-8.56.1.jar\n");

        String before = FormatFreshnessIndex.configKey(
                "palantir", "2.80.0", "kotlinlang", "0.61", true, true, true, null, worker);

        Files.writeString(sidecar, "/lib/rewrite-java-8.89.2.jar\n");
        String after = FormatFreshnessIndex.configKey(
                "palantir", "2.80.0", "kotlinlang", "0.61", true, true, true, null, worker);

        assertThat(after).isNotEqualTo(before);
    }
}
