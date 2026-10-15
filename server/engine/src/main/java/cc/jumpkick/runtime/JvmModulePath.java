// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The {@code --module-path} / {@code --add-modules} options a plugin's {@code [contribute.jvm-modules]}
 * asks of the test JVM and {@code jk run}. A runtime-classpath jar joins the module path when its
 * {@code module-info} names a module under the contributed prefix; automatic modules never do.
 * The jars stay on the classpath too, where the application loader defers their packages to the
 * named modules.
 */
public final class JvmModulePath {

    private JvmModulePath() {}

    /** The JVM options for {@code build}'s runtime {@code classpath}; empty when no plugin contributes. */
    public static List<String> args(JkBuild build, @Nullable Path moduleDir, List<Path> classpath) {
        return args(PluginContributions.jvmModules(build, moduleDir), classpath);
    }

    static List<String> args(List<PluginContributions.JvmModuleUse> uses, List<Path> classpath) {
        if (uses.isEmpty()) return List.of();
        Set<String> modules = new LinkedHashSet<>();
        Set<Path> modulePath = new LinkedHashSet<>();
        for (PluginContributions.JvmModuleUse use : uses) {
            modules.addAll(use.addModules());
            for (Path jar : classpath) {
                String name = explicitModule(jar);
                if (name != null && name.startsWith(use.modulePrefix())) modulePath.add(jar);
            }
        }
        if (modulePath.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        out.add("--module-path");
        out.add(Classpaths.join(modulePath));
        if (!modules.isEmpty()) {
            out.add("--add-modules");
            out.add(String.join(",", modules));
        }
        return out;
    }

    /** The module a jar declares in its {@code module-info}, or null for a plain or automatic one. */
    static @Nullable String explicitModule(Path jar) {
        if (!Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar")) return null;
        try {
            Set<ModuleReference> refs = ModuleFinder.of(jar).findAll();
            if (refs.size() != 1) return null;
            ModuleDescriptor d = refs.iterator().next().descriptor();
            return d.isAutomatic() ? null : d.name();
        } catch (FindException e) {
            return null;
        }
    }
}
