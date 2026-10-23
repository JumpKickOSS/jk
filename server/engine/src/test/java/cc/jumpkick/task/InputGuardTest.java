// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step's inputs, as they were before its key, against what they are after it ran: every way a
 * tree a tool lists for itself can change under it is a move.
 */
class InputGuardTest {

    private interface Change {
        void apply(Path root) throws Exception;
    }

    private static List<Path> movedBy(Path dir, Change change) throws Exception {
        Path root = Files.createDirectories(dir.resolve("src"));
        Path pkg = Files.createDirectories(root.resolve("com/acme"));
        Files.writeString(pkg.resolve("Greet.java"), "class Greet {}");
        Files.writeString(root.resolve("Main.java"), "class Main {}");
        Path jar = Files.writeString(dir.resolve("dep.jar"), "jar");
        return SessionContext.where(Session.defaults().withCacheDir(dir.resolve("cache")), () -> {
            InputGuard guard = InputGuard.of(List.of(root, jar));
            assertThat(guard.moved()).as("nothing moved yet").isEmpty();
            Thread.sleep(20);
            change.apply(root);
            return guard.moved();
        });
    }

    @Test
    void an_edit_is_a_move(@TempDir Path dir) throws Exception {
        assertThat(movedBy(dir, r -> Files.writeString(r.resolve("com/acme/Greet.java"), "class Greet { }")))
                .containsExactly(dir.resolve("src").toAbsolutePath().normalize());
    }

    @Test
    void an_added_file_is_a_move(@TempDir Path dir) throws Exception {
        assertThat(movedBy(dir, r -> Files.writeString(r.resolve("com/acme/Extra.java"), "class Extra {}")))
                .isNotEmpty();
    }

    @Test
    void a_deleted_file_is_a_move(@TempDir Path dir) throws Exception {
        assertThat(movedBy(dir, r -> Files.delete(r.resolve("Main.java")))).isNotEmpty();
    }

    @Test
    void a_renamed_file_is_a_move_even_when_only_its_case_changes(@TempDir Path dir) throws Exception {
        assertThat(movedBy(dir, r -> Files.move(r.resolve("Main.java"), r.resolve("Main2.java"))))
                .isNotEmpty();
        Path other = Files.createDirectories(dir.resolve("case"));
        assertThat(movedBy(other, r -> {
                    Path tmp = r.resolve("tmp.java");
                    Files.move(r.resolve("Main.java"), tmp);
                    Files.move(tmp, r.resolve("main.java"));
                }))
                .isNotEmpty();
    }

    @Test
    void a_renamed_package_directory_is_a_move(@TempDir Path dir) throws Exception {
        assertThat(movedBy(dir, r -> Files.move(r.resolve("com/acme"), r.resolve("com/other"))))
                .isNotEmpty();
    }

    @Test
    void a_file_input_that_changes_is_a_move(@TempDir Path dir) throws Exception {
        assertThat(movedBy(dir, r -> Files.writeString(dir.resolve("dep.jar"), "JAR")))
                .containsExactly(dir.resolve("dep.jar").toAbsolutePath().normalize());
    }
}
