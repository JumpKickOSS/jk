// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.jdk.HostPlatform;
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
    void enable_is_a_noop_on_this_host() {
        assumeFalse(HostPlatform.isWindows());
        PrintStream before = System.out;
        WindowsUtf8.enable();
        assertThat(System.out).isSameAs(before);
    }

    @Test
    void spoofed_windows_retargets_system_out_to_utf8() {
        String previousOs = System.getProperty("os.name");
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        try {
            System.setProperty("os.name", "Windows 11");
            // The kernel32 lookup throws on this host and enable() swallows it; the stream
            // retarget still runs, and that is the half CliOutput reads its charset from.
            WindowsUtf8.enable();
            assertThat(System.out).isNotSameAs(previousOut);
            assertThat(System.out.charset()).isEqualTo(StandardCharsets.UTF_8);
            assertThat(System.err.charset()).isEqualTo(StandardCharsets.UTF_8);
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
            if (previousOs == null) System.clearProperty("os.name");
            else System.setProperty("os.name", previousOs);
            WindowsUtf8.resetForTest();
        }
    }

    @Test
    void enable_sets_console_output_cp_to_utf8_when_console_present() throws Throwable {
        assumeTrue(HostPlatform.isWindows());
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
        assumeTrue(before != 0); // no console attached
        assertThat(after).isEqualTo(65_001);
    }
}
