// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.CompilerProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The sources an incremental compile recompiled, read from what its reporter writes to {@code debug}. */
class KcLoggerTest {

    @Test
    void each_iteration_names_the_sources_it_recompiled(@TempDir Path dir) {
        Path a = dir.resolve("src/main/kotlin/a/A.kt");
        Path b = dir.resolve("src/main/kotlin/a/B.kt");
        Path c = dir.resolve("src/main/kotlin/a/C.kt");
        KcLogger logger = logger(List.of(a, b, c));

        logger.debug("Compile iteration:");
        logger.debug("  src/main/kotlin/a/A.kt <- was modified since last time");
        logger.debug("some other debug line");
        logger.debug("  src/main/kotlin/a/C.kt");
        logger.debug("Compile iteration:");
        logger.debug("  " + b + " <- dependency of A.kt");

        assertThat(logger.compiled()).containsExactly(a.toString(), b.toString());
    }

    @Test
    void a_compile_that_reported_no_iteration_names_nothing(@TempDir Path dir) {
        KcLogger logger = logger(List.of(dir.resolve("A.kt")));

        logger.debug("  A.kt <- was modified since last time");

        assertThat(logger.compiled()).isNull();
    }

    @Test
    void a_file_that_is_not_one_of_the_compiles_sources_is_not_named(@TempDir Path dir) {
        KcLogger logger = logger(List.of(dir.resolve("A.kt")));

        logger.debug("Compile iteration:");
        logger.debug("  elsewhere/Z.kt");

        assertThat(logger.compiled()).isEmpty();
    }

    private static KcLogger logger(List<Path> sources) {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        return new KcLogger(
                new CompilerProtocol(new ProtocolWriter(new PrintStream(wire, true, StandardCharsets.UTF_8), "##K:")),
                sources);
    }
}
