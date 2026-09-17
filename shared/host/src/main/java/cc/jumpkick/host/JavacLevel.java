// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.ArrayList;
import java.util.List;

/**
 * The javac options that set a compile's language and class-file level. {@code --release N} is
 * the default: it compiles against that release's API as well as its bytecode. javac refuses it
 * beside {@code --add-exports} or {@code --add-reads} of a system module ({@code java.*}, {@code
 * jdk.*}), so a compile whose arguments export one — Hadoop's annotations reach {@code
 * jdk.javadoc.internal.tool} — carries {@code -source N -target N} instead, against the running
 * JDK's API, as the Maven compiler plugin does for a POM that writes {@code <source>}/{@code
 * <target>}; {@code -Xlint:-options} keeps javac's bootstrap-classpath warning for that pairing
 * out of the diagnostics.
 */
public final class JavacLevel {

    private static final List<String> MODULE_GRAPH = List.of("--add-modules", "--add-exports", "--add-reads");

    private JavacLevel() {}

    /**
     * The options in {@code args} that shape the module graph — {@code --add-modules}, {@code
     * --add-exports} and {@code --add-reads}, in either spelling, in order — which javadoc reads as
     * javac does; a flag with no value is dropped.
     */
    public static List<String> moduleGraphOptions(List<String> args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (MODULE_GRAPH.contains(arg)) {
                if (i + 1 >= args.size()) break;
                out.add(arg);
                out.add(args.get(++i));
            } else if (MODULE_GRAPH.stream().anyMatch(flag -> arg.startsWith(flag + "="))) {
                out.add(arg);
            }
        }
        return List.copyOf(out);
    }

    /** The level options for {@code release}, given the compile's other arguments; empty for {@code 0}. */
    public static List<String> options(int release, List<String> args) {
        if (release <= 0) return List.of();
        String level = Integer.toString(release);
        if (exportsSystemModule(args)) return List.of("-source", level, "-target", level, "-Xlint:-options");
        return List.of("--release", level);
    }

    /**
     * Whether {@code args} carry an {@code --add-exports} or {@code --add-reads} whose source module
     * is a system module, in either spelling ({@code --add-exports m/p=t} or {@code --add-exports=m/p=t}).
     */
    public static boolean exportsSystemModule(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            String value;
            if (arg.equals("--add-exports") || arg.equals("--add-reads")) {
                if (i + 1 >= args.size()) return false;
                value = args.get(i + 1);
            } else if (arg.startsWith("--add-exports=") || arg.startsWith("--add-reads=")) {
                value = arg.substring(arg.indexOf('=') + 1);
            } else {
                continue;
            }
            int end = value.indexOf('/');
            if (end < 0) end = value.indexOf('=');
            String module = end < 0 ? value : value.substring(0, end);
            if (module.startsWith("java.") || module.startsWith("jdk.")) return true;
        }
        return false;
    }
}
