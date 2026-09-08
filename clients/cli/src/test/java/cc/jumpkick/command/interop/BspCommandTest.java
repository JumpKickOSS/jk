// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Exit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk bsp install} writes the BSP connection file. Everything asserted here is written by
 * {@link BspCommand}: the test creates only {@code jk.toml}, never {@code .bsp/}. Serving is
 * covered by {@code BspServerTest}, which drives real Content-Length frames through
 * {@code BspServer}.
 */
@Tag("integration")
class BspCommandTest {

    @Test
    void install_writes_the_bsp_connection_file(@TempDir Path dir) throws IOException {
        project(dir);

        assertThat(Jk.execute(new String[] {"bsp", "install", "-C", dir.toString()}))
                .isEqualTo(Exit.SUCCESS);

        Path connection = dir.resolve(".bsp/jk.json");
        assertThat(connection).isRegularFile();
        String json = Files.readString(connection);
        assertThat(json)
                .contains("\"name\": \"jk\"")
                .contains("\"bspVersion\": \"2.1.0\"")
                .contains("\"languages\": [\"java\", \"kotlin\", \"groovy\"]")
                .contains("\"version\": " + quoted(JkVersion.VERSION))
                .contains("\"bsp\", \"serve\"");
    }

    @Test
    void install_is_idempotent(@TempDir Path dir) throws IOException {
        project(dir);
        Path connection = dir.resolve(".bsp/jk.json");

        assertThat(Jk.execute(new String[] {"bsp", "install", "-C", dir.toString()}))
                .isEqualTo(Exit.SUCCESS);
        String first = Files.readString(connection);

        assertThat(Jk.execute(new String[] {"bsp", "install", "-C", dir.toString()}))
                .isEqualTo(Exit.SUCCESS);
        assertThat(Files.readString(connection)).isEqualTo(first);
    }

    @Test
    void install_refuses_a_directory_with_no_manifest_and_writes_nothing(@TempDir Path dir) {
        assertThat(Jk.execute(new String[] {"bsp", "install", "-C", dir.toString()}))
                .isEqualTo(Exit.CONFIG);
        assertThat(dir.resolve(".bsp")).doesNotExist();
    }

    @Test
    void an_unknown_action_is_a_usage_error(@TempDir Path dir) throws IOException {
        project(dir);

        assertThat(Jk.execute(new String[] {"bsp", "wobble", "-C", dir.toString()}))
                .isEqualTo(Exit.USAGE);
        assertThat(dir.resolve(".bsp")).doesNotExist();
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    private static void project(Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                java = 25
                """);
    }
}
