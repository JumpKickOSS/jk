// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeJournal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskToolJournalTest {

    @TempDir
    Path dir;

    @Test
    void a_clean_that_ran_is_journaled_with_what_it_freed() {
        ChangeJournal.into(dir, () -> {
            DiskTool.noteDone("clean", Map.of("cleaned", true, "files", 12L, "bytes", 3L * 1024 * 1024));
            DiskTool.noteDone("m2-clean", Map.of("cleaned", true, "files", 2, "bytes", 0));
            DiskTool.noteDone("nuke", Map.of("nuked", true));
            return null;
        });

        List<Change> entries = new ChangeJournal(dir).page(null, 10).entries();
        assertThat(entries)
                .extracting(Change::summary)
                .containsExactly(
                        "nuked the cache tier",
                        "cleaned JumpKick's unused downloads from ~/.m2: 2 files, 0 MiB freed",
                        "cleaned the cache tier: 12 files, 3 MiB freed");
        assertThat(entries).extracting(Change::action).containsExactly(Change.DELETE, Change.CLEAN, Change.CLEAN);
    }

    @Test
    void a_preview_an_error_or_a_clean_of_nothing_is_not_a_change() {
        ChangeJournal.into(dir, () -> {
            DiskTool.noteDone("nuke", Map.of("preview", true));
            DiskTool.noteDone("clean", Map.of("error", "cache is busy"));
            DiskTool.noteDone("clean", Map.of("cleaned", true, "files", 0L, "bytes", 0L));
            return null;
        });
        assertThat(new ChangeJournal(dir).page(null, 10).entries()).isEmpty();
    }
}
