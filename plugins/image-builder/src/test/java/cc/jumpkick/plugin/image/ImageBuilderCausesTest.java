// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** An image build's failure text names its message and every cause's, once each. */
class ImageBuilderCausesTest {

    @Test
    void every_cause_is_named_outermost_first() {
        Exception failure = new Exception("outer", new IllegalStateException("middle", new Error("inner")));

        assertThat(ImageBuilder.causes(failure))
                .isEqualTo(
                        "outer; caused by java.lang.IllegalStateException: middle; caused by java.lang.Error: inner");
    }

    @Test
    void a_chain_that_loops_back_is_named_once() {
        Exception outer = new Exception("outer");
        IllegalStateException inner = new IllegalStateException("inner");
        outer.initCause(inner);
        inner.initCause(outer);

        String text = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> ImageBuilder.causes(outer));

        assertThat(text).isEqualTo("outer; caused by java.lang.IllegalStateException: inner");
    }
}
