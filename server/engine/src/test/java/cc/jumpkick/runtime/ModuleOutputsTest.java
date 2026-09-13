// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.FreshnessStamp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The outputs-missing probes. Presence must see class files at ANY package depth: a depth-capped
 * walk missed everything under three segments, so all {@code cc.jumpkick.*} modules read as
 * "outputs missing" and every fully-cached workspace was re-run on every build. Wholeness is
 * judged against the compile record: a tree that lost an output the record owns is missing an
 * output, whatever else it holds.
 */
class ModuleOutputsTest {

    @Test
    void deep_packages_count_as_content(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("classes/main");
        Path deep = Files.createDirectories(classes.resolve("cc/jumpkick/jsonl/inner"));
        Files.writeString(deep.resolve("Deep.class"), "x");
        assertThat(ModuleOutputs.classesDirHasContent(classes))
                .as("cc/jumpkick/jsonl/inner/Deep.class is content, whatever its depth")
                .isTrue();
    }

    @Test
    void a_tree_is_whole_only_while_every_output_its_record_owns_is_present(@TempDir Path dir) throws Exception {
        Cas cas = new Cas(dir.resolve("cas"));
        ActionCache ac = new ActionCache(cas, dir.resolve("actions"));
        String sha = cas.hashFromPath(cas.put("bytes".getBytes(StandardCharsets.UTF_8)))
                .orElseThrow();
        ac.storeWithOutputs("compile-main@x", "key-a", Map.of(), Map.of("p/A.class", sha, "p/q/B.class", sha));
        Path classes = Files.createDirectories(dir.resolve("classes/main"));
        Files.createDirectories(classes.resolve("p/q"));
        Files.writeString(classes.resolve("p/A.class"), "bytes");
        Files.writeString(classes.resolve("p/q/B.class"), "bytes");

        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, "key-a", classes)).isTrue();
        Files.writeString(classes.resolve("p/Extra.class"), "unowned");
        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, "key-a", classes))
                .as("extra files are the restore's prune, not this probe's concern")
                .isTrue();

        Files.delete(classes.resolve("p/q/B.class"));
        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, "key-a", classes))
                .as("a strict subset of the record's outputs")
                .isFalse();
        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, "no-such-key", classes))
                .as("no record: nothing to hold the tree against")
                .isTrue();
        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, null, classes)).isTrue();

        // Through the stamp: the tree's own .jstamp names the key it was compiled under.
        FreshnessStamp.write(
                classes,
                BuildStamps.JAVA,
                "compile-main",
                "key-a",
                List.of(),
                FreshnessStamp.ClasspathTokens.of(List.of()),
                25,
                "");
        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, classes)).isFalse();
        Files.writeString(classes.resolve("p/q/B.class"), "bytes");
        assertThat(ModuleOutputs.compileOutputsOnDisk(ac, classes)).isTrue();
    }

    @Test
    void empty_or_classless_trees_are_not_content(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes/main"));
        assertThat(ModuleOutputs.classesDirHasContent(classes)).isFalse();
        Files.createDirectories(classes.resolve("cc/jumpkick/only/dirs"));
        Files.writeString(classes.resolve("cc/jumpkick/only/dirs/notes.txt"), "not a class");
        assertThat(ModuleOutputs.classesDirHasContent(classes)).isFalse();
        assertThat(ModuleOutputs.classesDirHasContent(dir.resolve("absent"))).isFalse();
    }
}
