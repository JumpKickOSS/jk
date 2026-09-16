// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Classpaths;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a module with a {@code module-info.java} adds to its javac invocations. The compiler worker
 * puts the compile classpath on {@code --module-path} whenever it compiles a descriptor or patches a
 * module; this class derives the test compile's side of that, the way Maven's compiler plugin does:
 * the test sources patch the main module ({@code --patch-module}) and the module reads the unnamed
 * module the test classpath forms ({@code --add-reads <module>=ALL-UNNAMED}).
 */
final class ModularCompile {

    /** The compiled module descriptor a main classes tree carries. */
    static final String DESCRIPTOR_CLASS = "module-info.class";

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private ModularCompile() {}

    /** The module name in {@code classesDir}'s descriptor, or null when the tree declares no module. */
    static @Nullable String moduleName(@Nullable Path classesDir) {
        if (classesDir == null) return null;
        Path descriptor = classesDir.resolve(DESCRIPTOR_CLASS);
        if (!Files.isRegularFile(descriptor)) return null;
        try (InputStream in = Files.newInputStream(descriptor)) {
            return ModuleDescriptor.read(in).name();
        } catch (IOException | InvalidModuleDescriptorException unreadable) {
            return null;
        }
    }

    /**
     * {@code base} plus the test compile's module options when {@code mainClasses} holds a module
     * descriptor: the test sources' package roots patch that module, and it reads the unnamed module.
     * The build and the forecast both derive the options from the same source list, so the request
     * they key on is one.
     */
    static List<String> testOptions(List<String> base, @Nullable Path mainClasses, List<Path> testSources) {
        String module = moduleName(mainClasses);
        if (module == null || testSources.isEmpty()) return base;
        List<String> out = new ArrayList<>(base);
        out.add("--patch-module");
        out.add(module + "=" + Classpaths.join(sourceRoots(testSources)));
        out.add("--add-reads");
        out.add(module + "=ALL-UNNAMED");
        return List.copyOf(out);
    }

    /**
     * Each source's package root, in first-seen order: its directory minus the trailing segments its
     * {@code package} declaration names. A source without a declaration, or one whose directory does
     * not spell its package, roots at its own directory.
     */
    static List<Path> sourceRoots(List<Path> sources) {
        Set<Path> roots = new LinkedHashSet<>();
        for (Path source : sources) {
            if (!source.toString().endsWith(".java")) continue;
            roots.add(packageRoot(source.toAbsolutePath().normalize()));
        }
        return List.copyOf(roots);
    }

    private static Path packageRoot(Path source) {
        Path dir = Objects.requireNonNull(source.getParent(), "an absolute source path has a parent");
        String declared;
        try {
            Matcher m = PACKAGE.matcher(Files.readString(source));
            declared = m.find() ? m.group(1) : "";
        } catch (IOException unreadable) {
            declared = "";
        }
        if (declared.isEmpty()) return dir;
        String[] segments = declared.split("\\.");
        Path root = dir;
        for (int i = segments.length - 1; i >= 0; i--) {
            if (root == null
                    || root.getFileName() == null
                    || !root.getFileName().toString().equals(segments[i])) {
                return dir;
            }
            root = root.getParent();
        }
        return root == null ? dir : root;
    }
}
