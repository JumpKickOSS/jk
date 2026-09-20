// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmptyArchiveTest {

    @Test
    void only_the_bare_end_of_central_directory_record_is_an_empty_archive(@TempDir Path tmp) throws Exception {
        byte[] eocd = {0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThat(EmptyArchive.is(Files.write(tmp.resolve("hollow.jar"), eocd)))
                .isTrue();
        assertThat(EmptyArchive.is(Files.writeString(tmp.resolve("text.jar"), "twenty-two bytes here!")))
                .as("size alone does not make an archive")
                .isFalse();
        assertThat(EmptyArchive.is(Files.writeString(tmp.resolve("tiny.jar"), ".jar")))
                .isFalse();
        assertThat(EmptyArchive.is(tmp.resolve("absent.jar"))).isFalse();
    }
}
