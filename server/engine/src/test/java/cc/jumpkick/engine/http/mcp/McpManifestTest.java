// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpManifestTest {

    private static final String TABLE_TERMINATED = """
            group   = "com.example"
            name    = "widget"
            version = "0.1.0"
            jdk     = 25

            [dependencies]

            [test-dependencies]
            """;

    @Test
    void set_java_on_a_table_terminated_manifest_lands_at_top_level(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), TABLE_TERMINATED, StandardCharsets.UTF_8);
        Map<String, Object> out = McpManifest.setJava(dir.toString(), 21, true);
        assertThat(out.get("applied")).isEqualTo(true);
        assertThat(out).doesNotContainKey("error");
        String after = Files.readString(dir.resolve("jk.toml"), StandardCharsets.UTF_8);
        assertThat(after.indexOf("java = 21")).isLessThan(after.indexOf("[dependencies]"));
        assertThat(JkBuildParser.parse(after).project().javaRelease()).isEqualTo(21);
    }

    @Test
    void set_java_replaces_an_existing_root_line(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"), "name = \"a\"\ngroup = \"g\"\nversion = \"1\"\njava = 17\n[dependencies]\n");
        McpManifest.setJava(dir.toString(), 25, true);
        String after = Files.readString(dir.resolve("jk.toml"), StandardCharsets.UTF_8);
        assertThat(after).contains("java = 25");
        assertThat(after).doesNotContain("java = 17");
        assertThat(JkBuildParser.parse(after).project().javaRelease()).isEqualTo(25);
    }
}
