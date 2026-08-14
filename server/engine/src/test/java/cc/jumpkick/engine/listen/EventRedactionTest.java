// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.test.JUnitLauncher;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Truncation-seam masking on failure fields (JK-1960). */
class EventRedactionTest {

    private static TestFailureInfo failure(String message, String stack) {
        return new TestFailureInfo("g:a", "junit-jupiter", "C", "m()", "E", message, stack, 1, null, 0, 0, List.of());
    }

    @Test
    void a_secret_cut_by_stack_truncation_is_masked_at_the_seam() {
        SecretRedactor r = SecretRedactor.of(List.of("s3cret-token-value"));
        String stack = "E: leak s3cret-tok" + JUnitLauncher.STACK_TRUNCATION_MARKER + "12345 more chars)";
        TestFailureInfo safe = EventRedaction.redactFailure(r, failure("m", stack));
        assertThat(safe.stack())
                .isEqualTo(
                        "E: leak " + SecretRedactor.MASK + JUnitLauncher.STACK_TRUNCATION_MARKER + "12345 more chars)");
    }

    @Test
    void a_secret_cut_by_message_truncation_is_masked_at_the_seam() {
        SecretRedactor r = SecretRedactor.of(List.of("s3cret-token-value"));
        String message = "expected s3cret-tok" + JUnitLauncher.MESSAGE_TRUNCATION_MARKER + "9 more chars)";
        TestFailureInfo safe = EventRedaction.redactFailure(r, failure(message, ""));
        assertThat(safe.message())
                .isEqualTo(
                        "expected " + SecretRedactor.MASK + JUnitLauncher.MESSAGE_TRUNCATION_MARKER + "9 more chars)");
    }

    @Test
    void untruncated_fields_do_not_pay_the_seam_pass() {
        SecretRedactor r = SecretRedactor.of(List.of("s3cret-token-value"));
        TestFailureInfo f = failure("plain message", "E: fine\n\tat C.m(C.java:1)");
        assertThat(EventRedaction.redactFailure(r, f)).isSameAs(f);
    }
}
