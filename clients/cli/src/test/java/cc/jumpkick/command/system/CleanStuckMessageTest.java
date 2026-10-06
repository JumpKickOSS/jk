// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.FileHolders;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CleanStuckMessageTest {

    @Test
    void a_stuck_file_names_its_holder_and_the_engine_by_role(@TempDir Path ws) {
        Path stuck = ws.resolve("app/target/classes/A.class");
        long self = ProcessHandle.current().pid();
        IOException failure = new FileSystemException(
                stuck.toString(),
                null,
                "The process cannot access the file because it is being used by another process");
        failure.addSuppressed(new IOException("other"));

        String byName = CleanCommand.stuckMessage(
                failure, ws, 10, "10 files, 1 KiB total", p -> List.of(new FileHolders.Holder(4242, "Code.exe")), 0);
        String byRole = CleanCommand.stuckMessage(
                failure, ws, 10, "10 files, 1 KiB total", p -> List.of(new FileHolders.Holder(self, "java")), self);

        assertThat(byName)
                .isEqualTo("Removed 10 files, 1 KiB total, but app/target/classes/A.class and 1 more could not be"
                        + " removed: held open by Code.exe (pid 4242)");
        assertThat(byRole).endsWith("held open by the jk engine (pid " + self + "; `jk engine stop` releases it)");
    }

    @Test
    void an_unknown_holder_says_where_to_look(@TempDir Path ws) {
        IOException failure = new IOException(ws.resolve("target/x.jar").toString());

        assertThat(CleanCommand.stuckMessage(failure, ws, 0, "", p -> List.of(), 0))
                .isEqualTo("Nothing removed target/x.jar could not be removed: another process has it open"
                        + " (a build, the engine, or an IDE)");
    }
}
