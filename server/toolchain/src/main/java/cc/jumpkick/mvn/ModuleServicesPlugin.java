// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * Helidon's {@code helidon-services-plugin} is {@code [build] module-services = true}: its {@code
 * services} goal writes a {@code META-INF/services} file into the classes tree for every {@code
 * provides} in {@code module-info.java}, and so does jk's step. Helidon's root POM binds the goal for
 * every module, so the key lands only where it does something: a module whose plugin runs the goal
 * and whose main sources hold a {@code module-info.java}. A plugin managed in {@code
 * <pluginManagement>} but not run by the module, or a run bound to phase {@code none}, maps to
 * nothing.
 *
 * <p>The plugin's {@code fail} mode, the one Helidon builds with, is jk's only behaviour: a resource
 * that names other providers than the descriptor fails the step. Any other mode is a row.
 */
final class ModuleServicesPlugin {

    static final String ARTIFACT = "helidon-services-plugin";

    private static final String GOAL = "services";
    private static final String FAIL = "fail";

    private ModuleServicesPlugin() {}

    /** True when the module's effective build runs the {@code services} goal over a module descriptor. */
    static boolean map(EffectiveModel em, ImportReport.Builder report) {
        Optional<Plugin> plugin = PluginFacts.plugin(em.model(), ARTIFACT);
        if (plugin.isEmpty() || !runsGoal(plugin.get()) || !hasDescriptor(em.model())) return false;
        for (Xpp3Dom config : PluginFacts.configurations(plugin.get())) {
            String mode = PluginFacts.usable(PluginFacts.child(config, "mode"));
            if (mode != null && !FAIL.equals(mode)) {
                report.warning(ARTIFACT + " <mode> " + mode
                        + ": imported as fail; [build] module-services fails on a services file that disagrees");
            }
        }
        return true;
    }

    private static boolean runsGoal(Plugin plugin) {
        for (PluginExecution execution : plugin.getExecutions()) {
            if (!"none".equals(execution.getPhase()) && execution.getGoals().contains(GOAL)) return true;
        }
        return false;
    }

    /** The module's main source root holds a {@code module-info.java}. */
    private static boolean hasDescriptor(Model model) {
        Path sources = sourceRoot(model);
        return sources != null && Files.isRegularFile(sources.resolve("module-info.java"));
    }

    private static @Nullable Path sourceRoot(Model model) {
        String configured = model.getBuild() == null
                ? null
                : PluginFacts.usable(model.getBuild().getSourceDirectory());
        Path base = model.getProjectDirectory() == null
                ? null
                : model.getProjectDirectory().toPath();
        if (configured != null) {
            Path dir = Path.of(configured);
            return dir.isAbsolute() || base == null ? dir : base.resolve(dir);
        }
        return base == null ? null : base.resolve("src/main/java");
    }
}
