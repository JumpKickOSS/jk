// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
    void worker_pom_change_is_a_different_index(@TempDir Path tmp) throws Exception {
        Path worker = tmp.resolve("jk-formatter.jar");
        Path pom = tmp.resolve("jk-formatter.pom");
        Files.writeString(worker, "thin");
        Files.writeString(pom, "<project><artifactId>jk-formatter</artifactId><version>1</version></project>\n");

        String before = FormatFreshnessIndex.configKey(
                "palantir", "2.80.0", "kotlinlang", "0.61", true, true, true, null, worker);

        Files.writeString(pom, "<project><artifactId>jk-formatter</artifactId><version>2</version></project>\n");
        String after = FormatFreshnessIndex.configKey(
                "palantir", "2.80.0", "kotlinlang", "0.61", true, true, true, null, worker);

        assertThat(after).isNotEqualTo(before);
    }

    /**
     * Re-materialising the worker into the store is not a new formatter. Keying the index on the
     * jar's stat instead of its bytes minted a fresh index on every re-fetch and orphaned the old
     * one, which is most of what the tier was holding.
     */
    @Test
    void the_same_worker_bytes_are_the_same_index(@TempDir Path tmp) throws Exception {
        Path first = tmp.resolve("first/jk-formatter.jar");
        Path second = tmp.resolve("second/jk-formatter.jar");
        for (Path jar : List.of(first, second)) {
            Files.createDirectories(jar.getParent());
            Files.writeString(jar, "thin");
            Files.writeString(siblingPom(jar), "<project><artifactId>jk-formatter</artifactId></project>\n");
        }
        Files.setLastModifiedTime(second, FileTime.fromMillis(System.currentTimeMillis() - 86_400_000L));

        assertThat(keyFor(second)).isEqualTo(keyFor(first));

        Files.writeString(second, "thnn"); // same length, different formatter
        assertThat(keyFor(second)).isNotEqualTo(keyFor(first));
    }

    private static Path siblingPom(Path jar) {
        String name = jar.getFileName().toString();
        return jar.resolveSibling(name.substring(0, name.length() - 4) + ".pom");
    }

    private static String keyFor(Path workerJar) {
        return FormatFreshnessIndex.configKey(
                "palantir", "2.80.0", "kotlinlang", "0.61", true, true, true, null, workerJar);
    }
}
