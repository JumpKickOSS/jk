// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeavyActionGcTest {

    @Test
    void ttl_deletes_stale_class_c_keys(@TempDir Path root) throws Exception {
        Path cache = root.resolve("cache");
        Cas cas = new Cas(cache);
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
        Path out = root.resolve("out");
        Files.createDirectories(out);
        Path bin = out.resolve("app");
        Files.writeString(bin, "native-binary-payload");
        String task = TaskNames.NATIVE_IMAGE + "@mod";
        var rec = ac.storeArtifacts(task, "key-old", Map.of(), out, List.of(bin));
        Path keyFile = cache.resolve("actions/keys").resolve(rec.actionKey());
        Files.setLastModifiedTime(
                keyFile,
                FileTime.fromMillis(
                        System.currentTimeMillis() - Duration.ofDays(10).toMillis()));

        var report = HeavyActionGc.sweep(cache, cas, 0L, Duration.ofDays(3), false);

        assertThat(report.deletedKeys()).isEqualTo(1);
        assertThat(Files.exists(keyFile)).isFalse();
    }

    @Test
    void native_keeps_two_generations_on_store(@TempDir Path root) throws Exception {
        Path cache = root.resolve("cache");
        Cas cas = new Cas(cache);
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
        Path out = root.resolve("out");
        Files.createDirectories(out);
        String task = TaskNames.NATIVE_IMAGE + "@mod";

        Path a = out.resolve("app");
        Files.writeString(a, "gen-one");
        var r1 = ac.storeArtifacts(task, "key-1", Map.of(), out, List.of(a));

        Files.writeString(a, "gen-two");
        var r2 = ac.storeArtifacts(task, "key-2", Map.of(), out, List.of(a));

        Files.writeString(a, "gen-three");
        var r3 = ac.storeArtifacts(task, "key-3", Map.of(), out, List.of(a));

        Path keys = cache.resolve("actions/keys");
        // 2 generations: current key-3 + one predecessor key-2; key-1 dropped
        assertThat(Files.exists(keys.resolve(r3.actionKey()))).isTrue();
        assertThat(Files.exists(keys.resolve(r2.actionKey()))).isTrue();
        assertThat(Files.exists(keys.resolve(r1.actionKey()))).isFalse();
    }

    @Test
    void purgeAll_deletes_every_class_c_key(@TempDir Path root) throws Exception {
        Path cache = root.resolve("cache");
        Cas cas = new Cas(cache);
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
        Path out = root.resolve("out");
        Files.createDirectories(out);
        Path bin = out.resolve("app");
        Files.writeString(bin, "fresh-native");
        String task = TaskNames.NATIVE_IMAGE + "@mod";
        var rec = ac.storeArtifacts(task, "key-fresh", Map.of(), out, List.of(bin));
        Path keyFile = cache.resolve("actions/keys").resolve(rec.actionKey());

        var report = HeavyActionGc.purgeAll(cache, cas, false);

        assertThat(report.deletedKeys()).isEqualTo(1);
        assertThat(Files.exists(keyFile)).isFalse();
    }

    @Test
    void write_image_keeps_one_generation(@TempDir Path root) throws Exception {
        Path cache = root.resolve("cache");
        Cas cas = new Cas(cache);
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
        Path out = root.resolve("out");
        Files.createDirectories(out);
        String task = TaskNames.WRITE_IMAGE + "@mod";

        Path tar = out.resolve("img.tar");
        Files.writeString(tar, "image-v1");
        var r1 = ac.storeArtifacts(task, "img-1", Map.of(), out, List.of(tar));
        Files.writeString(tar, "image-v2");
        var r2 = ac.storeArtifacts(task, "img-2", Map.of(), out, List.of(tar));

        Path keys = cache.resolve("actions/keys");
        assertThat(Files.exists(keys.resolve(r2.actionKey()))).isTrue();
        assertThat(Files.exists(keys.resolve(r1.actionKey()))).isFalse();
    }
}
