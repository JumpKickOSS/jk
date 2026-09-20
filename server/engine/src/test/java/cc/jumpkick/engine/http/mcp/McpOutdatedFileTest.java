// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.workspace.JkOutdatedMarkdown;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk_outdated} writes the same results file the CLI does and names it in the payload. */
class McpOutdatedFileTest {

    @TempDir
    Path isolated;

    @BeforeEach
    void isolate() {
        System.setProperty("jk.env.JK_STORE_DIR", isolated.resolve("store").toString());
        System.setProperty("jk.env.JK_CACHE_DIR", isolated.resolve("cache").toString());
    }

    @AfterEach
    void release() {
        System.clearProperty("jk.env.JK_STORE_DIR");
        System.clearProperty("jk.env.JK_CACHE_DIR");
    }

    @Test
    void a_read_writes_the_results_file_and_returns_its_path(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.acme"
                name = "app"
                version = "0.1.0"
                """);

        Map<String, Object> data = McpReads.outdated(dir.toString(), false);

        assertThat(data).doesNotContainKey("error");
        Path file = JkOutdatedMarkdown.latestPath(dir);
        assertThat(data.get("file")).isEqualTo(file.toString());
        assertThat(file).exists();
        assertThat(Files.readString(file))
                .contains("# jk outdated dependencies")
                .contains("0 checked");
    }

    @Test
    void an_errored_read_has_no_file(@TempDir Path dir) {
        Map<String, Object> data = McpReads.outdated(dir.resolve("nowhere").toString(), false);
        assertThat(data).containsKey("error").doesNotContainKey("file");
    }
}
