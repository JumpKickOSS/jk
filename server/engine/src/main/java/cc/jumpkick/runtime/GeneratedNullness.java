// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Generated code is unannotated to NullAway, whatever wrote it. NullAway already treats a class
 * marked {@code @Generated} so, but not every generator marks its output (jOOQ does not by
 * default), and an unmarked class in a checked package reads as annotated: every setter parameter
 * non-null, so hand-written code cannot pass it a nullable column's value. Every source the compile
 * reads from under the module's build output ({@code target/}: a plugin step's contributed
 * sources, a KSP round's, build logic's) is generated; its top-level classes are named to NullAway
 * as unannotated. Decided here, for every generator, rather than per generator.
 */
final class GeneratedNullness {

    private GeneratedNullness() {}

    private static final String NULLAWAY_ON = "-Xep:NullAway:";
    private static final String UNANNOTATED = "-XepOpt:NullAway:UnannotatedClasses=";
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");

    /**
     * {@code options} with the generated top-level classes among {@code sources} (those under
     * {@code targetDir}) added to NullAway's unannotated classes, when an Error Prone plugin option
     * turns NullAway on; unchanged otherwise.
     */
    static List<String> withGeneratedUnannotated(List<String> options, List<Path> sources, Path targetDir) {
        int at = nullAwayPlugin(options);
        if (at < 0) return options;
        Path target = targetDir.toAbsolutePath().normalize();
        Set<String> classes = new TreeSet<>();
        for (Path source : sources) {
            String file =
                    source.getFileName() == null ? "" : source.getFileName().toString();
            if (!file.endsWith(".java") || file.equals("package-info.java") || file.equals("module-info.java")) {
                continue;
            }
            if (!source.toAbsolutePath().normalize().startsWith(target)) continue;
            String pkg = packageOf(source);
            String simple = file.substring(0, file.length() - ".java".length());
            classes.add(pkg == null || pkg.isEmpty() ? simple : pkg + "." + simple);
        }
        if (classes.isEmpty()) return options;
        List<String> out = new ArrayList<>(options);
        out.set(at, withClasses(options.get(at), classes));
        return List.copyOf(out);
    }

    /** The index of the Error Prone plugin option that turns NullAway on, or -1. */
    private static int nullAwayPlugin(List<String> options) {
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i);
            if (!option.startsWith(PlannerCompile.PLUGIN_FLAG + "ErrorProne")) continue;
            for (String flag : option.split(" ")) {
                if (flag.startsWith(NULLAWAY_ON) && !flag.equals(NULLAWAY_ON + "OFF")) return i;
            }
        }
        return -1;
    }

    /** {@code option} with {@code classes} joined to its unannotated-classes flag, written when it has none. */
    private static String withClasses(String option, Set<String> classes) {
        String joined = String.join(",", classes);
        List<String> flags = new ArrayList<>(List.of(option.split(" ")));
        for (int i = 0; i < flags.size(); i++) {
            if (flags.get(i).startsWith(UNANNOTATED)) {
                flags.set(i, flags.get(i) + "," + joined);
                return String.join(" ", flags);
            }
        }
        return option + " " + UNANNOTATED + joined;
    }

    /** The package a Java source declares, read from its head; null when it declares none or is unreadable. */
    static @Nullable String packageOf(Path source) {
        try (BufferedReader in = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                Matcher m = PACKAGE.matcher(line);
                if (m.find()) return m.group(1);
                String t = line.strip();
                if (t.startsWith("import ") || t.contains(" class ") || t.startsWith("class ")) return null;
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }
}
