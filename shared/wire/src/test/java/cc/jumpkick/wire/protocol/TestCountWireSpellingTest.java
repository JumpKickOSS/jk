// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TestSummary;
import org.junit.jupiter.api.Test;

/**
 * One wire spelling for test counts.
 *
 * <p>jk shipped three: {@code testTotal}/{@code testFailed}/… on {@code plan-finish},
 * {@code testsTotal}/{@code testsFailed}/… on the history verbs, and the nested
 * {@code tests:{total,succeeded,failed,skipped}} object in the journal's {@code record.json} — which
 * is the only one the dashboard ever read. The nested object won because it is the persisted shape:
 * of 218 real {@code record.json} files on this host, every one that recorded a test phase wrote it,
 * and changing it would need a journal migration.
 *
 * <p>These assertions read the <em>wire text</em>, not a round trip through {@link TestSummary}'s own
 * encoder/decoder pair — a round trip is green for a consistently wrong name. The tree-wide ban on
 * the retired spellings is the {@code retired-wire-keys} rule in {@code jk-guards.toml}: a JUnit
 * scan over other modules' sources is keyed to this module alone, a rule in the tree lane is keyed
 * to the text it reads.
 */
class TestCountWireSpellingTest {

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
    void plan_finish_carries_the_counts_object() {
        String line = ProtoEvents.planFinish("/w", true, 12, 10, 1, 1);

        assertThat(line).contains("\"tests\":{\"total\":12,\"succeeded\":10,\"failed\":1,\"skipped\":1}");
    }

    @Test
    void image_plan_finish_uses_the_same_object() {
        String line = ProtoEvents.planFinishImage("/w", true, 12, 12, 0, 0, "reg.io/app:1.0", null, "app", "1.0", null);

        assertThat(line).contains("\"tests\":{\"total\":12,\"succeeded\":12,\"failed\":0,\"skipped\":0}");
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
        TestSummary counts = requireNonNull(TestSummary.readCounts(ProtoEvents.planFinish("/w", false, 7, 4, 2, 1)));

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

        TestSummary counts = requireNonNull(TestSummary.readCounts(recordJson));

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
}
