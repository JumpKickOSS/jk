// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.task.ClasspathFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A live workspace build remembers a sibling's classes tree token only once nothing this build
 * runs writes that tree any more: before the producer publishes its classes every reading walks
 * the tree, so a file it adds is in the next token; after, the tree reads once for the request.
 */
class SealedEntryMemoTest {

    @Test
    void a_producers_classes_tree_is_walked_each_time_until_it_publishes_and_once_after(@TempDir Path ws)
            throws Exception {
        Path a = Files.createDirectories(ws.resolve("a"));
        Path b = Files.createDirectories(ws.resolve("b"));
        Path classes = Files.createDirectories(BuildLayout.moduleTargetDir(a).resolve("classes"));
        Files.writeString(classes.resolve("A.class"), "one");
        SiblingArtifacts siblings = new SiblingArtifacts(Map.of(a, Set.of(), b, Set.of(a)), List.of(a, b));
        ClasspathFingerprint.EntryMemo memo = ClasspathFingerprint.EntryMemo.onceSealed(siblings::classesSealed);

        List<String> before = ClasspathFingerprint.withEntryMemo(memo, () -> {
            List<String> tokens = new ArrayList<>();
            tokens.add(ClasspathFingerprint.entry(classes));
            Files.writeString(classes.resolve("B.class"), "two");
            tokens.add(ClasspathFingerprint.entry(classes));
            tokens.add(ClasspathFingerprint.entry(classes));
            return tokens;
        });
        assertThat(memo.walks()).as("walks before a publishes its classes").isEqualTo(3);
        assertThat(before.get(1)).as("a file a writes is in the next token").isNotEqualTo(before.get(0));
        assertThat(before.get(2)).isEqualTo(before.get(1));

        siblings.compiled(a);
        List<String> after = ClasspathFingerprint.withEntryMemo(
                memo,
                () -> List.of(
                        ClasspathFingerprint.entry(classes),
                        ClasspathFingerprint.entry(classes),
                        ClasspathFingerprint.entry(classes)));
        assertThat(memo.walks() - 3).as("walks after a publishes its classes").isEqualTo(1);
        assertThat(after).containsOnly(before.get(2));
        assertThat(memo.dirReadings()).as("every reading, walked or remembered").isEqualTo(6);
    }

    @Test
    void a_tree_the_build_does_not_schedule_is_sealed_from_the_start_and_a_jar_never_is(@TempDir Path ws)
            throws Exception {
        Path a = Files.createDirectories(ws.resolve("a"));
        Path b = Files.createDirectories(ws.resolve("b"));
        Path classes = Files.createDirectories(BuildLayout.moduleTargetDir(a).resolve("classes"));
        Files.writeString(classes.resolve("A.class"), "one");
        Path jar = Files.writeString(BuildLayout.moduleTargetDir(a).resolve("a.jar"), "jar");
        Path testClasses =
                Files.createDirectories(BuildLayout.moduleTargetDir(a).resolve("test-classes"));
        SiblingArtifacts siblings = new SiblingArtifacts(Map.of(a, Set.of(), b, Set.of(a)), List.of(b));

        assertThat(siblings.classesSealed(classes))
                .as("a's tree: a is not scheduled")
                .isTrue();
        assertThat(siblings.classesSealed(jar))
                .as("a jar is packaged after the classes publish")
                .isFalse();
        assertThat(siblings.classesSealed(testClasses))
                .as("test classes are no main tree")
                .isFalse();
        assertThat(siblings.classesSealed(ws.resolve("elsewhere/target/classes")))
                .as("a tree of no module this build knows")
                .isFalse();
    }
}
