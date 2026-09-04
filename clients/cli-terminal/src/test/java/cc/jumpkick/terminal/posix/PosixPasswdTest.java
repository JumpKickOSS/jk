// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.posix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class PosixPasswdTest {

    @Test
    void fromEtcPasswd_reads_pw_shell_for_the_named_user(@TempDir Path dir) throws Exception {
        Path passwd = dir.resolve("passwd");
        Files.writeString(passwd, """
                root:x:0:0:root:/root:/bin/bash
                # comment
                daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin
                alice:x:1000:1000:Alice:/home/alice:/usr/bin/zsh
                """);
        assertThat(PosixPasswd.fromEtcPasswd(passwd, "alice")).contains("/usr/bin/zsh");
        assertThat(PosixPasswd.fromEtcPasswd(passwd, "root")).contains("/bin/bash");
        assertThat(PosixPasswd.fromEtcPasswd(passwd, "missing")).isEmpty();
        assertThat(PosixPasswd.fromEtcPasswd(passwd, "")).isEmpty();
    }

    @Test
    void fromEtcPasswd_empty_shell_field_is_absent(@TempDir Path dir) throws Exception {
        Path passwd = dir.resolve("passwd");
        Files.writeString(passwd, "nobody:x:65534:65534:nobody:/nonexistent:\n");
        assertThat(PosixPasswd.fromEtcPasswd(passwd, "nobody")).isEmpty();
    }

    @Test
    void fromEtcPasswd_missing_file_is_empty(@TempDir Path dir) {
        assertThat(PosixPasswd.fromEtcPasswd(dir.resolve("nope"), "alice")).isEmpty();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void getpwuid_returns_an_absolute_shell_path() {
        assertThat(PosixPasswd.fromGetpwuid()).isPresent().get().asString().startsWith("/");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void getpwuid_matches_etc_passwd_when_the_user_is_local() {
        var nativeShell = PosixPasswd.fromGetpwuid();
        var fileShell = PosixPasswd.fromEtcPasswd(Path.of("/etc/passwd"), System.getProperty("user.name", ""));
        if (nativeShell.isPresent() && fileShell.isPresent()) {
            assertThat(nativeShell).isEqualTo(fileShell);
        }
    }
}
