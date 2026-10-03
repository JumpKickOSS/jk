// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The output directory a webpack or Vite config writes to, read from a literal path in the config:
 * webpack's {@code output.path} (a string, or the last string of a {@code path.join(__dirname, …)}),
 * Vite's {@code build.outDir}. A config that computes the path any other way is not read.
 */
final class BundlerOutput {

    private static final List<String> WEBPACK =
            List.of("webpack.config.js", "webpack.config.cjs", "webpack.config.mjs", "webpack.config.ts");
    private static final List<String> VITE = List.of(
            "vite.config.ts",
            "vite.config.js",
            "vite.config.mjs",
            "vite.config.mts",
            "vite.config.cjs",
            "vite.config.cts");
    private static final Pattern STRING = Pattern.compile("([\"'`])((?:(?!\\1)[^\\\\\\n]|\\\\.)*)\\1");
    private static final Pattern WEBPACK_OUTPUT = Pattern.compile("\\boutput\\s*:\\s*\\{");
    private static final Pattern PATH_KEY = Pattern.compile("\\bpath\\s*:\\s*");
    private static final Pattern VITE_OUT_DIR = Pattern.compile("\\boutDir\\s*:\\s*([\"'`])([^\"'`]+)\\1");

    private BundlerOutput() {}

    /**
     * A config and where in it the output path is written.
     *
     * @param literal the path as written, relative to the config's directory
     * @param start the literal's first character in {@code text}
     * @param paths every relative path string in the config, in order
     */
    record Found(Path file, String text, String literal, int start, List<String> paths) {

        /** The directory the bundler writes to. */
        Path target(Path dir) {
            return dir.resolve(literal).normalize();
        }

        /** The config with the output path replaced by {@code out}. */
        String rewritten(String out) {
            return text.substring(0, start) + out + text.substring(start + literal.length());
        }
    }

    /** The bundler output in {@code dir}'s webpack or Vite config, or null when none is readable. */
    static @Nullable Found find(Path dir) {
        for (String name : WEBPACK) {
            Found f = read(dir.resolve(name), true);
            if (f != null) return f;
        }
        for (String name : VITE) {
            Found f = read(dir.resolve(name), false);
            if (f != null) return f;
        }
        return null;
    }

    private static @Nullable Found read(Path file, boolean webpack) {
        if (!Files.isRegularFile(file)) return null;
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            return null;
        }
        List<String> paths = paths(text);
        if (!webpack) {
            Matcher m = VITE_OUT_DIR.matcher(text);
            return m.find() ? new Found(file, text, m.group(2), m.start(2), paths) : null;
        }
        Matcher output = WEBPACK_OUTPUT.matcher(text);
        if (!output.find()) return null;
        int end = closing(text, output.end() - 1);
        Matcher key = PATH_KEY.matcher(text).region(output.end(), end);
        if (!key.find()) return null;
        int exprEnd = expressionEnd(text, key.end(), end);
        Matcher literal = STRING.matcher(text).region(key.end(), exprEnd);
        String last = null;
        int at = -1;
        while (literal.find()) {
            last = literal.group(2);
            at = literal.start(2);
        }
        return last == null ? null : new Found(file, text, last, at, paths);
    }

    /**
     * Every relative path a config or {@code package.json} names in its strings, a command string
     * split into its words: {@code "stylelint src/main/scss"} names {@code src/main/scss}.
     */
    static List<String> paths(String text) {
        List<String> paths = new ArrayList<>();
        Matcher all = STRING.matcher(text);
        while (all.find()) {
            for (String word : all.group(2).split("\\s+")) {
                if (word.contains("/") && !word.contains("://") && !word.startsWith("/") && !word.startsWith("@")) {
                    paths.add(word);
                }
            }
        }
        return paths;
    }

    /** The index of the brace closing the one at {@code open}. */
    private static int closing(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return text.length();
    }

    /** Where the value starting at {@code from} ends: the first top-level comma or the block's end. */
    private static int expressionEnd(String text, int from, int limit) {
        int depth = 0;
        for (int i = from; i < limit; i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return limit;
    }
}
