// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A resource the mirror recorded, or a class another compiler merged in, is not a compile output. */
class MirroredOutputsTest {

    @Test
    void a_class_a_language_merge_recorded_is_dropped_from_the_javac_record(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("target/classes"));
        Path incremental = Files.createDirectories(tmp.resolve("target/incremental"));
        Files.writeString(
                incremental.resolve(MirroredOutputs.mergedLedger("kotlin")), "com/x/A.class\ncom/x/B.class\n");
        Files.writeString(incremental.resolve(MirroredOutputs.mergedLedger("groovy")), "com/x/G.class\n");
        Files.writeString(incremental.resolve(MirroredOutputs.mergedLedger("build-logic-gen")), "gen/Meta.class\n");
        // The test tree's merge names the same relative path as a main class; it is not main's.
        Files.writeString(incremental.resolve(MirroredOutputs.mergedLedger("kotlin-test")), "com/x/J.class\n");

        Map<String, String> kept = MirroredOutputs.without(
                classes,
                Map.of(
                        "com/x/J.class", "j",
                        "com/x/A.class", "a",
                        "com/x/B.class", "b",
                        "com/x/G.class", "g",
                        "gen/Meta.class", "m"));

        assertThat(kept).containsOnlyKeys("com/x/J.class");
    }

    @Test
    void a_test_tree_is_held_to_its_own_merge_ledgers(@TempDir Path tmp) throws Exception {
        Path testClasses = Files.createDirectories(tmp.resolve("target/test-classes"));
        Path incremental = Files.createDirectories(tmp.resolve("target/incremental"));
        Files.writeString(incremental.resolve(MirroredOutputs.mergedLedger("kotlin")), "com/x/A.class\n");
        Files.writeString(incremental.resolve(MirroredOutputs.mergedLedger("kotlin-test")), "com/x/ATest.class\n");
        Files.writeString(incremental.resolve(MirroredOutputs.TEST_LEDGER), "fixture.txt\n");

        Map<String, String> kept = MirroredOutputs.without(
                testClasses,
                Map.of(
                        "com/x/A.class", "a",
                        "com/x/ATest.class", "t",
                        "com/x/JTest.class", "j",
                        "fixture.txt", "f"));

        assertThat(kept).containsOnlyKeys("com/x/A.class", "com/x/JTest.class");
    }

    @Test
    void a_restore_leaves_a_merged_class_to_its_merge(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("target/classes"));
        Files.createDirectories(classes.resolve("com/x"));
        Files.writeString(classes.resolve("com/x/J.class"), "javac");
        Files.writeString(classes.resolve("com/x/A.class"), "kotlin-v1");
        Files.writeString(classes.resolve("com/x/B.class"), "kotlin-v1");
        Path incremental = Files.createDirectories(tmp.resolve("target/incremental"));
        Files.writeString(
                incremental.resolve(MirroredOutputs.mergedLedger("kotlin")), "com/x/A.class\ncom/x/B.class\n");
        Path cache = tmp.resolve("cache");
        Cas cas = new Cas(cache.resolve("cas"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
        // javac ran in the merged tree; its record is the tree less what the merge ledger owns.
        Map<String, String> snapshot = new TreeMap<>();
        for (String rel : List.of("com/x/J.class", "com/x/A.class", "com/x/B.class")) {
            snapshot.put(
                    rel,
                    cas.hashFromPath(cas.put(Files.readAllBytes(classes.resolve(rel))))
                            .orElseThrow());
        }
        ActionCache.ActionRecord record =
                ac.storeWithOutputs("compile-main", "key-1", Map.of(), MirroredOutputs.without(classes, snapshot));
        assertThat(record.outputs()).containsOnlyKeys("com/x/J.class");

        // The Kotlin declaration B was dropped: the merge removed it, rewrote A and its ledger.
        Files.writeString(incremental.resolve(MirroredOutputs.mergedLedger("kotlin")), "com/x/A.class\n");
        Files.writeString(classes.resolve("com/x/A.class"), "kotlin-v2");
        Files.delete(classes.resolve("com/x/B.class"));
        Files.writeString(classes.resolve("com/x/J.class"), "edited");

        assertThat(ac.restore(MirroredOutputs.omitting(classes, record), classes))
                .isTrue();
        assertThat(classes.resolve("com/x/J.class")).hasContent("javac");
        assertThat(classes.resolve("com/x/A.class"))
                .as("the merge's current class is neither pruned nor overwritten")
                .hasContent("kotlin-v2");
        assertThat(classes.resolve("com/x/B.class"))
                .as("a dropped declaration is not laid back down from the javac record")
                .doesNotExist();
    }

    @Test
    void a_recorded_resource_is_dropped_and_a_class_stays(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("target/classes"));
        Path ledger = tmp.resolve("target/incremental").resolve(MirroredOutputs.MAIN_LEDGER);
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
        Path classes = Files.createDirectories(tmp.resolve("target/classes"));
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
