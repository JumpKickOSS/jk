// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.orchestrator.ClassCones;
import cc.jumpkick.test.ClassReads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite's code files are listed with since when each has held its content: a file keeps its time
 * while it holds, takes the start of the run that saw it new or changed, and a class past its own
 * code bound is replayed only when every file held since before it finished.
 */
class SuiteCodeFilesTest {

    @Test
    void a_file_keeps_its_time_while_its_content_holds(@TempDir Path ws) throws Exception {
        Path zinc = Files.writeString(ws.resolve("zinc.jar"), "zinc 1");
        Path scala = Files.writeString(ws.resolve("scala.jar"), "scala");
        String first = header(SuiteCodeFiles.listed(null, code(zinc, scala), ws, 1_000));
        assertThat(SuiteCodeFiles.since(first, ws))
                .as("both new: the run that saw them stands on them")
                .isEqualTo(999);

        String second = header(SuiteCodeFiles.listed(first, code(zinc), ws, 5_000));
        assertThat(SuiteCodeFiles.since(second, ws))
                .as("a run that read one of them keeps both, at their old time")
                .isEqualTo(999);

        Files.writeString(zinc, "zinc 2");
        assertThat(SuiteCodeFiles.since(second, ws))
                .as("a file changed since the list was written: no class stands on it")
                .isEqualTo(Long.MAX_VALUE);
        String third = header(SuiteCodeFiles.listed(second, code(), ws, 9_000));
        assertThat(SuiteCodeFiles.since(third, ws))
                .as("listed again, it holds from the run that saw it changed")
                .isEqualTo(8_999);
    }

    @Test
    void past_its_bound_the_list_starts_over_or_says_so(@TempDir Path ws) throws Exception {
        List<Path> jars = new ArrayList<>();
        for (int i = 0; i < ClassReads.SUITE_CODE_KEPT; i++) {
            jars.add(Files.writeString(ws.resolve("lib-" + i + ".jar"), "lib " + i));
        }
        String full = header(SuiteCodeFiles.listed(null, ClassReads.SuiteCode.of(jars), ws, 1_000));
        Path extra = Files.writeString(ws.resolve("extra.jar"), "extra");
        String over = header(SuiteCodeFiles.listed(full, code(extra), ws, 5_000));
        assertThat(SuiteCodeFiles.since(over, ws))
                .as("the run's own files, from its start: every class of an earlier run runs again")
                .isEqualTo(4_999);

        List<Path> more = new ArrayList<>(jars);
        more.add(extra);
        SuiteCodeFiles.Listed past = SuiteCodeFiles.listed(full, ClassReads.SuiteCode.of(more), ws, 9_000);
        assertThat(past.more()).isTrue();
        assertThat(SuiteCodeFiles.since(header(past), ws)).isEqualTo(Long.MAX_VALUE);
        assertThat(SuiteCodeFiles.since("{\"kind\":\"suite\"}", ws))
                .as("a header that lists nothing of its code stands for nothing")
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void a_class_past_its_own_code_bound_replays_only_while_its_suites_code_held(@TempDir Path ws) throws Exception {
        ClassCones.Index index = ClassCones.index(List.of(Files.createDirectories(ws.resolve("classes"))));
        ClassCones.Cone cone = index.cone("demo.T");
        ClassKeys.Observed seen = new ClassKeys.Observed(true, List.of(), false, List.of(), true, List.of());
        String key = ClassKeys.key(
                        "",
                        cone,
                        null,
                        "suite",
                        seen,
                        Map.of(),
                        ClassKeys.Observed.NONE,
                        index.digest(),
                        ClassKeys.SuiteReadSet.NONE,
                        ws,
                        null,
                        new HashMap<>())
                .key();
        String row = JsonFields.object()
                .string("launch", "")
                .string("outcome", "passed")
                .number("ranAt", 2_000)
                .bool("attributed", true)
                .string("classKey", key)
                .optionalTrue("codeMore", true)
                .finish();

        assertThat(shadow(index, ws, 1_999).whyRun(row, "", "demo.T"))
                .as("every code file held since before it finished")
                .isNull();
        assertThat(shadow(index, ws, 2_000).whyRun(row, "", "demo.T"))
                .as("one began to hold what it holds now after it finished")
                .isEqualTo("code moved");
        assertThat(shadow(index, ws, Long.MAX_VALUE).whyRun(row, "", "demo.T")).isEqualTo("code moved");
    }

    private static ClassShadow shadow(ClassCones.Index index, Path ws, long codeSince) {
        return new ClassShadow(
                index,
                "suite",
                ws,
                launch -> ClassKeys.Observed.NONE,
                launch -> ClassKeys.Observed.NONE,
                Map.of(),
                ClassKeys.SuiteReadSet.NONE,
                ClassKeys.SuiteReadSet.NONE,
                codeSince,
                false,
                "stamp");
    }

    private static ClassReads.SuiteCode code(Path... files) {
        return ClassReads.SuiteCode.of(List.of(files));
    }

    private static String header(SuiteCodeFiles.Listed listed) {
        return JsonFields.object()
                .string("kind", "suite")
                .array(SuiteCodeFiles.FIELD, listed.entries())
                .optionalTrue(SuiteCodeFiles.MORE, listed.more())
                .finish();
    }
}
