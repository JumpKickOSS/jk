// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jsonschema;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * jsonschema2pojo's command line over the step's inputs: {@code [--root <dir>]… <flag>… --
 * <file>…}. An input under a root is generated from the root, as the Maven and Gradle plugins do,
 * so a subdirectory is a subpackage; an input under no root (a schema the registry pinned) is its
 * own source. Runs in the generator step's forked JVM with jsonschema2pojo's closure on the
 * classpath and reaches it by reflection, so this worker compiles against nothing of it.
 */
public final class JsonSchemaMain {

    private static final String CLI = "org.jsonschema2pojo.cli.Jsonschema2PojoCLI";

    private JsonSchemaMain() {}

    public static void main(String[] args) throws Exception {
        try {
            Class.forName(CLI).getMethod("main", String[].class).invoke(null, (Object) cli(args));
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    /** jsonschema2pojo's arguments: the flags, then {@code -s} and the sources comma-joined. */
    static String[] cli(String[] args) {
        List<Path> roots = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        boolean files = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (files) {
                sources.add(sourceOf(Path.of(arg).toAbsolutePath().normalize(), roots));
            } else if (arg.equals("--")) {
                files = true;
            } else if (arg.equals("--root") && i + 1 < args.length) {
                roots.add(Path.of(args[++i]).toAbsolutePath().normalize());
            } else {
                flags.add(arg);
            }
        }
        flags.add("-s");
        flags.add(String.join(",", sources));
        return flags.toArray(String[]::new);
    }

    private static String sourceOf(Path file, List<Path> roots) {
        for (Path root : roots) {
            if (file.startsWith(root)) return root.toString();
        }
        return file.toString();
    }
}
