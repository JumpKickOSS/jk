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
        Files.writeString(script, """
                import java.nio.file.Files
                // write stamp
                Files.writeString(outDir.resolve("kts.txt"), "ok")
                """);
        Path project = dir.resolve("proj");
        Path out = dir.resolve("out");
        String wrapped = BuildLogicKtsHost.wrap(script, project, out);
        assertTrue(wrapped.contains("import java.nio.file.Path"));
        assertTrue(wrapped.contains("import java.nio.file.Files"));
        assertTrue(wrapped.contains("val projectDir: Path = Path.of("));
        assertTrue(wrapped.contains("val outDir: Path = Path.of("));
        // No classesDir binding: outDir is the only surface the action cache replays (JK-1614).
        assertFalse(wrapped.contains("classesDir"));
        assertTrue(wrapped.contains("Files.writeString(outDir.resolve(\"kts.txt\"), \"ok\")"));
        // Path import not duplicated
        assertFalse(wrapped.contains("import java.nio.file.Path\nimport java.nio.file.Path"));
    }

    /**
     * Kotlin's file order is @file: annotations, then imports, then declarations. The bindings are
     * declarations, so an import after a leading comment still has to be hoisted above them —
     * this repo's own convention opens every file with an SPDX comment (JK-1605).
     */
    @Test
    void a_leading_comment_does_not_strand_the_imports_below_the_bindings(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("before-compile.kts");
        Files.writeString(script, """
                // SPDX-License-Identifier: Apache-2.0
                import java.nio.file.Files
                // and one more between imports
                import java.util.Locale

                Files.writeString(outDir.resolve("kts.txt"), "ok".uppercase(Locale.ROOT))
                """);
        String wrapped = BuildLogicKtsHost.wrap(script, dir, dir.resolve("o"));

        assertTrue(wrapped.indexOf("import java.nio.file.Files") < wrapped.indexOf("val projectDir"));
        assertTrue(wrapped.indexOf("import java.util.Locale") < wrapped.indexOf("val projectDir"));
        assertTrue(wrapped.contains("\"ok\".uppercase(Locale.ROOT)"));
    }

    /**
     * A {@code /* ... *}{@code /} block comment is the same JK-1605 bug as a leading {@code //}
     * line comment — a common license-header style, and it can span multiple lines.
     */
    @Test
    void a_leading_block_comment_does_not_strand_the_imports_below_the_bindings(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("before-compile.kts");
        Files.writeString(script, """
                /*
                 * Copyright Example Corp.
                 * Licensed under Apache-2.0.
                 */
                import java.nio.file.Files

                Files.writeString(outDir.resolve("kts.txt"), "ok")
                """);
        String wrapped = BuildLogicKtsHost.wrap(script, dir, dir.resolve("o"));

        assertTrue(wrapped.indexOf("import java.nio.file.Files") < wrapped.indexOf("val projectDir"));
        assertTrue(wrapped.contains("Files.writeString(outDir.resolve(\"kts.txt\"), \"ok\")"));
    }

    /** A block comment that opens and closes on the same line, with an import right after. */
    @Test
    void a_same_line_block_comment_does_not_strand_the_following_import(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("before-compile.kts");
        Files.writeString(script, """
                /* SPDX-License-Identifier: Apache-2.0 */
                import java.nio.file.Files

                Files.writeString(outDir.resolve("kts.txt"), "ok")
                """);
        String wrapped = BuildLogicKtsHost.wrap(script, dir, dir.resolve("o"));

        assertTrue(wrapped.indexOf("import java.nio.file.Files") < wrapped.indexOf("val projectDir"));
    }

    @Test
    void file_annotations_precede_every_import_including_the_injected_one(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("before-compile.kts");
        Files.writeString(script, """
                // a comment first, to be awkward
                @file:JvmName("Gen")
                import java.nio.file.Files

                Files.writeString(outDir.resolve("kts.txt"), "ok")
                """);
        String wrapped = BuildLogicKtsHost.wrap(script, dir, dir.resolve("o"));

        assertTrue(wrapped.indexOf("@file:JvmName(\"Gen\")") < wrapped.indexOf("import java.nio.file.Path"));
        assertTrue(wrapped.indexOf("@file:JvmName(\"Gen\")") < wrapped.indexOf("import java.nio.file.Files"));
    }

    @Test
    void wrap_rejects_package_declaration(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("before-compile.kts");
        Files.writeString(script, "package demo\nval x = 1\n");
        assertThrows(IllegalStateException.class, () -> BuildLogicKtsHost.wrap(script, dir, dir.resolve("o")));
    }

    @Test
    void ktString_escapes() {
        assertTrue(BuildLogicKtsHost.ktString("a\"b$c\\d").contains("\\\""));
        assertTrue(BuildLogicKtsHost.ktString("a\"b$c\\d").contains("\\$"));
        assertTrue(BuildLogicKtsHost.ktString("a\"b$c\\d").contains("\\\\"));
    }
}
