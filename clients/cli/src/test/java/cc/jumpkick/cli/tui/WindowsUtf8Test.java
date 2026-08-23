// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.HostPlatform;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Gates for {@link WindowsUtf8}. */
class WindowsUtf8Test {

    @AfterEach
    void reset() {
        WindowsUtf8.resetForTest();
    }

    @Test
    void utf8_stream_preserves_pulse_and_ellipsis_bytes() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        // Same charset WindowsUtf8 installs on FileDescriptor.out.
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        out.print("\u25CF \u25B0 cache\u2026");
        out.flush();
        assertThat(buf.toByteArray()).isEqualTo("\u25CF \u25B0 cache\u2026".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void enable_is_noop_off_windows() {
        if (HostPlatform.isWindows()) return;
        WindowsUtf8.enable();
        assertThat(WindowsUtf8.isEnabled()).isFalse();
    }

    @Test
    void enable_marks_enabled_on_windows() {
        if (!HostPlatform.isWindows()) return;
        WindowsUtf8.enable();
        assertThat(WindowsUtf8.isEnabled()).isTrue();
        assertThat(WindowsUtf8.CP_UTF8).isEqualTo(65_001);
    }

    @Test
    void enable_sets_console_output_cp_to_utf8_when_console_present() throws Throwable {
        if (!HostPlatform.isWindows()) return;
        // GetConsoleOutputCP reflects the process console; when this test has one (local
        // PowerShell / conhost), enable() must flip it to 65001. Under redirected CI agents
        // with no console the SetConsole* calls return 0 and the assertion is skipped.
        var get = java.lang.foreign.Linker.nativeLinker()
                .downcallHandle(
                        java.lang.foreign.SymbolLookup.libraryLookup("kernel32", java.lang.foreign.Arena.global())
                                .find("GetConsoleOutputCP")
                                .orElseThrow(),
                        java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT));
        int before = (int) get.invokeExact();
        WindowsUtf8.enable();
        int after = (int) get.invokeExact();
        if (before == 0) return; // no console attached
        assertThat(after).isEqualTo(WindowsUtf8.CP_UTF8);
    }
}
