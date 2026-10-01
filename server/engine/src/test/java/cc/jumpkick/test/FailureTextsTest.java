// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A failure text a report already holds is handed back, so a repeated trace is kept once. */
class FailureTextsTest {

    @Test
    void an_equal_text_returns_the_copy_already_held() {
        FailureTexts texts = new FailureTexts();
        String first = texts.of(new String("at com.example.Context.load(Context.java:42)"));
        String again = texts.of(new String("at com.example.Context.load(Context.java:42)"));

        assertThat(again).isSameAs(first);
        assertThat(texts.of("another")).isEqualTo("another");
        assertThat(texts.of(null)).isNull();
    }
}
