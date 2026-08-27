// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildLogicScriptsTest {

    @Test
    void matchAnchor_stems_and_suffixes() {
        assertEquals(
                BuildLogicAnchor.BEFORE_COMPILE,
                BuildLogicScripts.matchAnchor("before-compile").orElseThrow());
        assertEquals(
                BuildLogicAnchor.BEFORE_COMPILE,
                BuildLogicScripts.matchAnchor("before_compile").orElseThrow());
        assertEquals(
                BuildLogicAnchor.BEFORE_COMPILE,
                BuildLogicScripts.matchAnchor("before-compile-collections").orElseThrow());
        assertEquals(
                BuildLogicAnchor.AFTER_RESOURCES,
                BuildLogicScripts.matchAnchor("after-resources").orElseThrow());
        assertTrue(BuildLogicScripts.matchAnchor("compile.groovy").isEmpty());
        assertTrue(BuildLogicScripts.matchAnchor("random").isEmpty());
    }

    @Test
    void discover_top_level_only(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("before-compile.groovy"), "// ok\n");
        Files.writeString(dir.resolve("after-compile.groovy"), "// ok\n");
        Files.writeString(dir.resolve("after-resources.kts"), "// kts\n");
        Files.writeString(dir.resolve("ignored.groovy"), "// no stem\n");
        Path nested = dir.resolve("nested");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("before-package.groovy"), "// not top-level\n");

        List<BuildLogicScripts.ScriptTask> tasks = BuildLogicScripts.discover(dir);
        assertEquals(3, tasks.size());
        assertEquals("after-compile", tasks.get(0).name());
        assertEquals(BuildLogicScripts.ScriptKind.GROOVY, tasks.get(0).kind());
        assertEquals("after-resources", tasks.get(1).name());
        assertEquals(BuildLogicScripts.ScriptKind.KTS, tasks.get(1).kind());
        assertEquals("before-compile", tasks.get(2).name());
    }

    @Test
    void kts_wins_the_same_stem(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("before-compile.groovy"), "// groovy\n");
        Files.writeString(dir.resolve("before-compile.kts"), "// kts\n");

        List<BuildLogicScripts.ScriptTask> tasks = BuildLogicScripts.discover(dir);
        assertEquals(1, tasks.size());
        assertEquals("before-compile", tasks.get(0).name());
        assertEquals(BuildLogicScripts.ScriptKind.KTS, tasks.get(0).kind());
        assertEquals("before-compile.kts", tasks.get(0).file().getFileName().toString());
    }
}
