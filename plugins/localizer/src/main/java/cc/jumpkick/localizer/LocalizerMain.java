// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.localizer;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The command line the localizer library lacks: {@code --out <dir> [--mask <glob>] [--encoding
 * <charset>] [--key-pattern <regex>] [--strict-types] [--access-modifier-annotations] <resource
 * dir>…} runs {@code org.jvnet.localizer.Generator} over every bundle the mask names under the
 * directories, a {@code Messages} class per bundle, the bundle's directory its package. Runs in the
 * generator step's forked JVM with the localizer plugin's closure on the classpath and reaches the
 * generator by reflection, so this worker compiles against nothing of the plugin's and the forked
 * JVM needs nothing of jk's.
 */
public final class LocalizerMain {

    private static final String PACKAGE = "org.jvnet.localizer.";

    private LocalizerMain() {}

    public static void main(String[] args) throws Exception {
        @Nullable String out = null;
        String mask = "Messages.properties";
        String encoding = "UTF-8";
        @Nullable String keyPattern = null;
        boolean strictTypes = false;
        boolean accessModifierAnnotations = false;
        List<File> dirs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = value(args, ++i);
                case "--mask" -> mask = value(args, ++i);
                case "--encoding" -> encoding = value(args, ++i);
                case "--key-pattern" -> keyPattern = value(args, ++i);
                case "--strict-types" -> strictTypes = true;
                case "--access-modifier-annotations" -> accessModifierAnnotations = true;
                default -> {
                    if (args[i].startsWith("--")) throw new IllegalArgumentException("unknown option " + args[i]);
                    dirs.add(new File(args[i]));
                }
            }
        }
        if (out == null) throw new IllegalArgumentException("--out <dir> is required");
        ClassLoader loader = LocalizerMain.class.getClassLoader();
        Class<?> reporterType = Class.forName(PACKAGE + "Reporter", true, loader);
        Object reporter = Proxy.newProxyInstance(loader, new Class<?>[] {reporterType}, quiet());
        Object config = Class.forName(PACKAGE + "GeneratorConfig", true, loader)
                .getMethod("of", File.class, String.class, reporterType, String.class, boolean.class, boolean.class)
                .invoke(null, new File(out), encoding, reporter, keyPattern, strictTypes, accessModifierAnnotations);
        Class<?> generatorType = Class.forName(PACKAGE + "Generator", true, loader);
        Object generator = generatorType.getConstructor(config.getClass()).newInstance(config);
        Method generate = generatorType.getMethod("generate", File.class, String.class);
        PathMatcher bundle = FileSystems.getDefault().getPathMatcher("glob:" + mask);
        int bundles = 0;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            Path root = dir.toPath().toAbsolutePath().normalize();
            List<Path> found = new ArrayList<>();
            collect(root.toFile(), bundle, found);
            found.sort(null);
            for (Path file : found) {
                call(generate, generator, file.toFile(), root.relativize(file).toString());
                bundles++;
            }
        }
        call(generatorType.getMethod("build"), generator);
        System.out.println("localizer: " + bundles + (bundles == 1 ? " bundle" : " bundles") + " -> " + out);
    }

    /** Every bundle under {@code dir} the mask names, a locale variant ({@code Messages_de.properties}) skipped. */
    private static void collect(File dir, PathMatcher bundle, List<Path> found) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, bundle, found);
            } else if (bundle.matches(child.toPath().getFileName())
                    && !child.getName().contains("_")) {
                found.add(child.toPath());
            }
        }
    }

    /** The generator's {@code Reporter}: its debug lines are noise in a build's output. */
    private static InvocationHandler quiet() {
        return (proxy, method, methodArgs) -> switch (method.getName()) {
            case "toString" -> "quiet";
            case "hashCode" -> 0;
            case "equals" -> methodArgs != null && proxy == methodArgs[0];
            default -> null;
        };
    }

    /** Invoke, surfacing the generator's own exception rather than the reflective wrapper. */
    private static @Nullable Object call(Method method, Object target, Object... methodArgs) throws Exception {
        try {
            return method.invoke(target, methodArgs);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private static String value(String[] args, int at) {
        if (at >= args.length) throw new IllegalArgumentException(args[at - 1] + " needs a value");
        return args[at];
    }
}
