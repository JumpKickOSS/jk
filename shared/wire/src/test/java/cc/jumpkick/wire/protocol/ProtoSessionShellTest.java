// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtoSessionShellTest {

    @Test
    void the_shell_round_trips_values_with_separators_and_quotes() {
        Map<String, String> shell = new LinkedHashMap<>();
        shell.put("PATH", "/usr/bin:/bin");
        shell.put("WEIRD", "a=b \"quoted\" \\ back");
        shell.put("=C:", "C:\\work"); // Windows' per-drive working directory
        String line = ProtoSession.withShell("{\"type\":\"x\",\"dir\":\"/p\"}", shell);

        assertThat(ProtoSession.shellOf(line)).isEqualTo(shell);
    }

    @Test
    void a_variable_named_like_a_request_field_does_not_read_as_that_field() {
        // Lookups match a key at any depth, so the shell must not spell names as keys.
        String line = ProtoSession.withShell("{\"type\":\"x\",\"dir\":\"/p\"}", Map.of("modules", "evil", "dir", "/x"));

        assertThat(Jsonl.str(line, "modules")).isNull();
        assertThat(Jsonl.str(line, "dir")).isEqualTo("/p");
    }

    @Test
    void no_shell_leaves_the_line_untouched() {
        String line = "{\"type\":\"x\"}";
        assertThat(ProtoSession.withShell(line, Map.of())).isEqualTo(line);
        assertThat(ProtoSession.shellOf(line)).isEmpty();
    }
}
