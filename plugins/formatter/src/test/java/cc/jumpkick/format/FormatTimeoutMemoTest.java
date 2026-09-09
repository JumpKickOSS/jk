// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.LineEnding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file that defeated the formatter is not attempted again, and is not forgotten either.
 *
 * <p>Neither cache would record it: a settled stamp means "these bytes are finished", and stamping a
 * timeout would let {@code --check} pass on a file that was never formatted. So the memo is its own
 * list, and it has to report the file as an error every run while costing the run nothing.
 *
 * <p>These drive the real {@code formatOne} through Spotless's semicolon step, which needs no
 * resolved jars — so a run that <em>does</em> format the file is observable in the bytes on disk.
 */
class FormatTimeoutMemoTest {

    @TempDir
    Path dir;

    /** Spotless's semicolon step strips a trailing one, so a real run leaves a mark on disk. */
    private static final String DIRTY = """
            class Sample {
                def go() {
                    return 1;
                }
            }
            """;

    @Test
    void a_remembered_timeout_is_reported_without_the_file_being_touched() throws Exception {
        Path file = write(DIRTY);
        FormatStampCache memo = cache();
        memo.recordTimeout(memo.keyFor(Files.readAllBytes(file)), 2_000);

        CodeFormatter.FileResult result = format(file, memo, 2_000);

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.msg())
                .contains("timed out at a 2000 ms limit on an earlier run")
                .contains("was not retried");
        assertThat(Files.readString(file)).isEqualTo(DIRTY);
    }

    @Test
    void the_message_carries_the_post_mortem_when_the_source_explains_the_stall() throws Exception {
        Path file = write(DIRTY.replace("return 1;", "return a(b(c(d(e(f(g(h(i(j(1))))))))));"));
        FormatStampCache memo = cache();
        memo.recordTimeout(memo.keyFor(Files.readAllBytes(file)), 2_000);

        CodeFormatter.FileResult result = format(file, memo, 2_000);

        assertThat(result.msg()).contains("deepest expression nesting here is");
    }

    @Test
    void editing_the_file_retires_the_memo() throws Exception {
        Path file = write(DIRTY);
        FormatStampCache memo = cache();
        memo.recordTimeout(memo.keyFor(Files.readAllBytes(file)), 2_000);
        Files.writeString(file, DIRTY.replace("return 1;", "return 2;"), StandardCharsets.UTF_8);

        CodeFormatter.FileResult result = format(file, memo, 2_000);

        assertThat(result.status()).isEqualTo("changed");
        assertThat(Files.readString(file)).doesNotContain(";");
    }

    /** Raising the limit is a request to try again — the point of having the knob. */
    @Test
    void a_raised_limit_retries_the_file() throws Exception {
        Path file = write(DIRTY);
        FormatStampCache memo = cache();
        memo.recordTimeout(memo.keyFor(Files.readAllBytes(file)), 2_000);

        CodeFormatter.FileResult result = format(file, memo, 10_000);

        assertThat(result.status()).isEqualTo("changed");
    }

    @Test
    void a_tighter_limit_keeps_the_memo() throws Exception {
        Path file = write(DIRTY);
        FormatStampCache memo = cache();
        memo.recordTimeout(memo.keyFor(Files.readAllBytes(file)), 10_000);

        CodeFormatter.FileResult result = format(file, memo, 2_000);

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.msg()).contains("10000 ms limit");
    }

    /** With the bound off the caller has asked for however long it takes, memo or no memo. */
    @Test
    void an_unbounded_run_retries_the_file() throws Exception {
        Path file = write(DIRTY);
        FormatStampCache memo = cache();
        memo.recordTimeout(memo.keyFor(Files.readAllBytes(file)), 2_000);

        CodeFormatter.FileResult result = format(file, memo, 0);

        assertThat(result.status()).isEqualTo("changed");
    }

    @Test
    void a_successful_format_retires_the_memo_for_the_bytes_it_produced() throws Exception {
        Path file = write(DIRTY);
        FormatStampCache memo = cache();
        String dirtyKey = memo.keyFor(Files.readAllBytes(file));
        memo.recordTimeout(dirtyKey, 2_000);

        assertThat(format(file, memo, 10_000).status()).isEqualTo("changed");

        // The formatted bytes are stamped settled, and the file no longer looks like a timeout.
        assertThat(memo.contains(memo.keyFor(Files.readAllBytes(file)))).isTrue();
        assertThat(memo.timedOutAt(memo.keyFor(Files.readAllBytes(file)))).isZero();
    }

    @Test
    void a_memo_survives_the_run_that_recorded_it() throws Exception {
        Path file = write(DIRTY);
        Path root = dir.resolve("stamps");
        FormatStampCache first = new FormatStampCache(root, CONFIG_KEY);
        String key = first.keyFor(Files.readAllBytes(file));
        first.recordTimeout(key, 2_000);
        first.save();

        FormatStampCache reopened = new FormatStampCache(root, CONFIG_KEY);

        assertThat(reopened.timedOutAt(key)).isEqualTo(2_000);
        assertThat(reopened.timeoutCount()).isEqualTo(1);
    }

    /** The list is a cache: a line it cannot read is a file that gets formatted for real. */
    @Test
    void an_unreadable_memo_line_is_not_a_verdict() throws Exception {
        Path root = dir.resolve("stamps");
        Files.createDirectories(root);
        Files.writeString(root.resolve(CONFIG_KEY + ".timeouts"), "not-a-line\nabc notanumber\ndef 0\n");

        FormatStampCache memo = new FormatStampCache(root, CONFIG_KEY);

        assertThat(memo.timeoutCount()).isZero();
    }

    /** Other configurations' lists coexist here, and the residue sweep must not take them. */
    @Test
    void the_residue_sweep_keeps_the_timeout_list() throws Exception {
        Path root = dir.resolve("stamps");
        Files.createDirectories(root);
        Path other = root.resolve("0000000000000000000000000000000000000000000000000000000000000000.timeouts");
        Files.writeString(other, "abc 2000\n");
        Files.createDirectories(root.resolve("aa/bb"));

        new FormatStampCache(root, CONFIG_KEY);

        assertThat(other).exists();
        assertThat(root.resolve("aa")).doesNotExist();
    }

    private static final String CONFIG_KEY = "1111111111111111111111111111111111111111111111111111111111111111";

    private Path write(String source) throws Exception {
        Path file = dir.resolve("Sample.groovy");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    private FormatStampCache cache() {
        return new FormatStampCache(dir.resolve("stamps"), CONFIG_KEY);
    }

    /** One file through the real per-file path, with a formatter that needs no resolved jars. */
    private CodeFormatter.FileResult format(Path file, FormatStampCache memo, long limitMs) {
        var spec = new CodeFormatter.Spec();
        spec.fileTimeoutMs = limitMs;
        try (Formatter fmt = Formatter.builder()
                .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
                .encoding(StandardCharsets.UTF_8)
                .steps(CodeFormatter.groovySteps())
                .build()) {
            return CodeFormatter.formatOne(
                    new CodeFormatter.FileRef(CodeFormatter.Kind.GROOVY, file.toFile()), fmt, spec, memo, null);
        }
    }

    /** Guards the assumption the rest of the class rests on: a real run does rewrite the file. */
    @Test
    void the_stand_in_formatter_really_formats() throws Exception {
        Path file = write(DIRTY);

        CodeFormatter.FileResult result = format(file, cache(), 2_000);

        assertThat(result.status()).isEqualTo("changed");
        assertThat(Files.readString(file)).doesNotContain(";").contains("return 1");
    }
}
