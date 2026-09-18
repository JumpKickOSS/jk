// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.protobuf;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The {@code [protobuf] replace} rules over what protoc wrote: each key is a regular expression,
 * each value its replacement ({@code $1} names a group, as {@link Matcher#replaceAll}), applied in
 * declared order to every generated {@code .java} and {@code .kt} file. This is how a module
 * compiles protoc's output against a shaded runtime — {@code com.google.protobuf} rewritten to the
 * package a shading jar carries it under — the way {@code maven-replacer-plugin} does after
 * {@code protobuf-maven-plugin}.
 */
final class GeneratedRewrites {

    private GeneratedRewrites() {}

    /** Rewrite the generated files under {@code gen}; the number of files that changed. */
    static int apply(Path gen, Map<String, String> replace) throws IOException {
        if (replace.isEmpty()) return 0;
        Map<Pattern, String> rules = compile(replace);
        List<Path> files = new ArrayList<>();
        PathUtil.forEachRegularFile(gen, (file, attrs) -> {
            String name = file.getFileName().toString();
            if (name.endsWith(".java") || name.endsWith(".kt")) files.add(file);
        });
        int changed = 0;
        for (Path file : files) {
            String before = Files.readString(file, StandardCharsets.UTF_8);
            String after = before;
            for (Map.Entry<Pattern, String> rule : rules.entrySet()) {
                after = rule.getKey().matcher(after).replaceAll(rule.getValue());
            }
            if (!after.equals(before)) {
                Files.writeString(file, after, StandardCharsets.UTF_8);
                changed++;
            }
        }
        return changed;
    }

    private static Map<Pattern, String> compile(Map<String, String> replace) {
        Map<Pattern, String> rules = new LinkedHashMap<>();
        for (Map.Entry<String, String> rule : replace.entrySet()) {
            try {
                rules.put(Pattern.compile(rule.getKey()), rule.getValue());
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException(
                        "[protobuf] replace: `" + rule.getKey() + "` is not a regular expression: "
                                + e.getDescription(),
                        e);
            }
        }
        return rules;
    }
}
