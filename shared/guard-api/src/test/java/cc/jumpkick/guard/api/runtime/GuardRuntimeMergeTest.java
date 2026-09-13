// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The merge of several modules' indexes and the output-directory inference behind the runtime. */
class GuardRuntimeMergeTest {

    @TempDir
    Path dir;

    private static ClassFacts cls(String name) {
        return new ClassFacts(name, 1, "java/lang/Object", List.of(), null, List.of(), List.of(), List.of(), Set.of());
    }

    private Path index(String file, String digest, String... classes) throws Exception {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (String c : classes) m.put(c, cls(c));
        Path p = dir.resolve(file);
        FactsFormat.write(p, new FactsIndex(m, Map.of(), digest));
        return p;
    }

    @Test
    void no_files_is_the_empty_index() throws Exception {
        assertThat(GuardRuntime.merge(List.of())).isSameAs(FactsIndex.EMPTY);
    }

    @Test
    void one_file_is_read_as_is_and_a_missing_one_is_empty() throws Exception {
        Path a = index("a.idx", "x", "a/A", "a/B");
        FactsIndex one = GuardRuntime.merge(List.of(a));
        assertThat(one.classes().keySet()).containsExactly("a/A", "a/B");
        assertThat(one.bodyDigest()).isEqualTo(FactsFormat.digestOf(one));
        assertThat(GuardRuntime.merge(List.of(dir.resolve("absent.idx")))).isSameAs(FactsIndex.EMPTY);
    }

    @Test
    void several_files_union_their_classes_and_join_their_digests() throws Exception {
        Path a = index("a.idx", "x", "a/A");
        Path b = index("b.idx", "y", "b/B", "a/A");
        FactsIndex merged = GuardRuntime.merge(List.of(a, dir.resolve("absent.idx"), b));
        assertThat(merged.classes().keySet()).containsExactly("a/A", "b/B");
        String da = FactsFormat.readHeader(a).orElseThrow().bodyDigest();
        String db = FactsFormat.readHeader(b).orElseThrow().bodyDigest();
        assertThat(merged.bodyDigest()).isEqualTo(da + ";" + db + ";");
        assertThat(merged.stamps())
                .as("a merged index has no single change detector")
                .isEmpty();
    }

    private GuardConfig config(Path root, Path report) {
        return new GuardConfig(
                report, root, "", List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(), null, false,
                null);
    }

    @Test
    void the_output_directory_is_the_first_segment_under_the_root_that_holds_the_report() {
        assertThat(GuardRuntime.outputDirOf(config(dir, dir.resolve("target/guard/report.jsonl"))))
                .isEqualTo("target");
        assertThat(GuardRuntime.outputDirOf(config(dir, dir.resolve("build/report.jsonl"))))
                .isEqualTo("build");
    }

    @Test
    void a_report_at_the_root_or_outside_it_names_no_output_directory() {
        assertThat(GuardRuntime.outputDirOf(config(dir, dir.resolve("report.jsonl"))))
                .isNull();
        assertThat(GuardRuntime.outputDirOf(config(dir.resolve("ws"), dir.resolve("elsewhere/report.jsonl"))))
                .isNull();
    }
}
