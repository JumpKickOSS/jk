// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildLogicKtsHostTest {

    @Test
    void wrap_injects_bindings_and_hoists_imports(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("after-resources.kts");
        Files.writeString(
                script,
                """
                import java.nio.file.Files
                // write stamp
                Files.writeString(outDir.resolve("kts.txt"), "ok")
                """);
        Path project = dir.resolve("proj");
        Path out = dir.resolve("out");
        Path classes = dir.resolve("classes");
        String wrapped = BuildLogicKtsHost.wrap(script, project, out, classes);
        assertTrue(wrapped.contains("import java.nio.file.Path"));
        assertTrue(wrapped.contains("import java.nio.file.Files"));
        assertTrue(wrapped.contains("val projectDir: Path = Path.of("));
        assertTrue(wrapped.contains("val outDir: Path = Path.of("));
        assertTrue(wrapped.contains("Files.writeString(outDir.resolve(\"kts.txt\"), \"ok\")"));
        // Path import not duplicated
        assertFalse(wrapped.contains("import java.nio.file.Path\nimport java.nio.file.Path"));
    }

    @Test
    void wrap_rejects_package_declaration(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("before-compile.kts");
        Files.writeString(script, "package demo\nval x = 1\n");
        assertThrows(IllegalStateException.class, () -> BuildLogicKtsHost.wrap(
                script, dir, dir.resolve("o"), dir.resolve("c")));
    }

    @Test
    void ktString_escapes() {
        assertTrue(BuildLogicKtsHost.ktString("a\"b$c\\d").contains("\\\""));
        assertTrue(BuildLogicKtsHost.ktString("a\"b$c\\d").contains("\\$"));
        assertTrue(BuildLogicKtsHost.ktString("a\"b$c\\d").contains("\\\\"));
    }
}
