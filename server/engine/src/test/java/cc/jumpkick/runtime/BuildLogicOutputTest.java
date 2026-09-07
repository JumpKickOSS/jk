// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A build-logic script's captured stdout, as it reaches the output channel. */
class BuildLogicOutputTest {

    @Test
    void a_transcript_is_attributed_to_its_script_and_split_on_any_terminator() {
        List<String> out = new ArrayList<>();
        BuildLogicSupport.emitLines("after-build-sweep.kts", "jk sweep: reset a\r\njk sweep: reset b\n", out::add);
        assertThat(out).containsExactly("after-build-sweep.kts:", "  jk sweep: reset a", "  jk sweep: reset b");
    }

    @Test
    void a_silent_script_emits_nothing_not_even_a_header() {
        List<String> out = new ArrayList<>();
        BuildLogicSupport.emitLines("guard.kts", "", out::add);
        BuildLogicSupport.emitLines("guard.kts", "  \n", out::add);
        BuildLogicSupport.emitLines("guard.kts", null, out::add);
        assertThat(out).isEmpty();
    }
}
