// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The {@code --debug-jvm} spec grammar, its canonical spelling, and the one flag it stands for. */
class DebugJvmTest {

    @Test
    void a_bare_flag_is_the_stock_remote_debug_address_suspended() {
        assertThat(DebugJvm.parse("")).isEqualTo(DebugJvm.DEFAULT);
        assertThat(DebugJvm.parse(null)).isEqualTo(DebugJvm.DEFAULT);
        assertThat(DebugJvm.DEFAULT.address()).isEqualTo("localhost:5005");
        assertThat(DebugJvm.DEFAULT.suspend()).isTrue();
    }

    @Test
    void a_port_alone_keeps_the_host_and_the_suspend_default() {
        DebugJvm d = DebugJvm.parse("8000");
        assertThat(d.host()).isEqualTo("localhost");
        assertThat(d.port()).isEqualTo(8000);
        assertThat(d.suspend()).isTrue();
    }

    @Test
    void zero_means_the_client_picks_the_port() {
        DebugJvm d = DebugJvm.parse("0");
        assertThat(d.portChosenByClient()).isTrue();
        assertThat(d.withPort(41_000).portChosenByClient()).isFalse();
        assertThat(d.withPort(41_000).address()).isEqualTo("localhost:41000");
    }

    @Test
    void host_and_suspend_override_in_either_order() {
        assertThat(DebugJvm.parse("*:5005,suspend=n")).isEqualTo(new DebugJvm("*", 5005, false));
        assertThat(DebugJvm.parse("suspend=false,0.0.0.0:7000")).isEqualTo(new DebugJvm("0.0.0.0", 7000, false));
        assertThat(DebugJvm.parse("suspend=n").port()).isEqualTo(DebugJvm.DEFAULT_PORT);
    }

    @Test
    void the_spelling_round_trips() {
        for (String spec : new String[] {"", "0", "9009", "*:5005,suspend=n", "suspend=y", "127.0.0.1:1"}) {
            DebugJvm d = DebugJvm.parse(spec);
            assertThat(DebugJvm.parse(d.spelling())).as(spec).isEqualTo(d);
        }
        assertThat(DebugJvm.DEFAULT.spelling()).isEqualTo("localhost:5005,suspend=y");
        assertThat(DebugJvm.parseOrNull(" ")).isNull();
        assertThat(DebugJvm.parseOrNull("0")).isEqualTo(DebugJvm.parse("0"));
    }

    @Test
    void the_agent_flag_is_a_dt_socket_server_at_the_address() {
        assertThat(DebugJvm.DEFAULT.agentArg())
                .isEqualTo("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:5005");
        assertThat(DebugJvm.parse("*:6006,suspend=n").agentArg())
                .isEqualTo("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:6006");
    }

    @Test
    void bad_specs_are_refused_with_the_grammar() {
        assertThatThrownBy(() -> DebugJvm.parse("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[[host:]port][,suspend=y|n]");
        assertThatThrownBy(() -> DebugJvm.parse("5005,pause=n")).hasMessageContaining("unknown --debug-jvm setting");
        assertThatThrownBy(() -> DebugJvm.parse("suspend=maybe")).hasMessageContaining("suspend must be y or n");
        assertThatThrownBy(() -> DebugJvm.parse("70000")).hasMessageContaining("0..65535");
    }
}
