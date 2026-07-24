// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class WatchDebounceTest {

    @Test
    void bad_debounce_ms_is_usage_error(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        int code = Jk.execute("watch", "build", "--debounce-ms", "abc", "-C", dir.toString());
        assertThat(code).isNotZero();
        code = Jk.execute("watch", "build", "--debounce-ms", "999999", "-C", dir.toString());
        assertThat(code).isNotZero();
    }
}
