// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssemblyCommandTest {

    @Test
    void without_assembly_prints_fix_and_exits_config(@TempDir Path dir) throws IOException {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                [application]
                main = "demo.App"
                """);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream orig = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("assembly", "-C", dir.toString());
        } finally {
            System.setErr(orig);
        }
        assertThat(exit).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("assembly = true");
    }
}
