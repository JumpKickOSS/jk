// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.runtime.BuildLogicScripts.ScriptKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A cold price in milliseconds for a build-logic script nothing has timed yet, read from the
 * script's own text.
 *
 * <p>It is a prior, not a prediction: the first successful build records a wall and {@link
 * BuildLogicEffort} uses that instead, forever. Its only job is to be better than the constant it
 * replaces on a fresh clone or a cold CI runner, where every script — a 3,000-line house-rule gate
 * and a one-line file copy alike — otherwise reads the same.
 *
 * <p><strong>Three signals, in the order they matter.</strong>
 *
 * <ol>
 *   <li><strong>Language.</strong> The floor, and the largest single term for a small script. A
 *       {@code .groovy} forks its own JVM per script; a {@code .kts} joins a session the build
 *       already has running. Measured against this repo's scripts that is ~600 ms against ~250 ms —
 *       both well above the placeholder, and in opposite directions from each other.
 *   <li><strong>Code size.</strong> Comments and blank lines stripped, because a script's header
 *       essay is not compiled. A {@code .kts} pays kotlinc, at roughly 112 ms per KiB of code; a
 *       {@code .groovy} pays a parse, at roughly 20 ms. This is what separates the gate from the
 *       one-liner, and it is the term that carries the rank.
 *   <li><strong>Declared dependencies.</strong> {@code @Grab} / {@code @Grapes} in Groovy,
 *       {@code @file:DependsOn} / {@code @file:Repository} in Kotlin. Each is a resolution, and on
 *       a cold host a download.
 * </ol>
 *
 * <p><strong>Honest placeholder over confident guess.</strong> A script with no code at all — empty,
 * or nothing but comments — and one that cannot be read are both worth {@link EffortWeights#TOKEN},
 * exactly as before. There is no signal there to read, and a made-up duration would be worse than
 * saying "something runs here".
 *
 * <p>Costs one streamed pass over the file and holds one line at a time, so a script is never worth
 * more to the ETA than it costs to read.
 */
final class BuildLogicReading {

    private BuildLogicReading() {}

    /** Cold price of a script that runs in the build's shared {@code .kts} host. */
    private static final long KTS_BASE_MS = 250;

    /** Cold price of a script that forks its own Groovy JVM. */
    private static final long GROOVY_BASE_MS = 600;

    /** kotlinc, per KiB of comment-stripped code. */
    private static final long KTS_MS_PER_KIB = 112;

    /** Groovy's parse, per KiB of comment-stripped code. */
    private static final long GROOVY_MS_PER_KIB = 20;

    /** One declared dependency: a resolution, and on a cold host a download. */
    private static final long DEPENDENCY_MS = 200;

    /**
     * Dependency lines counted. Past a handful the resolutions share one repository round trip, and
     * an unbounded count would let a header outweigh everything else the reading knows.
     */
    private static final int MAX_DEPENDENCIES = 4;

    private static final List<String> DEPENDENCY_TOKENS =
            List.of("@file:DependsOn", "@file:Repository", "@Grab", "@Grapes");

    /**
     * Milliseconds to charge for {@code script}, or {@code TOKEN}-worth when it carries no signal.
     *
     * @param kind which host runs it — the single largest term for a small script
     */
    static long millis(Path script, ScriptKind kind) {
        Shape shape = scan(script);
        if (shape == null || shape.codeBytes() == 0) return tokenMillis();
        boolean kts = kind == ScriptKind.KTS;
        long base = kts ? KTS_BASE_MS : GROOVY_BASE_MS;
        long perKib = kts ? KTS_MS_PER_KIB : GROOVY_MS_PER_KIB;
        return base
                + shape.codeBytes() * perKib / 1024
                + (long) Math.min(shape.dependencies(), MAX_DEPENDENCIES) * DEPENDENCY_MS;
    }

    /** The placeholder a script with nothing to read is worth. */
    static long tokenMillis() {
        return (long) EffortWeights.TOKEN * EffortWeights.MS_PER_WEIGHT;
    }

    /**
     * @param codeBytes bytes of code once comments and blank lines are gone
     * @param dependencies lines declaring a dependency, counted once per line
     */
    private record Shape(long codeBytes, int dependencies) {}

    /** One streamed pass; {@code null} when the file cannot be read. */
    private static @Nullable Shape scan(Path script) {
        long codeBytes = 0;
        int dependencies = 0;
        boolean inBlockComment = false;
        try (Stream<String> lines = Files.lines(script, StandardCharsets.UTF_8)) {
            for (String raw : (Iterable<String>) lines::iterator) {
                StringBuilder code = new StringBuilder(raw.length());
                inBlockComment = stripComments(raw, inBlockComment, code);
                String line = code.toString().strip();
                if (line.isEmpty()) continue;
                codeBytes += line.length() + 1;
                if (declaresDependency(line)) dependencies++;
            }
        } catch (IOException | RuntimeException unreadable) {
            // Includes a script that is not valid UTF-8. Unreadable is no signal, not zero cost.
            return null;
        }
        return new Shape(codeBytes, dependencies);
    }

    /**
     * Append {@code raw}'s code to {@code out}, dropping line and block comments, and return whether
     * a block comment is still open afterwards. String literals are not tracked — a comment marker
     * inside one costs the reading a few bytes, which is cheaper than lexing a language the ETA does
     * not otherwise need to understand.
     */
    private static boolean stripComments(String raw, boolean inBlockComment, StringBuilder out) {
        int i = 0;
        while (i < raw.length()) {
            if (inBlockComment) {
                int end = raw.indexOf("*/", i);
                if (end < 0) return true;
                i = end + 2;
                inBlockComment = false;
                continue;
            }
            int line = raw.indexOf("//", i);
            int block = raw.indexOf("/*", i);
            if (line >= 0 && (block < 0 || line < block)) {
                out.append(raw, i, line);
                return false;
            }
            if (block >= 0) {
                out.append(raw, i, block);
                i = block + 2;
                inBlockComment = true;
                continue;
            }
            out.append(raw, i, raw.length());
            return false;
        }
        return inBlockComment;
    }

    private static boolean declaresDependency(String line) {
        for (String token : DEPENDENCY_TOKENS) {
            if (line.contains(token)) return true;
        }
        return false;
    }
}
