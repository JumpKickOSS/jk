// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The outputs-missing probe must see class files at ANY package depth: a depth-capped
 * walk missed everything under three segments, so all {@code cc.jumpkick.*} modules read as
 * "outputs missing" and the restore path re-ran the whole fully-cached workspace on every build.
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
    void empty_or_classless_trees_are_not_content(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes/main"));
        assertThat(ModuleOutputs.classesDirHasContent(classes)).isFalse();
        Files.createDirectories(classes.resolve("cc/jumpkick/only/dirs"));
        Files.writeString(classes.resolve("cc/jumpkick/only/dirs/notes.txt"), "not a class");
        assertThat(ModuleOutputs.classesDirHasContent(classes)).isFalse();
        assertThat(ModuleOutputs.classesDirHasContent(dir.resolve("absent"))).isFalse();
    }
}
