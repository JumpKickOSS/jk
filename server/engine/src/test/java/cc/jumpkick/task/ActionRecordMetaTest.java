// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.PathUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a record says about itself: its format, when, by which jk and on which platform it was made,
 * and each blob's size. A stored record reads all of it back, its total size is known with every
 * blob gone, and the form that may leave the machine keeps it while still dropping machine paths
 * and what the action said.
 */
class ActionRecordMetaTest {

    @Test
    void a_stored_record_reads_back_its_format_time_producer_platform_and_sizes(@TempDir Path tmp) throws Exception {
        Path out = Files.createDirectories(tmp.resolve("classes/p"));
        Files.writeString(out.resolve("A.class"), "class A");
        Files.writeString(out.resolve("B.class"), "class B, longer");
        Files.writeString(out.resolve("C.class"), "class A"); // same bytes as A: one blob
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));
        Instant before = Instant.ofEpochSecond(Instant.now().getEpochSecond());

        cache.store("compile-main@x", "k", Map.of("src/A.java", "aaa"), tmp.resolve("classes"));
        PathUtil.deleteRecursivelyOrThrow(tmp.resolve("cas"));
        String text = Files.readString(tmp.resolve("actions/keys/k"));
        ActionCache.ActionRecord record = new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"))
                .lookup("k")
                .orElseThrow();

        ActionRecordText.Meta meta = record.meta();
        assertThat(meta.format()).isEqualTo(ActionRecordText.FORMAT);
        assertThat(meta.writes()).isEqualTo(ActionRecordText.Writes.OUTPUTS);
        assertThat(meta.created()).isNotNull().isBetween(before, Instant.now());
        assertThat(meta.producer()).isEqualTo(ActionCache.producer()).startsWith("jk/");
        assertThat(meta.host()).isEqualTo(CachePlatform.current()).matches("[a-z]+-[a-z0-9_]+");
        assertThat(meta.sizes())
                .containsEntry("p/A.class", 7L)
                .containsEntry("p/B.class", 15L)
                .containsEntry("p/C.class", 7L);
        assertThat(record.totalBytes())
                .as("A and C are one blob; no blob is left to stat")
                .isEqualTo(22L);
        assertThat(record.outputs().get("p/A.class")).hasSize(64);
        assertThat(text).contains("\nFORMAT 2\n").contains("\nHOST " + CachePlatform.current() + "\n");
        assertThat(meta.target()).as("bytecode names no target").isNull();
        assertThat(text).doesNotContain("TARGET");
        assertThat(text).containsPattern("OUTPUT [0-9a-f]{64}/15 p/B\\.class");
    }

    @Test
    void the_shared_form_keeps_what_the_record_says_about_itself(@TempDir Path tmp) {
        String key = "k".repeat(64);
        ActionRecordText.Meta meta = new ActionRecordText.Meta(
                2,
                "java:compile",
                ActionRecordText.Writes.OUTPUTS,
                Instant.parse("2026-11-15T17:03:11Z"),
                "jk/0.51.2#8",
                "linux-x86_64",
                "toolchain:jdk:25.0.4+7",
                "linux-aarch64",
                4120,
                Map.of("A.class", 812L));
        String local = ActionRecordText.render(
                "compile-main@x",
                key,
                Map.of("src/A.java", "aaa", tmp.resolve("lib.jar").toString(), "abi:1"),
                Map.of("A.class", "a".repeat(64), "b/B.class", "b".repeat(64)),
                Set.of(),
                List.of("warning: something"),
                meta);

        String shared = ActionRecordText.renderShared(ActionRecordText.parse(local, key));

        ActionRecordText.Parsed back = ActionRecordText.parse(shared, key);
        assertThat(back.meta()).isEqualTo(meta);
        assertThat(back.outputs()).containsEntry("A.class", "a".repeat(64)).containsEntry("b/B.class", "b".repeat(64));
        assertThat(back.inputs()).containsOnlyKeys("src/A.java");
        assertThat(shared).doesNotContain("SAID ").doesNotContain(tmp.toString());
    }

    @Test
    void a_record_in_a_newer_format_is_refused(@TempDir Path tmp) {
        String key = "k".repeat(64);
        String newer = "TASK t\nKEY " + key + "\nFORMAT 3\nEND 0\n";

        assertThatThrownBy(() -> ActionRecordText.parse(newer, key)).isInstanceOf(ActionRecordText.Refused.class);
    }

    @Test
    void a_record_without_the_header_reads_it_as_absent() {
        String key = "k".repeat(64);
        String bare = "TASK t\nKEY " + key + "\nOUTPUT " + "b".repeat(64) + " A.class\nEND 1\n";

        ActionRecordText.Parsed parsed = ActionRecordText.parse(bare, key);

        assertThat(parsed.meta()).isEqualTo(ActionRecordText.Meta.NONE);
        assertThat(parsed.outputs()).containsEntry("A.class", "b".repeat(64));
    }
}
