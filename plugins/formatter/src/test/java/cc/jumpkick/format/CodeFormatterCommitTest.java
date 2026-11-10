// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A formatted file is written whole and only over the bytes it was formatted from. */
class CodeFormatterCommitTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void an_edit_saved_during_the_run_is_kept(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("A.java"), "class  A {}\n");
        CodeFormatter.FileResult decided = new CodeFormatter.FileResult(
                file.toFile(), "changed", null, utf8("class A {}\n"), "k", utf8("class  A {}\n"));
        Files.writeString(file, "class  A { int saved; }\n");

        CodeFormatter.FileResult r = CodeFormatter.commit(decided, null);

        assertThat(r.status()).isEqualTo("error");
        assertThat(file).hasContent("class  A { int saved; }");
    }

    @Test
    void an_unchanged_file_is_replaced_with_its_formatted_bytes_and_keeps_its_mode(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("A.java"), "class  A {}\n");
        boolean posix = Files.getFileStore(file).supportsFileAttributeView("posix");
        Assumptions.assumeTrue(posix);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        CodeFormatter.FileResult decided = new CodeFormatter.FileResult(
                file.toFile(), "changed", null, utf8("class A {}\n"), "k", utf8("class  A {}\n"));

        assertThat(CodeFormatter.commit(decided, null).status()).isEqualTo("changed");
        assertThat(file).hasContent("class A {}");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-r--r--");
        try (var left = Files.list(dir)) {
            assertThat(left).as("no temporary file is left beside it").containsExactly(file);
        }
    }
}
