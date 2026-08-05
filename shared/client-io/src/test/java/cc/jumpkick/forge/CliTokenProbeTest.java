// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** JK-1468: the probe's 5s cap must hold whatever the helper does with its pipes. */
@DisabledOnOs(OS.WINDOWS)
class CliTokenProbeTest {

    @TempDir
    Path tmp;

    @Test
    void returns_the_trimmed_token_on_success() throws Exception {
        Path helper = script("#!/bin/sh\necho '  ghp_example  '\n");
        assertThat(CliTokenProbe.REAL.token(List.of(helper.toString()))).contains("ghp_example");
    }

    @Test
    void chatty_stderr_does_not_hang_or_pollute_the_token() throws Exception {
        // 8 MB of stderr overflows any pipe buffer: the old inline readAllBytes deadlocked here
        // because nothing drained stderr, so the child never exited.
        Path helper = script("#!/bin/sh\ndd if=/dev/zero bs=1024 count=8192 >&2 2>/dev/null\necho tok\n");
        long start = System.nanoTime();
        var token = CliTokenProbe.REAL.token(List.of(helper.toString()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(token).contains("tok");
        assertThat(elapsedMs).as("must not hang").isLessThan(30_000);
    }

    @Test
    void a_hung_helper_is_capped_and_yields_empty() throws Exception {
        Path helper = script("#!/bin/sh\nsleep 600\n");
        long start = System.nanoTime();
        var token = CliTokenProbe.REAL.token(List.of(helper.toString()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(token).isEmpty();
        assertThat(elapsedMs).as("5s cap is real").isLessThan(30_000);
    }

    @Test
    void non_zero_exit_yields_empty() throws Exception {
        Path helper = script("#!/bin/sh\necho tok\nexit 1\n");
        assertThat(CliTokenProbe.REAL.token(List.of(helper.toString()))).isEmpty();
    }

    @Test
    void missing_binary_yields_empty() {
        assertThat(CliTokenProbe.REAL.token(List.of(tmp.resolve("no-such-binary").toString())))
                .isEmpty();
    }

    private Path script(String body) throws IOException {
        Path script = Files.createTempFile(tmp, "helper", ".sh");
        Files.writeString(script, body);
        Files.setPosixFilePermissions(
                script,
                EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        return script;
    }
}
