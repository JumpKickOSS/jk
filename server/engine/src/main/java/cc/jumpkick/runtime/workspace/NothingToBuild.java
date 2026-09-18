// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.base.CompileSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The one verdict a workspace earns before any module runs: there is nothing in it to build. A
 * workspace with no modules, or whose every module has no source tree, would otherwise finish
 * green having compiled nothing, and a green build that built nothing reads as a passing build to
 * a person and to an agent alike. A plain project (no {@code [workspace]} block) builds what it
 * has and is never judged here.
 */
@NullMarked
final class NothingToBuild {

    private static final int NAMED = 5;

    private NothingToBuild() {}

    /** The one-line reason, or {@code null} when at least one unit can produce something. */
    static @Nullable String verdict(List<BuildGraph.BuildUnit> units, JkBuild entry) {
        if (entry.workspace() == null) return null;
        boolean declared = entry.isWorkspaceRoot();
        List<String> sourceless = new ArrayList<>();
        for (BuildGraph.BuildUnit unit : units) {
            if (productive(unit, declared)) return null;
            sourceless.add(unit.coord());
        }
        if (!declared) return "built nothing: the workspace declares no modules";
        int n = sourceless.size();
        String named = String.join(", ", sourceless.subList(0, Math.min(n, NAMED))) + (n > NAMED ? ", …" : "");
        return "built nothing: none of the " + n + " module" + (n == 1 ? "" : "s") + " has sources (" + named + ")";
    }

    /**
     * The root of a declared workspace enters the graph only when it has sources or build logic,
     * so its presence is the proof. Any other unit produces something when it has a source tree, an
     * extra source root or build logic.
     */
    private static boolean productive(BuildGraph.BuildUnit unit, boolean declaredWorkspace) {
        if (declaredWorkspace && unit.origin() == BuildGraph.Origin.ROOT) return true;
        Path dir = unit.dir();
        if (CompileSupport.hasSources(dir)
                || Files.isDirectory(dir.resolve("src"))
                || Files.isDirectory(dir.resolve("test"))) {
            return true;
        }
        BuildBlock build = unit.manifest().build();
        if (!build.extraSrc().isEmpty() || !build.testExtraSrc().isEmpty()) return true;
        return BuildLogicToml.resolve(dir).isPresent();
    }
}
