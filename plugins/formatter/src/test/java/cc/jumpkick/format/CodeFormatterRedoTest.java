// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.SpecWriter;
import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.LineEnding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk format -r} / {@code -F}: the worker formats a file whose bytes carry a settled stamp
 * instead of answering {@code clean} from the stamp, and the run's result is stamped again.
 */
class CodeFormatterRedoTest {

    private static final String CONFIG_KEY = "3333333333333333333333333333333333333333333333333333333333333333";

    @Test
    void a_settled_stamp_answers_clean_without_redo_and_is_ignored_under_it(@TempDir Path tmp) throws Exception {
        // Unformatted Groovy: the semicolon step would strip it. A stamp claims otherwise.
        Path file = tmp.resolve("A.groovy");
        byte[] source = "class A {\n    def x = 1;\n}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, source);
        FormatStampCache stamps = new FormatStampCache(tmp.resolve("stamps"), CONFIG_KEY);
        stamps.record(stamps.keyFor(source));
        var ref = new CodeFormatter.FileRef(CodeFormatter.Kind.GROOVY, file.toFile());

        try (Formatter fmt = groovy()) {
            var spec = new CodeFormatter.Spec();
            spec.apply = false;
            assertThat(CodeFormatter.formatOne(ref, fmt, spec, stamps, null).status())
                    .as("a stamp hit skips the formatter")
                    .isEqualTo("clean");

            spec.redo = true;
            assertThat(CodeFormatter.formatOne(ref, fmt, spec, stamps, null).status())
                    .as("a redo formats the file for real")
                    .isEqualTo("changed");
        }
    }

    @Test
    void a_redo_run_stamps_what_it_formatted(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("B.groovy");
        byte[] source = "class B {}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, source);
        FormatStampCache stamps = new FormatStampCache(tmp.resolve("stamps"), CONFIG_KEY);
        var ref = new CodeFormatter.FileRef(CodeFormatter.Kind.GROOVY, file.toFile());
        var spec = new CodeFormatter.Spec();
        spec.redo = true;

        try (Formatter fmt = groovy()) {
            var result = CodeFormatter.commit(CodeFormatter.formatOne(ref, fmt, spec, stamps, null), stamps);
            assertThat(result.status()).isEqualTo("clean");
        }
        assertThat(stamps.contains(stamps.keyFor(source))).isTrue();
    }

    @Test
    void the_spec_field_the_host_writes_is_the_one_the_worker_reads(@TempDir Path tmp) throws Exception {
        Path spec = tmp.resolve("fmt.spec");
        Files.write(
                spec,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMMAND, "format", "jk-formatter")
                        .configBool("redo", true)
                        .lines(),
                StandardCharsets.UTF_8);

        assertThat(CodeFormatter.Spec.from(PluginSpec.read(spec)).redo).isTrue();
        assertThat(new CodeFormatter.Spec().redo).isFalse();
    }

    private static Formatter groovy() {
        return Formatter.builder()
                .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
                .encoding(StandardCharsets.UTF_8)
                .steps(CodeFormatter.groovySteps())
                .build();
    }
}
