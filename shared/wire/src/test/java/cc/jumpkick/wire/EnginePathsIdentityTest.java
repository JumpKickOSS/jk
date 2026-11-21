// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The artifact store, and on Windows the logon kind, are part of the engine's identity.
 *
 * <p>It has to be. A daemon does not inherit the client's environment, so an invocation asking for a
 * different {@code JK_STORE_DIR} used to reuse an engine already bound to another store and the setting
 * did nothing at all — {@code /proc/<engine>/environ} carried no such variable. Sharing a state dir does
 * not imply agreeing about where downloads belong.
 *
 * <p>A Windows engine and its children keep the logon of the client that started it, and an ssh
 * logon cannot reach the desktop's credential vault, so the two kinds never share an engine.
 */
class EnginePathsIdentityTest {

    @Test
    void a_different_store_gets_a_different_engine(@TempDir Path tmp) {
        Path state = tmp.resolve("state");

        String a = EnginePaths.keyFor(state, tmp.resolve("store-a"));
        String b = EnginePaths.keyFor(state, tmp.resolve("store-b"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void the_same_pair_shares_one_engine(@TempDir Path tmp) {
        Path state = tmp.resolve("state");
        Path store = tmp.resolve("store");

        assertThat(EnginePaths.keyFor(state, store)).isEqualTo(EnginePaths.keyFor(state, store));
    }

    @Test
    void a_different_state_dir_still_gets_a_different_engine(@TempDir Path tmp) {
        Path store = tmp.resolve("store");

        assertThat(EnginePaths.keyFor(tmp.resolve("state-a"), store))
                .isNotEqualTo(EnginePaths.keyFor(tmp.resolve("state-b"), store));
    }

    @Test
    void the_two_components_cannot_be_confused_for_each_other(@TempDir Path tmp) {
        // Concatenating the paths without a separator would make (state="a", store="bc") collide with
        // (state="ab", store="c"), so the identity would silently merge two different configurations.
        assertThat(EnginePaths.keyFor(tmp.resolve("a"), tmp.resolve("bc")))
                .isNotEqualTo(EnginePaths.keyFor(tmp.resolve("ab"), tmp.resolve("c")));
    }

    @Test
    void relative_and_absolute_spellings_of_one_pair_agree(@TempDir Path tmp) {
        Path state = tmp.resolve("state");
        Path store = tmp.resolve("store");
        Path storeViaDotDot = tmp.resolve("x").resolve("..").resolve("store");

        assertThat(EnginePaths.keyFor(state, storeViaDotDot)).isEqualTo(EnginePaths.keyFor(state, store));
    }

    @Test
    void a_windows_logon_without_a_desktop_gets_its_own_engine(@TempDir Path tmp) {
        Path state = tmp.resolve("state");
        Path store = tmp.resolve("store");

        assertThat(EnginePaths.keyFor(state, store, EngineLogon.NO_DESKTOP))
                .isNotEqualTo(EnginePaths.keyFor(state, store, EngineLogon.DESKTOP));
    }

    @Test
    void the_desktop_and_every_other_os_keep_the_state_dir_and_store_key(@TempDir Path tmp) {
        Path state = tmp.resolve("state");
        Path store = tmp.resolve("store");
        String plain = Hashing.sha256Hex(state.toAbsolutePath().normalize() + "\u0000"
                        + store.toAbsolutePath().normalize())
                .substring(0, 16);

        assertThat(EnginePaths.keyFor(state, store, EngineLogon.ANY)).isEqualTo(plain);
        assertThat(EnginePaths.keyFor(state, store, EngineLogon.DESKTOP)).isEqualTo(plain);
    }

    @Test
    void the_logon_kind_is_read_from_the_session_name_on_windows_alone() {
        assertThat(EngineLogon.of(true, "Console")).isEqualTo(EngineLogon.DESKTOP);
        assertThat(EngineLogon.of(true, "RDP-Tcp#3")).isEqualTo(EngineLogon.DESKTOP);
        assertThat(EngineLogon.of(true, null)).isEqualTo(EngineLogon.NO_DESKTOP);
        assertThat(EngineLogon.of(true, "")).isEqualTo(EngineLogon.NO_DESKTOP);
        assertThat(EngineLogon.of(false, null)).isEqualTo(EngineLogon.ANY);
        assertThat(EngineLogon.of(false, "Console")).isEqualTo(EngineLogon.ANY);
    }

    @Test
    void resolved_paths_carry_the_key(@TempDir Path tmp) {
        Path state = tmp.resolve("state");
        EnginePaths.Paths p = EnginePaths.resolve(state, tmp.resolve("store"));

        assertThat(p.socket().getFileName().toString()).startsWith(p.key());
        assertThat(p.dir()).isEqualTo(state.resolve("engine"));
    }
}
