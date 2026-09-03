// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A refuse is a wire fact ({@code refused}), never an inference from message shape. */
class AffectedTestsReportTest {

    @Test
    void an_empty_message_refuse_survives_the_round_trip() {
        AffectedTestsReport r = AffectedTestsReport.decode(
                AffectedTestsReport.error("outside-selection", "").encode());
        assertThat(r.refused()).isTrue();
        assertThat(r.refuseCode()).isEqualTo("outside-selection");
    }

    @Test
    void a_message_that_is_literally_null_survives_verbatim() {
        AffectedTestsReport r = AffectedTestsReport.decode(
                AffectedTestsReport.error("manifest", "null").encode());
        assertThat(r.refused()).isTrue();
        assertThat(r.error()).isEqualTo("null");
    }

    @Test
    void an_anonymous_refuse_gets_the_internal_code() {
        AffectedTestsReport r = AffectedTestsReport.error("", "boom");
        assertThat(r.refuseCode()).isEqualTo("internal");
        assertThat(AffectedTestsReport.decode(r.encode()).refuseCode()).isEqualTo("internal");
    }

    @Test
    void success_round_trips_rows_and_never_reads_as_refused() {
        AffectedTestsReport r = AffectedTestsReport.of(
                20, 2, List.of(new AffectedTestsReport.Row(90, "com.acme.FooTest", "abi-import:com.acme.Foo")));
        AffectedTestsReport back = AffectedTestsReport.decode(r.encode());
        assertThat(back.refused()).isFalse();
        assertThat(back.error()).isNull();
        assertThat(back.rows())
                .containsExactly(new AffectedTestsReport.Row(90, "com.acme.FooTest", "abi-import:com.acme.Foo"));
    }
}
