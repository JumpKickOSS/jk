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

    @Test
    void keychain_secrets_round_trip_beside_the_shell_keyed_by_pointer() {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("secret-service repo/nexus", "p=ss \"w\" \\ 1");
        secrets.put("secret-service forge/github.com", "ghp_x");
        String line =
                ProtoSession.withKeychain(ProtoSession.withShell("{\"type\":\"x\"}", Map.of("PATH", "/bin")), secrets);

        assertThat(ProtoSession.keychainOf(line)).isEqualTo(secrets);
        assertThat(ProtoSession.shellOf(line)).isEqualTo(Map.of("PATH", "/bin"));
        assertThat(ProtoSession.withKeychain("{\"type\":\"x\"}", Map.of())).isEqualTo("{\"type\":\"x\"}");
    }
}
