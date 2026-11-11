// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.OutputDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The generator's argument vocabulary: {@code ${in}} is the first input, {@code ${inputs}} all of
 * them, {@code ${unpacked}} the directory the entry's jar was extracted into, {@code ${out}} the
 * output dir, {@code ${module.dir}} the module root, {@code ${extra:<artifact>}} a step dependency
 * the step was handed — every one an absolute path. An argument that
 * is exactly {@code ${inputs}} becomes one argument per input; embedded in a longer argument the
 * inputs join with the platform path separator. Any other {@code ${…}} is the tool's own and
 * passes through untouched.
 */
final class Arguments {

    private static final Pattern VAR =
            Pattern.compile("\\$\\{(in|inputs|unpacked|out|module\\.dir|extra:[A-Za-z0-9._-]+)}");

    /** A path under the module root named in an argument: what follows {@code ${module.dir}/} up to a separator. */
    private static final Pattern MODULE_PATH = Pattern.compile("\\$\\{module\\.dir}/([^\\s:;,\"'=]+)");

    private Arguments() {}

    /**
     * What the variables stand for in one run; {@code unpacked} is null when the entry unpacks
     * nothing, and {@code extra} finds a step dependency by its artifact name.
     */
    record Scope(List<Path> inputs, @Nullable Path unpacked, Path out, Path moduleDir, Function<String, Path> extra) {

        Scope(List<Path> inputs, @Nullable Path unpacked, Path out, Path moduleDir) {
            this(inputs, unpacked, out, moduleDir, name -> {
                throw new IllegalArgumentException("${extra:" + name + "} names a step dependency this run has not");
            });
        }

        String joined() {
            return Classpaths.join(inputs);
        }

        Path first() {
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("${in} names the first input, and this run has none");
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

    /**
     * The module-relative paths the arguments name through {@code ${module.dir}/…}, anywhere in an
     * argument ({@code -catalog=${module.dir}/catalog.xml}, a path list), each cut at its first glob
     * segment: what the tool reads that the inputs may not cover. The module root itself and the
     * build output under it are left out, since every build rewrites them.
     */
    static List<String> moduleReads(List<String> args) {
        Set<String> reads = new LinkedHashSet<>();
        for (String arg : args) {
            Matcher m = MODULE_PATH.matcher(arg);
            while (m.find()) {
                String base = Inputs.globBase(m.group(1));
                String normal = Path.of(base).normalize().toString().replace('\\', '/');
                if (normal.isEmpty() || normal.equals(OutputDirs.TARGET) || normal.startsWith(OutputDirs.TARGET + "/"))
                    continue;
                reads.add(normal);
            }
        }
        return List.copyOf(reads);
    }

    /**
     * {@code args} with the value of each flag in {@code pathFlags} ({@code -flag value} or {@code
     * -flag=value}) anchored at the module root when it is a relative path: the tool runs in its
     * output directory, so a path the user wrote relative to the module only resolves as {@code
     * ${module.dir}/value}, which also puts it in the step's key.
     */
    static List<String> anchorPathFlags(List<String> args, Set<String> pathFlags) {
        List<String> out = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            int eq = arg.indexOf('=');
            if (eq > 0 && pathFlags.contains(arg.substring(0, eq))) {
                out.add(arg.substring(0, eq + 1) + anchored(arg.substring(eq + 1)));
            } else if (pathFlags.contains(arg) && i + 1 < args.size()) {
                out.add(arg);
                out.add(anchored(args.get(++i)));
            } else {
                out.add(arg);
            }
        }
        return out;
    }

    private static String anchored(String value) {
        if (value.isEmpty() || value.contains("${") || value.startsWith("/") || value.startsWith("\\")) {
            return value;
        }
        if (value.length() > 1 && value.charAt(1) == ':') return value;
        return "${module.dir}/" + value;
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
                            case "module.dir" -> scope.moduleDir().toString();
                            default ->
                                scope.extra()
                                        .apply(m.group(1).substring("extra:".length()))
                                        .toString();
                        };
                m.appendReplacement(expanded, Matcher.quoteReplacement(value));
            }
            m.appendTail(expanded);
            out.add(expanded.toString());
        }
        return out;
    }
}
