// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.CacheBackend;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The directory that stands in for a shared cache keeps a shared store's write rules, is
 * unreachable when it is not there, and is bound to a request only when the request's shell names
 * it.
 */
class DirectoryCacheBackendTest {

    private static final String KEY = "a".repeat(64);

    @Test
    void outputs_are_written_once_and_a_marker_is_replaced_by_the_latest(@TempDir Path tmp) throws Exception {
        DirectoryCacheBackend dir = new DirectoryCacheBackend(Files.createDirectories(tmp.resolve("remote")));
        Path blob = Files.writeString(tmp.resolve("A.class"), "class A");
        String sha = Hashing.sha256Hex(blob);
        dir.putBlobs(Map.of(sha, blob));
        dir.putRecord(KEY, outputs(sha, "first"));
        dir.putRecord(KEY, outputs(sha, "second"));
        assertThat(dir.records(new CacheBackend.Lookup(List.of(KEY), "linux-x86_64"))
                        .get(KEY))
                .as("a second outputs record under one key is not taken")
                .contains("first");

        String marker = "b".repeat(64);
        dir.putRecord(marker, marker(marker, "1"));
        dir.putRecord(marker, marker(marker, "2"));
        assertThat(dir.records(new CacheBackend.Lookup(List.of(marker), "linux-x86_64"))
                        .get(marker))
                .as("a marker is an observation: the latest stands")
                .contains("OUTPUT 2 tests.total");
    }

    @Test
    void a_directory_that_is_not_there_cannot_be_reached(@TempDir Path tmp) {
        DirectoryCacheBackend dir = new DirectoryCacheBackend(tmp.resolve("absent"));
        assertThatThrownBy(() -> dir.records(new CacheBackend.Lookup(List.of(KEY), "linux-x86_64")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> dir.missing(Map.of(KEY, 1L))).isInstanceOf(IOException.class);
    }

    @Test
    void a_request_gets_a_tier_only_when_its_shell_names_the_directory(@TempDir Path tmp) throws Exception {
        Boolean unbound = RequestShell.where(
                Map.of("PATH", "/bin"), () -> RemoteCacheBinding.around(() -> RemoteCaches.current() == null));
        assertThat(unbound).isTrue();

        RemoteTier seen = RequestShell.where(
                Map.of(RemoteCacheBinding.DIR, tmp.toString()), () -> RemoteCacheBinding.around(RemoteCaches::current));
        assertThat(seen).isNotNull();
        assertThat(seen.name()).isEqualTo("dir:" + tmp);
        assertThat(seen.writes()).as("reads only, unless asked to write").isFalse();
    }

    @Test
    void outputs_for_another_target_are_not_for_this_platform() {
        assertThat(ActionCache.forThisPlatform(null, "linux-x86_64")).isTrue();
        assertThat(ActionCache.forThisPlatform("linux-x86_64", "linux-x86_64")).isTrue();
        assertThat(ActionCache.forThisPlatform("macos-aarch64", "linux-x86_64")).isFalse();
        assertThat(ActionCache.forThisPlatform("linux-x86_64-musl", "linux-x86_64"))
                .as("a libc this jk cannot compare is never taken")
                .isFalse();
    }

    private static String outputs(String sha, String said) {
        return ActionRecordText.render(
                "compile-java@0123456789ab",
                KEY,
                Map.of(),
                Map.of("A.class", sha),
                Set.of(),
                List.of(said),
                ActionRecordText.Meta.now(
                        "java:compile", ActionRecordText.Writes.OUTPUTS, "jk/test", "linux-x86_64", 5, Map.of()));
    }

    private static String marker(String key, String total) {
        return ActionRecordText.render(
                "run-tests@0123456789ab",
                key,
                Map.of(),
                Map.of("tests.total", total, "tests.failed", "0"),
                Set.of(),
                List.of(),
                ActionRecordText.Meta.now(
                        "java:run-tests", ActionRecordText.Writes.TEST_MARKER, "jk/test", "linux-x86_64", 5, Map.of()));
    }
}
