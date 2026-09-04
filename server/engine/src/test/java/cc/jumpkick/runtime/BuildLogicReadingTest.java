// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.BuildLogicScripts.ScriptKind;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cold reading is a prior, so what it has to get right is order, not milliseconds: a gate above
 * a one-liner, a forked Groovy above a script that joins the shared Kotlin host, a dependency
 * declaration above the same script without one. The one hard rule is where it refuses to answer.
 */
class BuildLogicReadingTest {

    private static final long TOKEN_MS = (long) EffortWeights.TOKEN * EffortWeights.MS_PER_WEIGHT;

    private static Path script(Path dir, String name, String body) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, body);
        return p;
    }

    /** Bytes of code, not bytes of file: a header essay is not compiled. */
    @Test
    void a_bigger_script_reads_higher_than_a_smaller_one(@TempDir Path dir) throws Exception {
        Path small = script(dir, "small.kts", "Files.createDirectories(outDir)\n");
        Path big = script(dir, "big.kts", ("val x = projectDir.resolve(\"src\").toString().length\n").repeat(2000));

        assertThat(BuildLogicReading.millis(big, ScriptKind.KTS))
                .isGreaterThan(10 * BuildLogicReading.millis(small, ScriptKind.KTS));
    }

    @Test
    void comments_and_blank_lines_are_not_code(@TempDir Path dir) throws Exception {
        String body = "Files.createDirectories(outDir)\n";
        Path bare = script(dir, "bare.kts", body);
        Path commented = script(dir, "commented.kts", "// " + "essay ".repeat(400) + "\n\n\n" + body);
        Path blockCommented = script(dir, "block.kts", "/*\n" + "essay\n".repeat(400) + "*/\n" + body);

        long expected = BuildLogicReading.millis(bare, ScriptKind.KTS);
        assertThat(BuildLogicReading.millis(commented, ScriptKind.KTS)).isEqualTo(expected);
        assertThat(BuildLogicReading.millis(blockCommented, ScriptKind.KTS)).isEqualTo(expected);
    }

    /** A Groovy stem forks its own JVM per script; a {@code .kts} joins the build's shared host. */
    @Test
    void the_same_text_reads_higher_as_groovy_than_as_kts(@TempDir Path dir) throws Exception {
        Path p = script(dir, "s.groovy", "outDir.resolve('x.txt').toFile().text = 'ok'\n");

        assertThat(BuildLogicReading.millis(p, ScriptKind.GROOVY))
                .isGreaterThan(BuildLogicReading.millis(p, ScriptKind.KTS));
    }

    @Test
    void a_declared_dependency_costs_more_than_the_same_script_without_one(@TempDir Path dir) throws Exception {
        String body = "println(projectDir)\n";
        Path plain = script(dir, "plain.kts", body);
        Path kts = script(dir, "dep.kts", "@file:DependsOn(\"g:a:1\")\n" + body);
        Path groovy = script(dir, "dep.groovy", "@Grab('g:a:1')\n" + body);

        assertThat(BuildLogicReading.millis(kts, ScriptKind.KTS))
                .isGreaterThan(BuildLogicReading.millis(plain, ScriptKind.KTS));
        assertThat(BuildLogicReading.millis(groovy, ScriptKind.GROOVY))
                .isGreaterThan(BuildLogicReading.millis(plain, ScriptKind.GROOVY));
    }

    /** Past a handful the resolutions share a round trip, and a header must not outweigh the body. */
    @Test
    void dependency_declarations_are_capped(@TempDir Path dir) throws Exception {
        String dep = "@file:DependsOn(\"g:a:1\")\n";
        String filler = "val zzzzzzzzzzzzzzzz = 1\n"; // same length, so only the count differs
        Path four = script(dir, "four.kts", dep.repeat(4) + filler.repeat(396) + "println(1)\n");
        Path fourHundred = script(dir, "many.kts", dep.repeat(400) + "println(1)\n");

        assertThat(BuildLogicReading.millis(fourHundred, ScriptKind.KTS))
                .as("past the cap only the code-size term still grows")
                .isEqualTo(BuildLogicReading.millis(four, ScriptKind.KTS));
    }

    // ---- degrade to TOKEN rather than to a wrong number ------------------------------------------

    @Test
    void a_script_that_cannot_be_read_is_worth_the_placeholder(@TempDir Path dir) {
        assertThat(BuildLogicReading.millis(dir.resolve("gone.kts"), ScriptKind.KTS))
                .isEqualTo(TOKEN_MS);
        assertThat(BuildLogicReading.millis(dir, ScriptKind.KTS))
                .as("a directory where a script was expected reads as no signal, not as free")
                .isEqualTo(TOKEN_MS);
    }

    @Test
    void a_script_with_no_code_is_worth_the_placeholder(@TempDir Path dir) throws Exception {
        assertThat(BuildLogicReading.millis(script(dir, "empty.kts", ""), ScriptKind.KTS))
                .isEqualTo(TOKEN_MS);
        assertThat(BuildLogicReading.millis(script(dir, "comment.kts", "// nothing here\n"), ScriptKind.GROOVY))
                .isEqualTo(TOKEN_MS);
        assertThat(BuildLogicReading.millis(script(dir, "block.kts", "/* nothing\n * here\n */\n"), ScriptKind.KTS))
                .isEqualTo(TOKEN_MS);
    }

    /** The reading is only worth having if it is above the placeholder for a script with real code. */
    @Test
    void any_script_with_real_code_reads_above_the_placeholder(@TempDir Path dir) throws Exception {
        Path kts = script(dir, "s.kts", "println(1)\n");
        Path groovy = script(dir, "s.groovy", "println 1\n");

        assertThat(BuildLogicReading.millis(kts, ScriptKind.KTS)).isGreaterThan(TOKEN_MS);
        assertThat(BuildLogicReading.millis(groovy, ScriptKind.GROOVY)).isGreaterThan(TOKEN_MS);
    }
}
