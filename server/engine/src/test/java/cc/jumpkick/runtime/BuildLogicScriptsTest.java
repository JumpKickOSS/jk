// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.task.RunNotices;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        assertEquals(
                BuildLogicAnchor.GATE, BuildLogicScripts.matchAnchor("gate").orElseThrow());
        assertEquals(
                BuildLogicAnchor.GATE,
                BuildLogicScripts.matchAnchor("gate-house").orElseThrow());
        assertTrue(BuildLogicScripts.matchAnchor("compile.groovy").isEmpty());
        assertTrue(BuildLogicScripts.matchAnchor("random").isEmpty());
    }

    @Test
    void discover_runs_top_level_only_and_reports_what_it_will_not_run(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("before-compile.groovy"), "// ok\n");
        Files.writeString(dir.resolve("after-compile.groovy"), "// ok\n");
        Files.writeString(dir.resolve("after-resources.kts"), "// kts\n");
        Files.writeString(dir.resolve("befor-compile.groovy"), "// typo\n");
        Files.writeString(dir.resolve("helpers.txt"), "not a script\n");
        Path nested = dir.resolve("scripts");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("before-package.groovy"), "// not top-level\n");

        List<String> warned = new ArrayList<>();
        RunNotices.clear();
        RunNotices.openSink(SessionContext.current().io(), (code, message) -> warned.add(message));
        try {
            List<BuildLogicScripts.ScriptTask> tasks = BuildLogicScripts.discover(dir);
            assertEquals(3, tasks.size());
            assertEquals("after-compile", tasks.get(0).name());
            assertEquals(BuildLogicScripts.ScriptKind.GROOVY, tasks.get(0).kind());
            assertEquals("after-resources", tasks.get(1).name());
            assertEquals(BuildLogicScripts.ScriptKind.KTS, tasks.get(1).kind());
            assertEquals("before-compile", tasks.get(2).name());

            // A script that does not run and does not complain is indistinguishable from one
            // that passed — the unknown stem names the valid set and suggests the closest, and
            // the nested recognized stem says why it will not run. The .txt file is not a script
            // and stays silent.
            assertThat(warned).hasSize(2);
            assertThat(warned).anySatisfy(w -> assertThat(w)
                    .contains("befor-compile.groovy")
                    .contains("will not run")
                    .contains("Did you mean before-compile?")
                    .contains("before-compile / after-compile / after-resources / before-package")
                    .contains("after-build / gate"));
            assertThat(warned).anySatisfy(w -> assertThat(w)
                    .contains("scripts")
                    .contains("before-package.groovy")
                    .contains("top level"));
        } finally {
            RunNotices.clear();
        }
    }

    /** Two modules with the same misspelled stem are two silent no-ops, and both are reported. */
    @Test
    void the_same_unknown_stem_in_two_modules_warns_for_each(@TempDir Path ws) throws Exception {
        Path a = Files.createDirectories(ws.resolve("a/.jk"));
        Path b = Files.createDirectories(ws.resolve("b/.jk"));
        Files.writeString(a.resolve("befor-compile.groovy"), "// typo\n");
        Files.writeString(b.resolve("befor-compile.groovy"), "// typo\n");
        List<String> warned = new ArrayList<>();
        RunNotices.clear();
        RunNotices.openSink(SessionContext.current().io(), (code, message) -> warned.add(message));
        try {
            BuildLogicScripts.discover(a);
            BuildLogicScripts.discover(b);
            assertThat(warned).hasSize(2);
            assertThat(warned).anySatisfy(w -> assertThat(w).contains("a/.jk/befor-compile.groovy"));
            assertThat(warned).anySatisfy(w -> assertThat(w).contains("b/.jk/befor-compile.groovy"));
        } finally {
            RunNotices.clear();
        }
    }

    /** The pragma is a header comment, spelled the same in both languages, and only there. */
    @Test
    void an_always_pragma_in_the_header_is_recognised(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("after-build-sweep.kts"), "// SPDX\n// jk: always\nprintln(1)\n");
        Files.writeString(dir.resolve("after-build.groovy"), "//  JK: ALWAYS \nprintln 1\n");
        Files.writeString(dir.resolve("before-compile.kts"), "// a check\n// says jk: always in prose\n");
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 60; i++) deep.append("// filler ").append(i).append('\n');
        deep.append("// jk: always\n");
        Files.writeString(dir.resolve("after-compile.kts"), deep.toString());

        List<BuildLogicScripts.ScriptTask> tasks = BuildLogicScripts.discover(dir);
        assertThat(tasks)
                .extracting(BuildLogicScripts.ScriptTask::name, BuildLogicScripts.ScriptTask::always)
                .containsExactly(
                        tuple("after-build", true),
                        tuple("after-build-sweep", true),
                        tuple("after-compile", false),
                        tuple("before-compile", false));
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
