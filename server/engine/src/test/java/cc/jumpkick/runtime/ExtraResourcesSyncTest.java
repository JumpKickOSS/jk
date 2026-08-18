// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A shrunk/removed {@code [build] extra-resources} entry must leave the classes dir on
 * the next run — orphaned copies used to survive even {@code --force} and ship in every
 * later jar (JK-2174).
 */
class ExtraResourcesSyncTest {

    @Test
    void undeclared_destinations_are_deleted_and_empty_parents_pruned(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("mod"));
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path kept = classes.resolve("cc/jk/kept.toml");
        Path stale = classes.resolve("cc/jk/deep/stale.toml");
        Files.createDirectories(kept.getParent());
        Files.createDirectories(stale.getParent());
        Files.writeString(kept, "k");
        Files.writeString(stale, "s");
        Path manifest = module.resolve("target/.jk/extra-resources.txt");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "cc/jk/kept.toml\ncc/jk/deep/stale.toml\n");

        Path src = tmp.resolve("src.toml");
        Files.writeString(src, "k");
        PlannerResources.syncExtraResourceManifest(
                module, classes, List.of(new ExtraResources.Copy(src, "cc/jk/kept.toml")));

        assertThat(kept).exists();
        assertThat(stale).doesNotExist();
        assertThat(stale.getParent()).as("emptied parent dirs are pruned").doesNotExist();
        assertThat(kept.getParent()).exists();
        assertThat(Files.readString(manifest)).isEqualTo("cc/jk/kept.toml\n");
    }

    @Test
    void empty_declaration_cleans_everything_and_drops_the_manifest(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("mod"));
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path stale = classes.resolve("cc/jk/only.toml");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "x");
        Path manifest = module.resolve("target/.jk/extra-resources.txt");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "cc/jk/only.toml\n");

        PlannerResources.syncExtraResourceManifest(module, classes, List.of());

        assertThat(stale).doesNotExist();
        assertThat(manifest).doesNotExist();
    }

    @Test
    void manifest_entries_cannot_escape_the_classes_dir(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("mod"));
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path outside = tmp.resolve("victim.txt");
        Files.writeString(outside, "keep me");
        Path manifest = module.resolve("target/.jk/extra-resources.txt");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "../victim.txt\n");

        PlannerResources.syncExtraResourceManifest(module, classes, List.of());

        assertThat(outside).exists();
    }
}
