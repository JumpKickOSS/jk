// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.antlr;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The ANTLR tool as the Maven plugin drives it: {@code --out <dir> --src <dir> [--lib <dir>]
 * [--package <name>] [--no-listener] [--visitor] [--encoding <charset>] [--arg <tool arg>]…
 * <grammar>…} runs {@code org.antlr.v4.Tool} once per grammar directory, the directory's path
 * under {@code --src} as the generated classes' package and as their subdirectory under
 * {@code --out} — that subdirectory the tool's output directory, so a parser's {@code tokenVocab}
 * finds the lexer's {@code .tokens} beside it — a grammar under {@code --lib} being an import and
 * never a target. Runs in the generator step's forked JVM with the tool's closure on the
 * classpath and reaches the tool by reflection, so this worker compiles against nothing of
 * ANTLR's and the forked JVM needs nothing of jk's.
 */
public final class AntlrMain {

    private static final String TOOL = "org.antlr.v4.Tool";

    private AntlrMain() {}

    public static void main(String[] args) throws Exception {
        int exit = run(args);
        if (exit != 0) System.exit(exit);
    }

    /** The exit status: zero when every grammar generated without a tool error. */
    static int run(String[] args) throws Exception {
        @Nullable String out = null;
        @Nullable String src = null;
        @Nullable String lib = null;
        @Nullable String pkg = null;
        List<String> toolArgs = new ArrayList<>(List.of("-listener", "-no-visitor"));
        List<Path> grammars = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = value(args, ++i);
                case "--src" -> src = value(args, ++i);
                case "--lib" -> lib = value(args, ++i);
                case "--package" -> pkg = value(args, ++i);
                case "--no-listener" -> toolArgs.set(0, "-no-listener");
                case "--visitor" -> toolArgs.set(1, "-visitor");
                case "--encoding" -> toolArgs.addAll(List.of("-encoding", value(args, ++i)));
                case "--arg" -> toolArgs.add(value(args, ++i));
                default -> {
                    if (args[i].startsWith("--")) throw new IllegalArgumentException("unknown option " + args[i]);
                    grammars.add(Path.of(args[i]).toAbsolutePath().normalize());
                }
            }
        }
        if (out == null) throw new IllegalArgumentException("--out <dir> is required");
        if (src == null) throw new IllegalArgumentException("--src <dir> is required");
        Path srcDir = Path.of(src).toAbsolutePath().normalize();
        Path libDir = lib == null
                ? srcDir.resolve("imports")
                : Path.of(lib).toAbsolutePath().normalize();
        Path outDir = Path.of(out).toAbsolutePath().normalize();
        List<String> common = new ArrayList<>();
        if (new File(libDir.toString()).isDirectory()) common.addAll(List.of("-lib", libDir.toString()));
        common.addAll(toolArgs);

        int generated = 0;
        int errors = 0;
        for (Map.Entry<String, List<String>> folder :
                byFolder(srcDir, libDir, grammars).entrySet()) {
            List<String> argv = new ArrayList<>(
                    List.of("-o", outDir.resolve(folder.getKey()).toString()));
            argv.addAll(common);
            String folderPackage = pkg != null ? pkg : folder.getKey().replace('/', '.');
            if (!folderPackage.isEmpty() && !argv.contains("-package")) argv.addAll(List.of("-package", folderPackage));
            for (String grammar : folder.getValue())
                argv.add(srcDir.resolve(grammar).toString());
            errors += process(argv);
            generated += folder.getValue().size();
        }
        System.out.println("antlr: " + generated + (generated == 1 ? " grammar" : " grammars") + " -> " + out
                + (errors == 0 ? "" : ", " + errors + (errors == 1 ? " error" : " errors")));
        return errors == 0 ? 0 : 1;
    }

    /**
     * The grammars to generate from, grouped by their directory relative to {@code src} (the
     * empty key for the root), each as a source-relative path; one under {@code lib} is left out.
     */
    static Map<String, List<String>> byFolder(Path src, Path lib, List<Path> grammars) {
        Map<String, List<String>> folders = new LinkedHashMap<>();
        for (Path grammar : grammars) {
            if (grammar.startsWith(lib)) continue;
            if (!grammar.startsWith(src)) {
                throw new IllegalArgumentException(grammar + " is not under the grammar directory " + src);
            }
            String relative = src.relativize(grammar).toString().replace(File.separatorChar, '/');
            int slash = relative.lastIndexOf('/');
            String folder = slash < 0 ? "" : relative.substring(0, slash);
            folders.computeIfAbsent(folder, k -> new ArrayList<>()).add(relative);
        }
        return folders;
    }

    /** One tool run over {@code argv}; the error count. */
    private static int process(List<String> argv) throws Exception {
        ClassLoader loader = AntlrMain.class.getClassLoader();
        Class<?> toolType = Class.forName(TOOL, true, loader);
        Object tool = toolType.getConstructor(String[].class).newInstance((Object) argv.toArray(String[]::new));
        try {
            toolType.getMethod("processGrammarsOnCommandLine").invoke(tool);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
        return (int) toolType.getMethod("getNumErrors").invoke(tool);
    }

    private static String value(String[] args, int at) {
        if (at >= args.length) throw new IllegalArgumentException(args[at - 1] + " needs a value");
        return args[at];
    }
}
