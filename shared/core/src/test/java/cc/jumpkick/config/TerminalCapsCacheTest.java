// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TerminalCapsCache.Check;
import cc.jumpkick.config.TerminalCapsCache.Fingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalCapsCacheTest {

    private static final Fingerprint FP = new Fingerprint("1.0", "Hack Nerd Font", "cfg1", "fonts1");

    @Test
    void a_confirmation_round_trips_and_is_valid_while_nothing_changes(@TempDir Path dir) {
        var cache = TerminalCapsCache.at(dir.resolve("terminal-caps.toml"));
        assertThat(cache.check("iterm2", FP)).isInstanceOf(Check.Absent.class);
        assertThat(cache.put("iterm2", NerdFontCaps.WEDGE_ONLY, "confirmed", FP))
                .isTrue();
        var check = cache.check("iterm2", FP);
        assertThat(check).isInstanceOf(Check.Valid.class);
        assertThat(((Check.Valid) check).entry().caps()).isEqualTo(NerdFontCaps.WEDGE_ONLY);
    }

    @Test
    void each_fingerprint_part_invalidates_and_is_named(@TempDir Path dir) {
        var cache = TerminalCapsCache.at(dir.resolve("c.toml"));
        cache.put("t", NerdFontCaps.ALL, "confirmed", FP);
        assertStale(cache, new Fingerprint("2.0", FP.font(), FP.config(), FP.fonts()), "terminal version");
        assertStale(cache, new Fingerprint(FP.version(), "Menlo", FP.config(), FP.fonts()), "terminal font");
        assertStale(cache, new Fingerprint(FP.version(), FP.font(), "cfg2", FP.fonts()), "terminal config file");
        assertStale(cache, new Fingerprint(FP.version(), FP.font(), FP.config(), "fonts2"), "installed fonts");
    }

    @Test
    void entries_for_other_terminals_are_untouched(@TempDir Path dir) {
        var cache = TerminalCapsCache.at(dir.resolve("c.toml"));
        cache.put("iterm2", NerdFontCaps.ALL, "confirmed", FP);
        cache.put("apple-terminal", NerdFontCaps.NONE, "confirmed", FP);
        cache.remove("iterm2");
        assertThat(cache.check("iterm2", FP)).isInstanceOf(Check.Absent.class);
        assertThat(cache.check("apple-terminal", FP)).isInstanceOf(Check.Valid.class);
    }

    @Test
    void a_stale_entry_is_reported_once_per_change(@TempDir Path dir) {
        var cache = TerminalCapsCache.at(dir.resolve("c.toml"));
        cache.put("t", NerdFontCaps.ALL, "confirmed", FP);
        var changed = new Fingerprint("1.0", "Menlo", "cfg1", "fonts1");
        assertThat(cache.claimHint("t", changed)).isTrue();
        assertThat(cache.claimHint("t", changed)).isFalse();
        var changedAgain = new Fingerprint("1.0", "Monaco", "cfg1", "fonts1");
        assertThat(cache.claimHint("t", changedAgain)).isTrue();
    }

    @Test
    void awkward_font_names_survive_the_file_format(@TempDir Path dir) {
        var cache = TerminalCapsCache.at(dir.resolve("c.toml"));
        var fp = new Fingerprint("", "Weird \"Font\" \\ Name", "", "");
        cache.put("t", NerdFontCaps.ALL, "confirmed", fp);
        assertThat(cache.check("t", fp)).isInstanceOf(Check.Valid.class);
    }

    @Test
    void a_corrupt_or_missing_file_reads_as_empty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("c.toml");
        var cache = TerminalCapsCache.at(file);
        assertThat(cache.read("t")).isEmpty();
        Files.writeString(file, "[terminal.t]\ncaps = \"bogus\"\n\u0000garbage [[[");
        assertThat(cache.read("t")).isEmpty();
        assertThat(cache.put("t", NerdFontCaps.ALL, "confirmed", FP)).isTrue();
        assertThat(cache.read("t")).isPresent();
    }

    @Test
    void a_cache_with_no_file_stores_nothing() {
        var cache = TerminalCapsCache.none();
        assertThat(cache.put("t", NerdFontCaps.ALL, "confirmed", FP)).isFalse();
        assertThat(cache.read("t")).isEmpty();
        assertThat(cache.claimHint("t", FP)).isFalse();
    }

    @Test
    void remote_sessions_are_keyed_by_the_client_address() {
        Map<String, String> env = Map.of("SSH_CONNECTION", "10.0.0.7 5000 10.0.0.1 22", "TERM", "xterm-256color");
        var slot = TerminalCapsCache.slot(TerminalIdentity.of(env::get), env::get);
        assertThat(slot).isEqualTo("unknown-ssh-10_0_0_7");
        Map<String, String> local = Map.of("TERM_PROGRAM", "iTerm.app");
        assertThat(TerminalCapsCache.slot(TerminalIdentity.of(local::get), local::get))
                .isEqualTo("iterm2");
    }

    private static void assertStale(TerminalCapsCache cache, Fingerprint now, String part) {
        var check = cache.check("t", now);
        assertThat(check).isInstanceOf(Check.Stale.class);
        assertThat(((Check.Stale) check).changed()).containsExactly(part);
    }
}
