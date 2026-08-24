// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.java.ShortenFullyQualifiedTypeReferences;

/**
 * OpenRewrite refuses to rewrite a file it cannot reprint byte-for-byte, and its Javadoc printer
 * cannot reprint the continuation line of a wrapped {@code @param}. The parser answers with a
 * {@code ParseError} — which is itself a {@code SourceFile}, so the recipe simply finds nothing to
 * visit and the changeset comes back empty.
 *
 * <p>That is indistinguishable, at the changeset, from "parsed fine, nothing to shorten", and
 * {@code applyRewrite} used to return the same {@code false} for both. So a file whose entire
 * rewrite pass had been skipped was counted and printed as already clean: 111 of jk's own 2,088
 * Java files, silently un-shortened while {@code jk format} reported success.
 *
 * <p>These two tests are the pair that has to stay apart — a skipped pass must not read as clean,
 * and a clean file must not read as a skipped pass.
 */
class CodeFormatterUnparseableTest {

    /**
     * A record whose {@code @param} wraps onto a continuation line. This is the minimal form of the
     * shape that defeats the printer; {@code clients/cli/.../ParameterModel.java} is the real one it
     * was reduced from.
     */
    private static final String WRAPPED_PARAM = """
        package demo;

        /**
         * A positional parameter as shown in a help screen.
         *
         * @param label the display label, already formatted per CLI convention ({@code <name>} when
         *     required, {@code [name]} when optional)
         * @param description the parameter's description lines (may be empty)
         */
        public record ParameterModel(String label, String[] description) {}
        """;

    /** The same file with the Javadoc on one line — nothing for the printer to trip over. */
    private static final String SINGLE_LINE_PARAM = """
        package demo;

        /** A positional parameter as shown in a help screen. */
        public record ParameterModel(String label, String[] description) {}
        """;

    /**
     * The red-without-the-fix test. Before the fix this returned "no change" — the same answer a
     * clean file gets.
     *
     * <p>If this ever fails with {@code UNCHANGED}, OpenRewrite's Javadoc printer was fixed: that is
     * good news, not a broken test. Re-measure and shrink {@code fqcn-baseline.txt}'s {@code
     * ## unreachable-by-the-formatter} section (its header says how), then pick a fixture the
     * printer still cannot round-trip — or delete this test if there is none left.
     */
    @Test
    void a_file_openrewrite_cannot_round_trip_is_unparseable_not_unchanged(@TempDir Path tmp) throws Exception {
        Path file = write(tmp.resolve("wrapped"), WRAPPED_PARAM);

        CodeFormatter.Rewrite outcome =
                CodeFormatter.applyRewrite(new ShortenFullyQualifiedTypeReferences(), file.toFile(), true, List.of());

        assertThat(outcome)
                .as("the rewrite pass never ran on this file; reporting it as UNCHANGED is reporting"
                        + " success for work that did not happen")
                .isEqualTo(CodeFormatter.Rewrite.UNPARSEABLE);
        assertThat(Files.readString(file))
                .as("apply mode must not write a reprint OpenRewrite itself rejected")
                .isEqualTo(WRAPPED_PARAM);
    }

    /** The other half: the two states must not have merged the other way. */
    @Test
    void a_file_that_parses_with_nothing_to_shorten_is_still_unchanged(@TempDir Path tmp) throws Exception {
        Path file = write(tmp.resolve("single"), SINGLE_LINE_PARAM);

        CodeFormatter.Rewrite outcome =
                CodeFormatter.applyRewrite(new ShortenFullyQualifiedTypeReferences(), file.toFile(), true, List.of());

        assertThat(outcome)
                .as("same declaration, same recipe, no fully-qualified name to shorten — this one"
                        + " really is nothing to do, and must not be reported as a parse failure")
                .isEqualTo(CodeFormatter.Rewrite.UNCHANGED);
    }

    /**
     * The cache half. The stamp store answers "this file is settled" without re-reading it, so a
     * single kind of stamp would have replayed the unparseable file as clean on every run after the
     * first — the finding would exist for exactly one run and then be cached away.
     */
    @Test
    void an_unparseable_stamp_is_not_a_clean_hit(@TempDir Path tmp) {
        byte[] bytes = WRAPPED_PARAM.getBytes(StandardCharsets.UTF_8);
        FormatStampCache cache = new FormatStampCache(tmp.resolve("format-stamps"), "config-digest");

        String unparseable = cache.keyFor(bytes, FormatStampCache.Outcome.UNPARSEABLE);
        cache.record(unparseable);

        assertThat(cache.contains(unparseable)).isTrue();
        assertThat(cache.keyFor(bytes)).isNotEqualTo(unparseable);
        assertThat(cache.contains(cache.keyFor(bytes)))
                .as("an unparseable stamp must never satisfy the clean fast path")
                .isFalse();
    }

    private static Path write(Path dir, String source) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("ParameterModel.java");
        Files.writeString(file, source);
        return file;
    }
}
