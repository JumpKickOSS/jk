// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileHoldersTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void a_file_this_process_holds_names_this_process(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("held.class"), "bytes");
        long self = ProcessHandle.current().pid();

        try (FileChannel open = FileChannel.open(file, StandardOpenOption.READ)) {
            assertThat(FileHolders.of(file)).extracting(FileHolders.Holder::pid).contains(self);
        }
        assertThat(FileHolders.of(file)).extracting(FileHolders.Holder::pid).doesNotContain(self);
    }

    @Test
    void a_file_nobody_holds_has_no_holders(@TempDir Path tmp) throws Exception {
        assertThat(FileHolders.of(Files.writeString(tmp.resolve("free"), "x"))).isEmpty();
        assertThat(FileHolders.of(tmp.resolve("absent"))).isEmpty();
    }
}
