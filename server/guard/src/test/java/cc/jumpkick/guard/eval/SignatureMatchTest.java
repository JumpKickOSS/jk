// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A method signature matches method descriptors only; an empty argument list means none. */
class SignatureMatchTest {

    @Test
    void an_empty_argument_list_matches_the_no_argument_method_and_nothing_else() {
        Signature io = Signature.parse("cc.jumpkick.run.JkThreads#io()");
        assertThat(io.methodMatches("io", "()Ljava/util/concurrent/ExecutorService;"))
                .isTrue();
        assertThat(io.methodMatches("io", "(I)Ljava/util/concurrent/ExecutorService;"))
                .isFalse();
        // A field of the same name has a field descriptor: no match, and no exception reading it.
        assertThat(io.methodMatches("io", "Ljava/util/concurrent/ExecutorService;"))
                .isFalse();
    }

    @Test
    void any_arguments_still_match_every_overload() {
        Signature any = Signature.parse("cc.jumpkick.run.JkThreads#io(**)");
        assertThat(any.methodMatches("io", "()Ljava/util/concurrent/ExecutorService;"))
                .isTrue();
        assertThat(any.methodMatches("io", "(I)V")).isTrue();
    }
}
