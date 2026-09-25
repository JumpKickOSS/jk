// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A resource the mirror recorded is not a compile output. */
class MirroredOutputsTest {

    @Test
    void a_recorded_resource_is_dropped_and_a_class_stays(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes/main"));
        Path ledger = tmp.resolve("incremental").resolve(MirroredOutputs.MAIN_LEDGER);
        Files.createDirectories(ledger.getParent());
        Files.writeString(ledger, "application.properties\nMETA-INF/services/com.example.Spi\n");

        Map<String, String> kept = MirroredOutputs.without(
                classes,
                Map.of(
                        "com/example/App.class", "abc",
                        "application.properties", "def",
                        "META-INF/services/com.example.Spi", "ghi"));

        assertThat(kept).containsOnlyKeys("com/example/App.class");
    }

    @Test
    void a_directory_that_is_not_a_classes_tree_is_unchanged(@TempDir Path tmp) throws Exception {
        Path out = Files.createDirectories(tmp.resolve("kotlin/main"));
        Map<String, String> outputs = Map.of("App.class", "abc", "application.properties", "def");
        assertThat(MirroredOutputs.without(out, outputs)).isEqualTo(outputs);
    }

    @Test
    void a_restore_does_not_rewrite_or_drop_a_mirrored_resource(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("target/classes/main"));
        Files.createDirectories(classes.resolve("com/example"));
        Files.writeString(classes.resolve("com/example/App.class"), "class-v1");
        Files.writeString(classes.resolve("application.properties"), "from-the-compile");
        Path cache = tmp.resolve("cache");
        ActionCache ac = new ActionCache(new Cas(cache.resolve("cas")), cache.resolve("actions"));
        ActionCache.ActionRecord record = ac.store("compile-main", "key-1", Map.of(), classes);

        Path ledger = tmp.resolve("target/incremental").resolve(MirroredOutputs.MAIN_LEDGER);
        Files.createDirectories(ledger.getParent());
        Files.writeString(ledger, "application.properties\n");
        Files.writeString(classes.resolve("application.properties"), "from-the-mirror");
        FileTime kept = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(classes.resolve("application.properties"), kept);
        Files.createDirectories(classes.resolve("stale"));
        Files.writeString(classes.resolve("stale/Old.class"), "stale");

        assertThat(ac.restore(record, classes)).isTrue();
        assertThat(Files.readString(classes.resolve("application.properties"))).isEqualTo("from-the-mirror");
        assertThat(Files.getLastModifiedTime(classes.resolve("application.properties")))
                .isEqualTo(kept);
        assertThat(classes.resolve("com/example/App.class")).isRegularFile();
        assertThat(classes.resolve("stale/Old.class")).doesNotExist();
    }
}
