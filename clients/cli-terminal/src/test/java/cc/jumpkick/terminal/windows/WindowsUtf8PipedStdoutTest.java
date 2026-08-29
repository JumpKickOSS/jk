// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.windows;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Output through the rewired streams must survive a pipe. The rewired stdout buffered 8&nbsp;KB
 * with autoflush only on a console, and nothing flushes at JVM exit — so a piped caller (every
 * agent harness) lost whatever a short-lived command printed: a usage error arrived as silence.
 */
class WindowsUtf8PipedStdoutTest {

    @Test
    void bootstrap_println_reaches_a_piped_consumer() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "java.exe" : "java")
                .toString();
        Process p = new ProcessBuilder(
                        java, "-cp", System.getProperty("java.class.path"), PipedStdoutMain.class.getName())
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
        assertThat(out).contains("piped-line-alive");
        assertThat(out).contains("piped-tail-alive"); // the print() tail rides the shutdown flush
    }
}
