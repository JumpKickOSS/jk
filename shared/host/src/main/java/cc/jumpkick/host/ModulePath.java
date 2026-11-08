// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.RequiresTree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.module.ModuleDescriptor;
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
            Optional<ModuleDescriptor> d = ModuleDescriptors.of(entry);
            if (d.isEmpty()) continue;
            String name = d.get().name();
            if (byName.putIfAbsent(name, entry) == null) descriptors.put(name, d.get());
        }
        Set<String> reached = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(rootRequires);
        if (root != null && byName.containsKey(root)) pending.addFirst(root);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!byName.containsKey(name) || !reached.add(name)) continue;
            ModuleDescriptor d = descriptors.get(name);
            if (d == null || d.isAutomatic()) continue;
            // A static requires is followed too: javac resolves a dependency's static requires
            // when they are observable (JUnit's requires static transitive org.apiguardian.api
            // fails "module not found" without it), and one the classpath lacks is simply absent.
            for (ModuleDescriptor.Requires r : d.requires()) pending.addLast(r.name());
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

    /** The module names {@code moduleInfo} requires, static or not, or null when it cannot be parsed. */
    static @Nullable Set<String> requiresOf(Path moduleInfo) {
        Declared d = declared(moduleInfo);
        return d == null ? null : d.requires();
    }

    /** The module a {@code module-info.java} declares and what it requires, static or not. */
    public record Declared(String name, Set<String> requires) {}

    /** What {@code moduleInfo} declares, or null when it cannot be parsed. */
    public static @Nullable Declared declared(Path moduleInfo) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) return null;
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) javac.getTask(
                    new StringWriter(), fm, d -> {}, List.of("-proc:none"), null, fm.getJavaFileObjects(moduleInfo));
            for (CompilationUnitTree unit : task.parse()) {
                ModuleTree module = unit.getModule();
                if (module == null) return null;
                Set<String> names = new LinkedHashSet<>();
                for (var directive : module.getDirectives()) {
                    if (directive instanceof RequiresTree requires) {
                        names.add(requires.getModuleName().toString());
                    }
                }
                return new Declared(module.getName().toString(), Set.copyOf(names));
            }
            return null;
        } catch (IOException | RuntimeException unparsable) {
            return null;
        }
    }
}
