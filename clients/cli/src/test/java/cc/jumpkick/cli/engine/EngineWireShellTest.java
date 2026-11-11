// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.ProtoSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A request carries the part of the shell the engine reads and what something declares; an
 * import, whose foreign build reads what it likes, carries the whole shell.
 */
class EngineWireShellTest {

    private static final String UNDECLARED = "MY_UNDECLARED_PROBE_VALUE";

    @BeforeEach
    void plant() {
        ProjectInfos.forget();
        System.setProperty("jk.env." + UNDECLARED, "kept-in-the-shell");
        System.setProperty("jk.env.CI", "true");
    }

    @AfterEach
    void clear() {
        System.clearProperty("jk.env." + UNDECLARED);
        System.clearProperty("jk.env.CI");
    }

    @Test
    void a_build_request_carries_what_the_engine_reads_and_not_an_undeclared_variable() {
        String line = EngineWire.withShell("{\"type\":\"build\",\"dir\":\"/p\"}");

        assertThat(ProtoSession.shellOf(line)).containsEntry("CI", "true").doesNotContainKey(UNDECLARED);
    }

    @Test
    void an_import_carries_the_whole_shell() {
        String line = EngineWire.withShell("{\"type\":\"import\",\"dir\":\"/p\"}");

        assertThat(ProtoSession.shellOf(line)).containsEntry(UNDECLARED, "kept-in-the-shell");
    }
}
