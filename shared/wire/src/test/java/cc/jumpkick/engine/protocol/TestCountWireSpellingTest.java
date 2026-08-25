// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TestSummary;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * One wire spelling for test counts (JK-2424).
 *
 * <p>jk shipped three: {@code testTotal}/{@code testFailed}/… on {@code plan-finish},
 * {@code testsTotal}/{@code testsFailed}/… on the history verbs, and the nested
 * {@code tests:{total,succeeded,failed,skipped}} object in the journal's {@code record.json} — which
 * is the only one the dashboard ever read. The nested object won because it is the persisted shape:
 * of 218 real {@code record.json} files on this host, every one that recorded a test phase wrote it,
 * and changing it would need a journal migration.
 *
 * <p>These assertions read the <em>wire text</em>, not a round trip through {@link TestSummary}'s own
 * encoder/decoder pair — a round trip is green for a consistently wrong name. Each retired spelling
 * is named as a literal so the encoder cannot quietly grow a second one back.
 */
class TestCountWireSpellingTest {

    /** Every spelling this ticket retired. None may appear in anything the engine emits. */
    private static final List<String> RETIRED = List.of(
            "testTotal",
            "testSucceeded",
            "testFailed",
            "testSkipped",
            "testsTotal",
            "testsSucceeded",
            "testsFailed",
            "testsSkipped");

    /**
     * The exact bytes of the counts object, taken from a real record on disk:
     * {@code .../builds/projects/a66f86e5…/runs/1/record.json}. jk's own self-host run.
     */
    @Test
    void the_counts_object_is_the_journals_own_shape() {
        assertThat(TestSummary.WIRE_KEY).isEqualTo("tests");
        assertThat(TestSummary.countsJson(4323, 4314, 0, 9))
                .isEqualTo("{\"total\":4323,\"succeeded\":4314,\"failed\":0,\"skipped\":9}");
    }

    @Test
    void plan_finish_carries_the_counts_object_and_no_retired_spelling() {
        String line = ProtoEvents.planFinish("/w", true, 12, 10, 1, 1);

        assertThat(line).contains("\"tests\":{\"total\":12,\"succeeded\":10,\"failed\":1,\"skipped\":1}");
        for (String retired : RETIRED) {
            assertThat(line).as("plan-finish must not re-grow %s", retired).doesNotContain(retired);
        }
    }

    @Test
    void image_plan_finish_uses_the_same_object() {
        String line = ProtoEvents.planFinishImage("/w", true, 12, 12, 0, 0, "reg.io/app:1.0", null, "app", "1.0", null);

        assertThat(line).contains("\"tests\":{\"total\":12,\"succeeded\":12,\"failed\":0,\"skipped\":0}");
        for (String retired : RETIRED) {
            assertThat(line)
                    .as("plan-finish kind=image must not re-grow %s", retired)
                    .doesNotContain(retired);
        }
    }

    /** No test phase omits the field outright: the client tells "no tests ran" from "no test step". */
    @Test
    void a_run_with_no_test_phase_omits_the_field_rather_than_writing_minus_one() {
        String line = ProtoEvents.planFinish("/w", true, "up-to-date", -1, -1, -1, -1);

        assertThat(line).doesNotContain("\"tests\"").doesNotContain("-1");
        assertThat(TestSummary.readCounts(line)).isNull();
    }

    /** The decoder reads the encoder's text — and reads it out of a full line, not a bare object. */
    @Test
    void the_decoder_reads_what_plan_finish_wrote() {
        TestSummary counts = TestSummary.readCounts(ProtoEvents.planFinish("/w", false, 7, 4, 2, 1));

        assertThat(counts).isNotNull();
        assertThat(counts.total()).isEqualTo(7);
        assertThat(counts.succeeded()).isEqualTo(4);
        assertThat(counts.failed()).isEqualTo(2);
        assertThat(counts.skipped()).isEqualTo(1);
        assertThat(counts.allPassed()).isFalse();
    }

