// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
