// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import cc.jumpkick.jsonl.Jsonl;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The contract's cases are well formed, and a line that breaks one is told apart from one that keeps it. */
class WireContractTest {

    @Test
    void every_case_is_named_once_and_conforms_to_itself() {
        Set<String> names = new HashSet<>();
        for (WireCase c : WireContract.CASES) {
            assertThat(names.add(c.name())).as("case name %s", c.name()).isTrue();
            assertThat(c.requestMismatches(c.request())).as(c.name()).isEmpty();
            assertThat(c.replyMismatches(c.replies())).as(c.name()).isEmpty();
            assertThat(c.answers(c.request())).as(c.name()).isTrue();
            assertThat(c.replies()).as(c.name()).isNotEmpty();
        }
    }

    @Test
    void every_varying_field_is_one_the_case_carries() {
        for (WireCase c : WireContract.CASES) {
            for (String field : c.varying()) {
                boolean carried =
                        Jsonl.has(c.request(), field) || c.replies().stream().anyMatch(r -> Jsonl.has(r, field));
                assertThat(carried)
                        .as("%s names %s varying but carries no such field", c.name(), field)
                        .isTrue();
            }
        }
    }

    @Test
    void a_renamed_field_a_changed_kind_and_a_changed_value_each_break_the_case() {
        WireCase hello = WireContract.HELLO;

        assertThat(hello.replyMismatches(List.of("""
                        {"type":"hello-ack","version":"1","processId":1,"startedAt":2,"proto":1,"draining":false,"buildId":"x"}"""))).singleElement(STRING).contains("field pid: missing");
        assertThat(hello.replyMismatches(List.of("""
                        {"type":"hello-ack","version":"1","pid":"1","startedAt":2,"proto":1,"draining":false,"buildId":"x"}""")))
                .singleElement(STRING)
                .contains("field pid: is a string, the contract has a number");
        assertThat(hello.replyMismatches(List.of("""
                        {"type":"hello-ack","version":"1","pid":1,"startedAt":2,"proto":2,"draining":false,"buildId":"x"}""")))
                .singleElement(STRING)
                .contains("field proto: is 2, the contract has 1");
    }

    @Test
    void a_varying_field_and_an_extra_field_keep_the_case() {
        assertThat(WireContract.HELLO.replyMismatches(List.of("""
                        {"type":"hello-ack","version":"1.2.3","pid":1,"startedAt":2,"proto":1,"draining":false,"buildId":"x","extra":true}"""))).isEmpty();
    }

    @Test
    void a_varying_object_still_needs_the_fields_the_case_names() {
        String reply = WireContract.STATUS.replies().get(0).replace("\"label\":", "\"name\":");

        assertThat(WireContract.STATUS.replyMismatches(List.of(reply)))
                .singleElement(STRING)
                .contains("field identity.label: missing");
    }

    @Test
    void a_request_is_answered_only_by_the_case_whose_fields_it_carries() {
        String byDir = """
                {"type":"cancel-request","dir":"/elsewhere"}""";

        assertThat(WireContract.CANCEL_UNKNOWN_JID.answers(byDir)).isFalse();
        assertThat(WireContract.CANCEL_IDLE_DIR.answers(byDir)).isTrue();
        assertThat(WireContract.CANCEL_IDLE_DIR.requestMismatches(byDir))
                .singleElement(STRING)
                .contains("field dir: is \"/elsewhere\"");
    }
}
