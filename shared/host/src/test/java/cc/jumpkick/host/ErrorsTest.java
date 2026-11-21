// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import org.junit.jupiter.api.Test;

class ErrorsTest {

    @Test
    void message_less_exceptions_surface_their_class_not_the_literal_null() {
        assertThat(Errors.text(new NullPointerException())).contains("NullPointerException");
        assertThat(Errors.text(new IllegalStateException("boom"))).isEqualTo("boom");
        assertThat(Errors.text(new IllegalStateException("  "))).contains("IllegalStateException");
        assertThat(Errors.text(null)).isEqualTo("unknown error");
    }

    /** A file-system exception's message is only the path, so the text leads with what happened to it. */
    @Test
    void a_file_system_exception_names_its_type_beside_the_path() {
        assertThat(Errors.text(new NoSuchFileException("/cache/compile-main@abc/provenance.tsv")))
                .isEqualTo("NoSuchFileException: /cache/compile-main@abc/provenance.tsv");
        assertThat(Errors.text(new IOException("disk full"))).isEqualTo("disk full");
    }

    @Test
    void the_cause_chain_runs_outermost_first_and_stops_where_it_loops() {
        IOException outer = new IOException("outer");
        IllegalStateException middle = new IllegalStateException("middle");
        IOException inner = new IOException("inner");
        outer.initCause(middle);
        middle.initCause(inner);
        inner.initCause(outer);

        assertThat(Errors.chain(outer)).containsExactly(outer, middle, inner);
        assertThat(Errors.inChain(outer, IllegalStateException.class)).isSameAs(middle);
        assertThat(Errors.inChain(outer, NoSuchFileException.class)).isNull();
        assertThat(Errors.rootCause(outer)).isSameAs(inner);
        assertThat(Errors.rootCause(middle)).isSameAs(outer);
        assertThat(Errors.chain(null)).isEmpty();
    }
}
