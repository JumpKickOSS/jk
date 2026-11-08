// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.FileHashMemo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A resource that leaves its root leaves the classes tree too; compiled classes are never touched. */
class ResourceMirrorTest {

    @Test
    void a_deleted_resource_is_removed_and_compiled_classes_stay(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("src/main/resources");
        Files.createDirectories(res.resolve("conf"));
        Files.writeString(res.resolve("a.txt"), "a");
        Files.writeString(res.resolve("conf/b.txt"), "b");
        Path classes = tmp.resolve("target/classes/main");
        Files.createDirectories(classes.resolve("com/x"));
        Files.writeString(classes.resolve("com/x/X.class"), "class");
        Path ledger = tmp.resolve("target/incremental/" + ResourceMirror.LEDGER);

        assertThat(ResourceMirror.sync(List.of(res), classes, ledger)).containsExactly("a.txt", "conf/b.txt");
        assertThat(classes.resolve("conf/b.txt")).exists();
        assertThat(Files.readString(ledger)).isEqualTo("a.txt\nconf/b.txt\n");

        Files.delete(res.resolve("conf/b.txt"));
        Files.delete(res.resolve("conf"));
        assertThat(ResourceMirror.sync(List.of(res), classes, ledger)).containsExactly("a.txt");
        assertThat(classes.resolve("conf/b.txt")).doesNotExist();
        assertThat(classes.resolve("conf"))
                .as("a directory the mirror emptied goes too")
                .doesNotExist();
        assertThat(classes.resolve("a.txt")).exists();
        assertThat(classes.resolve("com/x/X.class")).exists();

        assertThat(ResourceMirror.sync(List.of(), classes, ledger)).isEmpty();
        assertThat(classes.resolve("a.txt"))
                .as("no resource root left: every mirrored file goes")
                .doesNotExist();
        assertThat(classes.resolve("com/x/X.class")).exists();
        assertThat(ledger).as("an empty mirror still records that it ran").isRegularFile();
        assertThat(Files.readString(ledger)).isEmpty();
    }

    @Test
    void an_unchanged_tree_is_no_change_and_an_edit_or_a_delete_is_one(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("src/main/resources");
        Files.createDirectories(res);
        Files.writeString(res.resolve("a.txt"), "a");
        Files.writeString(res.resolve("b.txt"), "b");
        Path classes = tmp.resolve("target/classes/main");
        Path ledger = tmp.resolve("target/incremental/" + ResourceMirror.LEDGER);

        assertThat(ResourceMirror.syncChanges(List.of(res), classes, ledger)).isEqualTo(2);
        assertThat(ResourceMirror.syncChanges(List.of(res), classes, ledger)).isZero();
        Files.writeString(res.resolve("a.txt"), "a, longer");
        assertThat(ResourceMirror.syncChanges(List.of(res), classes, ledger)).isEqualTo(1);
        Files.delete(res.resolve("b.txt"));
        assertThat(ResourceMirror.syncChanges(List.of(res), classes, ledger)).isEqualTo(1);
        assertThat(ResourceMirror.syncChanges(List.of(res), classes, ledger)).isZero();
    }

    @Test
    void a_file_present_in_two_roots_is_mirrored_once_and_kept_while_either_holds_it(@TempDir Path tmp)
            throws Exception {
        Path r1 = tmp.resolve("r1");
        Path r2 = tmp.resolve("r2");
        Files.createDirectories(r1);
        Files.createDirectories(r2);
        Files.writeString(r1.resolve("shared.txt"), "1");
        Files.writeString(r2.resolve("shared.txt"), "2");
        Path classes = tmp.resolve("classes");
        Path ledger = tmp.resolve("ledger.txt");

        assertThat(ResourceMirror.sync(List.of(r1, r2), classes, ledger)).containsExactly("shared.txt");
        Files.delete(r1.resolve("shared.txt"));
        assertThat(ResourceMirror.sync(List.of(r1, r2), classes, ledger)).containsExactly("shared.txt");
        assertThat(classes.resolve("shared.txt")).exists();
    }

    @Test
    void a_file_copied_before_the_ledger_existed_is_removed(@TempDir Path tmp) throws Exception {
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("com/example"));
        Files.writeString(classes.resolve("gone.properties"), "old");
        Files.writeString(classes.resolve("com/example/App.class"), "class");
        Path res = Files.createDirectories(tmp.resolve("resources"));
        Files.writeString(res.resolve("keep.properties"), "k");
        Path ledger = tmp.resolve("incremental/copied-test-resources.txt");

