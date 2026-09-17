// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import cc.jumpkick.host.Classpaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The generator's argument vocabulary: {@code ${in}} is the first input, {@code ${inputs}} all of
 * them, {@code ${unpacked}} the directory the entry's jar was extracted into, {@code ${out}} the
 * output dir, {@code ${module.dir}} the module root — every one an absolute path. An argument that
 * is exactly {@code ${inputs}} becomes one argument per input; embedded in a longer argument the
 * inputs join with the platform path separator. Any other {@code ${…}} is the tool's own and
 * passes through untouched.
 */
final class Arguments {

    private static final Pattern VAR = Pattern.compile("\\$\\{(in|inputs|unpacked|out|module\\.dir)}");

    private Arguments() {}

    /** What the variables stand for in one run; {@code unpacked} is null when the entry unpacks nothing. */
    record Scope(List<Path> inputs, @Nullable Path unpacked, Path out, Path moduleDir) {
        Scope {
            if (inputs.isEmpty() && unpacked == null) {
                throw new IllegalArgumentException("a generator runs over at least one input");
            }
        }

        String joined() {
            return Classpaths.join(inputs);
        }

        Path first() {
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException(
                        "${in} names the first input, and this entry declares none — it reads ${unpacked}");
            }
            return inputs.getFirst();
        }

        Path unpackedDir() {
            if (unpacked == null) {
                throw new IllegalArgumentException("${unpacked} names the extracted jar, and this entry has no unpack");
            }
            return unpacked;
        }
    }

    static List<String> expand(List<String> args, Scope scope) {
        List<String> out = new ArrayList<>(args.size());
        for (String arg : args) {
            if (arg.equals("${inputs}")) {
                for (Path input : scope.inputs()) out.add(input.toString());
                continue;
            }
            Matcher m = VAR.matcher(arg);
            StringBuilder expanded = new StringBuilder();
            while (m.find()) {
                String value =
                        switch (m.group(1)) {
                            case "in" -> scope.first().toString();
                            case "inputs" -> scope.joined();
                            case "unpacked" -> scope.unpackedDir().toString();
                            case "out" -> scope.out().toString();
                            default -> scope.moduleDir().toString();
                        };
                m.appendReplacement(expanded, Matcher.quoteReplacement(value));
            }
            m.appendTail(expanded);
            out.add(expanded.toString());
        }
        return out;
    }
}
