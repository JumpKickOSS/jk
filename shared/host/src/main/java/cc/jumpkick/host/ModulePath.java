// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.RequiresTree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;

/**
 * The entries of a modular compile's classpath that belong on {@code --module-path}: those the
 * module's {@code requires} reach, directly or through the modules they require, the way Maven's
 * compiler plugin divides them. Everything else stays on the classpath only.
 *
 * <p>The whole classpath on the module path is not the same thing. Resolving one automatic module
 * resolves every automatic module the path holds, so jars the module never names are read all the
 * same, and two of them splitting a package ({@code jsr305} and {@code java.annotation} both carry
 * {@code javax.annotation}; Maven's plexus jars share {@code org.codehaus.plexus}) fail the compile.
 */
public final class ModulePath {

    /** javac's spelling of a module descriptor source. */
    static final String MODULE_INFO = "module-info.java";

    private ModulePath() {}

    /**
     * The module path for a compile whose root module requires {@code rootRequires}, in classpath
     * order; {@code root} itself, when it is on the classpath (a test compile patching the main
     * module), comes first.
     */
    public static List<Path> of(@Nullable String root, Set<String> rootRequires, List<Path> classpath) {
        Map<String, Path> byName = new LinkedHashMap<>();
        Map<String, ModuleDescriptor> descriptors = new LinkedHashMap<>();
        for (Path entry : classpath) {
            Optional<ModuleReference> ref = describe(entry);
            if (ref.isEmpty()) continue;
            String name = ref.get().descriptor().name();
            if (byName.putIfAbsent(name, entry) == null)
                descriptors.put(name, ref.get().descriptor());
        }
        Set<String> reached = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(rootRequires);
        if (root != null && byName.containsKey(root)) pending.addFirst(root);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!byName.containsKey(name) || !reached.add(name)) continue;
            ModuleDescriptor d = descriptors.get(name);
            if (d == null || d.isAutomatic()) continue;
            for (ModuleDescriptor.Requires r : d.requires()) {
                // A static requires is compile-time only for the module that declares it: the root
                // sees its own, a dependency's do not reach this compile.
                boolean isRoot = name.equals(root);
                if (isRoot || !r.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC)) {
                    pending.addLast(r.name());
                }
            }
        }
        List<Path> out = new ArrayList<>();
        for (Path entry : classpath) {
            for (Map.Entry<String, Path> e : byName.entrySet()) {
                if (e.getValue().equals(entry) && reached.contains(e.getKey())) {
                    out.add(entry);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * The {@code --module-path} entries for a modular compile of {@code sources} with {@code extra}
     * options: what the descriptor being compiled requires, or, for a compile that patches a module
     * ({@code --patch-module <module>=...}, a test compile), what that module's compiled descriptor
     * on the classpath requires, plus the module itself. The whole classpath when neither can be
     * read, which is what every modular compile used before.
     */
    public static List<Path> forCompile(List<Path> sources, List<String> extra, List<Path> classpath) {
        for (Path source : sources) {
            Path name = source.getFileName();
            if (name == null || !MODULE_INFO.equals(name.toString())) continue;
            Set<String> requires = requiresOf(source);
            return requires == null ? classpath : of(null, requires, classpath);
        }
        String patched = patchedModule(extra);
        return patched == null ? classpath : of(patched, Set.of(), classpath);
    }

    private static @Nullable String patchedModule(List<String> extra) {
        for (int i = 0; i + 1 < extra.size(); i++) {
            if (!"--patch-module".equals(extra.get(i))) continue;
            String value = extra.get(i + 1);
            int eq = value.indexOf('=');
            if (eq > 0) return value.substring(0, eq);
        }
        return null;
    }

    /** The module an entry forms on the module path: explicit, automatic, or none (a plain directory). */
    private static Optional<ModuleReference> describe(Path entry) {
        if (!Files.exists(entry)) return Optional.empty();
        try {
            Set<ModuleReference> found = ModuleFinder.of(entry).findAll();
            return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
        } catch (FindException | SecurityException unreadable) {
            // A jar the module system refuses (a bad Automatic-Module-Name, a broken descriptor)
            // cannot be a module this compile requires; it stays on the classpath.
            return Optional.empty();
        }
    }

    /** The module names {@code moduleInfo} requires, static or not, or null when it cannot be parsed. */
    static @Nullable Set<String> requiresOf(Path moduleInfo) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) return null;
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) javac.getTask(
                    new StringWriter(), fm, d -> {}, List.of("-proc:none"), null, fm.getJavaFileObjects(moduleInfo));
            Set<String> names = new LinkedHashSet<>();
            for (CompilationUnitTree unit : task.parse()) {
                ModuleTree module = unit.getModule();
                if (module == null) return null;
                for (var directive : module.getDirectives()) {
                    if (directive instanceof RequiresTree requires) {
                        names.add(requires.getModuleName().toString());
                    }
                }
            }
            return names;
        } catch (IOException | RuntimeException unparsable) {
            return null;
        }
    }
}
