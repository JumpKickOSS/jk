// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleDotGraph;
import cc.jumpkick.config.ModuleSelection;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.engine.protocol.ModuleGraphAck;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Module DAG for {@code jk explain --graph}. */
public final class ModuleGraphOps {

    private ModuleGraphOps() {}

    public static ModuleGraphAck render(Path startDir, String format, String modulesSpec, String affectedSince) {
        String fmt = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (!ModuleDotGraph.isSupportedFormat(fmt)) {
            return ModuleGraphAck.error("unsupported --graph format '" + format + "' (supported: "
                    + String.join(" | ", ModuleDotGraph.FORMATS) + ")");
        }
        Path root = startDir.toAbsolutePath().normalize();
        Path buildFile = root.resolve(ManifestPaths.MANIFEST);
        try {
            JkBuild entry = JkBuildParser.parse(buildFile);
            String graph;
            if (entry.isWorkspaceRoot()) {
                Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(root, entry);
                ModuleSelection.Result selected =
                        ModuleSelection.resolveOptional(startDir, entry, modulesSpec, affectedSince);
                if (selected != null && !selected.ok()) {
                    return ModuleGraphAck.error(selected.errorMessage());
                }
                Set<Path> only = selected != null ? selected.moduleDirs() : null;
                if (only != null && only.isEmpty()) {
                    graph = ModuleDotGraph.render(fmt, root, Map.of(), null);
                } else {
                    Map<Path, JkBuild> forGraph = new LinkedHashMap<>(modules);
                    graph = ModuleDotGraph.render(fmt, root, forGraph, only);
                }
            } else {
                boolean emptyMatch = false;
                if ((modulesSpec != null && !modulesSpec.isBlank())
                        || (affectedSince != null && !affectedSince.isBlank())) {
                    ModuleSelection.Result selected =
                            ModuleSelection.resolveOptional(startDir, entry, modulesSpec, affectedSince);
                    if (selected != null && !selected.ok()) {
                        return ModuleGraphAck.error(selected.errorMessage());
                    }
                    // A selector that validates but matches nothing must render the empty
                    // graph, not silently the full single-module one (JK-2167).
                    emptyMatch = selected != null && selected.moduleDirs().isEmpty();
                }
                graph = emptyMatch
                        ? ModuleDotGraph.render(fmt, root, Map.of(), null)
                        : ModuleDotGraph.singleModule(entry, root, fmt);
            }
            return ModuleGraphAck.of(graph);
        } catch (Exception e) {
            return ModuleGraphAck.error(Errors.text(e));
        }
    }
}
