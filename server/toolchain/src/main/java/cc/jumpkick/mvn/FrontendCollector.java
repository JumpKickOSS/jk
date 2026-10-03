// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.Model;

/**
 * The frontend-maven-plugin builds one import places: each module's side-by-side build as the
 * module is imported, the rest as generated node modules once the workspace is whole, and the files
 * that moves and rewrites. {@link #OFF} places nothing, for every read of a POM but {@code jk import}.
 */
public final class FrontendCollector {

    /** Places nothing: a frontend stays a row-free part of its module's POM. */
    public static final FrontendCollector OFF = new FrontendCollector(false);

    private final boolean enabled;
    private final List<FrontendImport.Relocation> relocations = new ArrayList<>();
    private FrontendFiles files = FrontendFiles.NONE;

    public FrontendCollector() {
        this(true);
    }

    private FrontendCollector(boolean enabled) {
        this.enabled = enabled;
    }

    /** What the import moves and rewrites; nothing until a workspace or module is imported. */
    public synchronized FrontendFiles files() {
        return files;
    }

    /** {@code build} with its module's side-by-side frontend; a frontend that needs a module of its own waits for {@link #place}. */
    synchronized JkBuild member(Model model, JkBuild build, ImportReport.Builder report, boolean standalone) {
        if (!enabled) return build;
        FrontendImport.Applied applied = FrontendImport.member(model, build, report, standalone);
        if (applied.relocation() != null) relocations.add(applied.relocation());
        files = files.plus(applied.files());
        return applied.build();
    }

    /** The workspace with the root's frontend and every waiting one as generated node modules. */
    synchronized FrontendImport.Relocated place(
            Model rootModel, Path rootDir, JkBuild root, Map<String, JkBuild> members, ImportReport.Builder report) {
        if (!enabled) return new FrontendImport.Relocated(root, members, FrontendFiles.NONE);
        FrontendImport.Applied rootFrontend = FrontendImport.member(rootModel, root, report, false);
        if (rootFrontend.relocation() != null) relocations.add(rootFrontend.relocation());
        FrontendImport.Relocated placed = FrontendImport.relocate(rootDir, root, members, relocations, report);
        files = files.plus(placed.files());
        return placed;
    }
}
