// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.runtime.base.PluginDescriptorOps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The code plugins a module runs, and their declarations merged into one plan view. Every
 * installed manifest with a {@code [code]} layer whose table the module declares is active; each
 * forks its own worker. Capabilities decide how many of a kind a module may hold: any number of
 * step contributors and generators, at most one packager that replaces the main artifact, plus
 * packagers that write beside it ({@code main-artifact = false}).
 */
public final class ActivePlugins {

    private ActivePlugins() {}

    /**
     * The module's active code plugins in registry order. Two plugins that both package the main
     * artifact are refused by name: the module would have two jars claiming to be its one output.
     */
    public static List<ActivePlugin> of(JkBuild project, Path moduleDir) {
        List<ActivePlugin> out = new ArrayList<>();
        ActivePlugin packager = null;
        for (PluginDescriptor m : PluginTableRegistry.manifestsFor(moduleDir, project.plugins())) {
            if (m.code() == null) continue;
            Optional<PluginConfig> config = project.pluginConfig(m.id());
            if (config.isEmpty()) continue;
            PluginDeclaration declaration = PluginDescriptorOps.declarationOf(moduleDir, project, m.id())
                    .orElse(null);
            ActivePlugin active = new ActivePlugin(m, config.get(), moduleDir, declaration);
            if (packagesMainArtifact(active)) {
                if (packager != null) {
                    throw new IllegalStateException(
                            "[" + m.table() + "] and [" + packager.manifest().table()
                                    + "] both package this module's main artifact: a module has one packager."
                                    + " Keep one of the two tables, or split the module");
                }
                packager = active;
            }
            out.add(active);
        }
        return List.copyOf(out);
    }

    /** The active plugin whose packager replaces the module's main artifact, or empty. */
    public static Optional<ActivePlugin> packager(JkBuild project, Path moduleDir) {
        return packager(of(project, moduleDir));
    }

    /** As {@link #packager(JkBuild, Path)} over an already selected set. */
    public static Optional<ActivePlugin> packager(List<ActivePlugin> plugins) {
        for (ActivePlugin a : plugins) {
            if (packagesMainArtifact(a)) return Optional.of(a);
        }
        return Optional.empty();
    }

    /** True when the plugin's {@code [packaging]} replaces the main artifact rather than writing beside it. */
    public static boolean packagesMainArtifact(ActivePlugin active) {
        var packaging = active.manifest().packaging();
        return packaging != null && packaging.resolve(active.config()).mainArtifact();
    }

    /**
     * Every active plugin's declarations as one plan: the steps of all of them in plugin order,
     * the main-artifact packager's {@code packager} and every command, each step and command
     * mapped to the plugin that forks it. A packager that writes beside the main artifact keeps
     * its own declarations ({@link PluginBuild#declarations}); its steps still ride here.
     */
    public record Declared(
            List<ActivePlugin> plugins,
            PluginDeclarations decls,
            Map<String, ActivePlugin> stepOwners,
            Map<String, ActivePlugin> commandOwners,
            @Nullable ActivePlugin packager) {

        /** The plugin that declared {@code step}. */
        public ActivePlugin ownerOf(TaskDecl step) {
            return Objects.requireNonNull(stepOwners.get(step.name()), () -> "owner of step " + step.name());
        }
    }

    /** A plugin's describe declarations; {@link PluginBuild#declarations} in the build. */
    interface DeclarationsSource {
        PluginDeclarations of(ActivePlugin active) throws IOException, InterruptedException;
    }

    /**
     * The module's active plugins with their describe declarations merged, or null when it has
     * none. Two plugins registering one step or command name is a refusal: the plan could fork
     * only one of them under that name.
     */
    public static @Nullable Declared declared(JkBuild project, Path moduleDir, Path cache, Path layoutTarget)
            throws IOException, InterruptedException {
        List<ActivePlugin> plugins = of(project, moduleDir);
        if (plugins.isEmpty()) return null;
        return merge(plugins, active -> PluginBuild.declarations(active, project, moduleDir, cache, layoutTarget));
    }

    /** {@link #declared} over a selected set and the source of each plugin's declarations. */
    static Declared merge(List<ActivePlugin> plugins, DeclarationsSource source)
            throws IOException, InterruptedException {
        List<TaskDecl> steps = new ArrayList<>();
        Map<String, ActivePlugin> stepOwners = new LinkedHashMap<>();
        Map<String, ActivePlugin> commandOwners = new LinkedHashMap<>();
        List<PluginDeclarations.CommandDecl> commands = new ArrayList<>();
        PluginDeclarations.PackagerDecl packager = null;
        ActivePlugin packagerPlugin = null;
        for (ActivePlugin active : plugins) {
            PluginDeclarations d;
            try {
                d = source.of(active);
            } catch (IOException e) {
                throw new IOException("plugin " + active.manifest().id() + ": " + e.getMessage(), e);
            }
            for (TaskDecl step : d.steps()) {
                ActivePlugin other = stepOwners.putIfAbsent(step.name(), active);
                if (other != null) throw sameName("step", step.name(), active, other);
                steps.add(step);
            }
            for (PluginDeclarations.CommandDecl command : d.commands()) {
                ActivePlugin other = commandOwners.putIfAbsent(command.name(), active);
                if (other != null) throw sameName("command", command.name(), active, other);
                commands.add(command);
            }
            if (d.packager() != null && packagesMainArtifact(active)) {
                packager = d.packager();
                packagerPlugin = active;
            }
        }
        return new Declared(
                plugins,
                new PluginDeclarations(steps, packager, commands),
                Map.copyOf(stepOwners),
                Map.copyOf(commandOwners),
                packagerPlugin);
    }

    private static IllegalStateException sameName(String kind, String name, ActivePlugin a, ActivePlugin b) {
        return new IllegalStateException(
                "[" + a.manifest().table() + "] and [" + b.manifest().table()
                        + "] both register a " + kind + " named `" + name + "`; a module's plugin " + kind
                        + " names are one namespace. Keep one of the two tables, or split the module");
    }
}
