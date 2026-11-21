// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** A rename's write and its undo: what a failed write leaves, and paths the manifest must carry whole. */
class RenameStoreUndoTest {

    /**
     * A file that appears at a move's destination after the plan was made fails the write: the
     * edits already written are put back, the file that appeared is left as it is, and the failure
     * the caller sees is the write's own.
     */
    @Test
    void a_write_that_fails_on_a_move_is_undone_and_reports_its_own_failure(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Path out = tmp.resolve("out");
        Files.writeString(root.resolve("Edited.java"), "before\n");
        Files.createDirectories(root.resolve("res/old"));
        Files.writeString(root.resolve("res/old/a.txt"), "resource\n");
        Files.createDirectories(root.resolve("res/new"));
        Files.writeString(root.resolve("res/new/a.txt"), "appeared\n");

        RenamePlan plan = new RenamePlan("old", "new");
        plan.before.put("Edited.java", "before\n");
        plan.after.put("Edited.java", "after\n");
        plan.rawMoves.put("res/old/a.txt", "res/new/a.txt");

        assertThatThrownBy(() -> RenameStore.write(root, out, plan))
                .isInstanceOf(FileAlreadyExistsException.class)
                .hasMessageContaining("new");
        assertThat(root.resolve("Edited.java")).hasContent("before");
        assertThat(root.resolve("res/old/a.txt")).hasContent("resource");
        assertThat(root.resolve("res/new/a.txt")).hasContent("appeared");
    }

    /** Windows refuses a tab in a file name; there the backslash case below proves the escaping. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_path_holding_a_tab_is_restored(@TempDir Path tmp) throws Exception {
        assertRestored(tmp, "we\tird/A.java");
    }

    /**
     * A backslash before a {@code t} is a file name character on POSIX and a separator on Windows;
     * either way the manifest must give it back as written, not as a tab.
     */
    @Test
    void a_path_holding_a_backslash_is_restored(@TempDir Path tmp) throws Exception {
        assertRestored(tmp, "we\\tird/A.java");
    }

    private static void assertRestored(Path tmp, String rel) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Path out = tmp.resolve("out");
        Path odd = root.resolve(rel);
        Files.createDirectories(odd.getParent());
        Files.writeString(odd, "before\n");

        RenamePlan plan = new RenamePlan("A", "B");
        plan.before.put(rel, "before\n");
        plan.after.put(rel, "after\n");
        RenameStore.write(root, out, plan);
        assertThat(odd).hasContent("after");

        assertThat(RenameStore.undo(root, out)).containsExactly(rel);
        assertThat(odd).hasContent("before");
    }
}