        assertThat(ResourceMirror.sync(List.of(res), classes, ledger, true)).containsExactly("keep.properties");
        assertThat(classes.resolve("gone.properties")).doesNotExist();
        assertThat(classes.resolve("keep.properties")).isRegularFile();
        assertThat(classes.resolve("com/example/App.class")).isRegularFile();
    }

    /**
     * Dot-file resources are resources: a template's {@code .jk-template.toml} is mirrored, edited
     * and removed like any other file, and an orphaned one is adopted and removed. Only a {@code
     * .jk-*} directory, a plugin's scratch, is left where it is.
     */
    @Test
    void a_dot_file_resource_is_mirrored_and_pruned_and_only_a_scratch_dir_is_left_alone(@TempDir Path tmp)
            throws Exception {
        Path res = tmp.resolve("src/main/resources");
        Path template = res.resolve("templates/java/app/default.g8/.jk-template.toml");
        Files.createDirectories(res.resolve("templates/java/app/default.g8"));
        Files.writeString(template, "title = \"one\"\n");
        Files.writeString(res.resolve(".hidden"), "h");
        Path classes = tmp.resolve("classes");
        Path copied = classes.resolve("templates/java/app/default.g8/.jk-template.toml");
        Files.createDirectories(classes.resolve(".jk-bootstrap"));
        Files.writeString(classes.resolve(".jk-bootstrap/repo.jar"), "scratch");
        Files.writeString(classes.resolve(".jk-orphan.toml"), "orphan");
        Path ledger = tmp.resolve("incremental/" + ResourceMirror.LEDGER);

        assertThat(ResourceMirror.sync(List.of(res), classes, ledger, true))
                .containsExactly(".hidden", "templates/java/app/default.g8/.jk-template.toml");
        assertThat(copied).hasContent("title = \"one\"");
        assertThat(classes.resolve(".jk-orphan.toml"))
                .as("a .jk-* file no root holds is an orphaned resource")
                .doesNotExist();
        assertThat(classes.resolve(".jk-bootstrap/repo.jar"))
                .as("a .jk-* directory is scratch")
                .isRegularFile();

        Files.writeString(template, "title = \"edited\"\n");
        assertThat(ResourceMirror.syncChanges(List.of(res), classes, ledger)).isEqualTo(1);
        assertThat(copied).hasContent("title = \"edited\"");

        Files.delete(template);
        ResourceMirror.sync(List.of(res), classes, ledger);
        assertThat(copied).doesNotExist();
    }

    @Test
    void a_file_the_compile_rewrote_is_not_adopted(@TempDir Path tmp) throws Exception {
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(classes.resolve("gone.properties"), "old");
        Files.writeString(classes.resolve("META-INF/services/com.example.Spi"), "generated");
        var before = ResourceMirror.nonClassIdentity(classes);
        Files.writeString(classes.resolve("META-INF/services/com.example.Spi"), "regenerated");
        Path res = Files.createDirectories(tmp.resolve("resources"));
        Files.writeString(res.resolve("keep.properties"), "k");
        Path ledger = tmp.resolve("incremental/copied-test-resources.txt");

        Set<String> protect = ResourceMirror.changedNonClass(classes, before);
        assertThat(ResourceMirror.sync(List.of(res), classes, ledger, true, protect))
                .containsExactly("keep.properties");
        assertThat(classes.resolve("gone.properties")).doesNotExist();
        assertThat(classes.resolve("META-INF/services/com.example.Spi")).isRegularFile();
        assertThat(classes.resolve("keep.properties")).isRegularFile();
    }

    /**
     * The main mirror runs before the compile and leaves a copy the last compile's processor
     * rewrote while its resource still has the sha the copy was seeded from; an edited resource is
     * copied again. A single file beside the roots — a module-root plugin manifest — rides the
     * ledger with them, and each mirrored path names the resource it came from.
     */
    @Test
    void a_held_copy_is_left_until_its_resource_changes(@TempDir Path tmp) throws Exception {
        Path classes = tmp.resolve("target/classes");
        Path res = Files.createDirectories(tmp.resolve("resources/META-INF"));
        Path resource = res.resolve("manifest");
        Files.writeString(resource, "static\n");
        Path plugin = Files.writeString(tmp.resolve("jk-plugin.toml"), "id = \"x\"\n");
        Path ledger = tmp.resolve("target/incremental/copied-resources.txt");
        Map<String, Path> files = Map.of("jk-plugin.toml", plugin);

        ResourceMirror.Main first =
                ResourceMirror.syncMain(List.of(tmp.resolve("resources")), files, classes, ledger, Map.of());
        assertThat(first.sources())
                .containsOnlyKeys("META-INF/manifest", "jk-plugin.toml")
                .containsEntry("META-INF/manifest", resource);
        assertThat(Files.readAllLines(ledger)).containsExactly("META-INF/manifest", "jk-plugin.toml");

        Files.writeString(classes.resolve("META-INF/manifest"), "static\nmerged\n");
        Map<String, String> held = Map.of("META-INF/manifest", FileHashMemo.contentHash(resource));
        ResourceMirror.Main second =
                ResourceMirror.syncMain(List.of(tmp.resolve("resources")), files, classes, ledger, held);
        assertThat(second.changes()).isZero();
        assertThat(classes.resolve("META-INF/manifest")).hasContent("static\nmerged");

        Files.writeString(resource, "edited\n");
        assertThat(ResourceMirror.syncMain(List.of(tmp.resolve("resources")), files, classes, ledger, held)
                        .changes())
                .isEqualTo(1);
        assertThat(classes.resolve("META-INF/manifest")).hasContent("edited");

        Files.delete(plugin);
        ResourceMirror.syncMain(List.of(tmp.resolve("resources")), Map.of(), classes, ledger, held);
        assertThat(classes.resolve("jk-plugin.toml")).doesNotExist();
    }
}
