// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.command.Exit;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompilerProtocolTest {

    private static ProtocolWriter writer() {
        return new ProtocolWriter(
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), "##JKTEST:");
    }

    /** A failed compile is that compile's failure; the served worker stays up for the next. */
    @Test
    void anExceptionIsTheCompilesFailure(@TempDir Path dir) throws Exception {
        Path spec = Files.writeString(dir.resolve("spec"), "");
        assertThat(CompilerProtocol.compileFromSpec("jk-test", List.of(spec.toString()), writer(), (s, p) -> {
                    throw new IllegalStateException("bad source");
                }))
                .isEqualTo(Exit.SOFTWARE);
    }

    /**
     * An {@link Error} ends the worker: kept warm, it would serve the next compile with whatever the
     * error left broken, a class that failed to initialise or a half-run static.
     */
    @Test
    void anErrorIsNotSwallowed(@TempDir Path dir) throws Exception {
        Path spec = Files.writeString(dir.resolve("spec"), "");
        assertThatThrownBy(() ->
                        CompilerProtocol.compileFromSpec("jk-test", List.of(spec.toString()), writer(), (s, p) -> {
                            throw new NoClassDefFoundError("demo/Gone");
                        }))
                .isInstanceOf(NoClassDefFoundError.class);
    }
}