    /**
     * The journal writes the object with {@code MiniJson}'s pretty printer (spaces after the colon)
     * and the wire writes it compact. One decoder has to read both, or {@code jk history show} and
     * the dashboard disagree about the same run.
     */
    @Test
    void the_decoder_reads_the_journals_pretty_printed_record_verbatim() {
        String recordJson = "{\n  \"id\": \"j-1\",\n  \"success\": true,\n"
                + "  \"tests\": {\n    \"total\": 4323,\n    \"succeeded\": 4314,\n"
                + "    \"failed\": 0,\n    \"skipped\": 9\n  }\n}";

        TestSummary counts = TestSummary.readCounts(recordJson);

        assertThat(counts).isNotNull();
        assertThat(counts.total()).isEqualTo(4323);
        assertThat(counts.succeeded()).isEqualTo(4314);
        assertThat(counts.skipped()).isEqualTo(9);
        assertThat(counts.allPassed()).isTrue();
    }

    /** 217 of the 218 records on this host record no test phase; {@code "tests": null} is that. */
    @Test
    void a_null_tests_field_decodes_to_no_counts() {
        assertThat(TestSummary.readCounts("{\"id\":\"j-1\",\"tests\":null}")).isNull();
        assertThat(TestSummary.readCounts("{\"id\":\"j-1\"}")).isNull();
        assertThat(TestSummary.readCounts(null)).isNull();
    }

    /**
     * The closure arm: no production source anywhere may type a retired spelling again. Asserting on
     * one encoder's output only proves that encoder; the defect this ticket closes was three
     * <em>different</em> owners each spelling the same fact their own way, and only a tree scan can
     * see that. Java production sources plus the SPA, which cannot import Java and so hand-types
     * every field name it reads.
     *
     * <p>The scan fails when it finds too few files, because "no violations" and "scanned nothing"
     * are the same green otherwise.
     */
    @Test
    void no_production_source_types_a_retired_spelling() throws IOException {
        Path root = repoRoot();
        List<Path> scanned = new ArrayList<>();
        Map<String, List<String>> hits = new LinkedHashMap<>();

        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (!isProductionSource(rel)) continue;
                scanned.add(f);
                String body = Files.readString(f, StandardCharsets.UTF_8);
                for (String retired : RETIRED) {
                    if (readsTheKey(body, retired)) {
                        hits.computeIfAbsent(retired, k -> new ArrayList<>()).add(rel);
                    }
                }
            }
        }

        assertThat(scanned)
                .as("production sources scanned under %s — a tiny corpus means the walk missed the tree", root)
                .hasSizeGreaterThan(1_000);
        assertThat(hits)
                .as("retired test-count spellings are back in production source (JK-2424); "
                        + "the one owner is TestSummary.WIRE_KEY + countsJson/readCounts")
                .isEmpty();
    }

    /**
     * True when the source references the name <em>as a field key</em>: quoted (a JSON key in Java or
     * JS) or dotted (a JS property read). A bare identifier is not a key — this file's own prose has
     * to be able to name the spellings it retired, and {@code long failed} is a parameter, not a
     * wire field.
     */
    private static boolean readsTheKey(String body, String name) {
        for (int i = body.indexOf(name); i >= 0; i = body.indexOf(name, i + 1)) {
            if (i == 0) continue;
            char before = body.charAt(i - 1);
            if (before == '"' || before == '\'' || before == '.') return true;
        }
        return false;
    }

    /** Java under {@code src/main/java}, or a dashboard asset — never build output, never tests. */
    private static boolean isProductionSource(String rel) {
        if (rel.contains("/build/classes/")
                || rel.contains("/build/generated")
                || rel.contains("/build/tmp")
                || rel.contains("/build/resources")
                || rel.startsWith("build/")
                || rel.contains("/target/")) {
            return false;
        }
        if (rel.contains("/src/main/java/") && rel.endsWith(".java")) return true;
        return rel.startsWith("clients/web/src/main/resources/web/") && (rel.endsWith(".js") || rel.endsWith(".html"));
    }

    /** The checkout root, walking up from this class's own location — never {@code user.dir}. */
    private static Path repoRoot() throws IOException {
        Path here;
        try {
            here = Path.of(TestCountWireSpellingTest.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath();
        } catch (URISyntaxException e) {
            throw new IOException("cannot locate this test's own class output", e);
        }
        for (Path d = here; d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve("settings.gradle.kts")) && Files.isDirectory(d.resolve("shared/wire"))) {
                return d;
            }
        }
        throw new IOException("cannot locate the jk checkout root from " + here);
    }
}
