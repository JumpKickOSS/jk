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
    public static final FrontendCollector OFF = new FrontendCollector(false, false);

    private final boolean enabled;
    private final boolean inPlace;
    private final List<FrontendImport.Relocation> relocations = new ArrayList<>();
    private FrontendFiles files = FrontendFiles.NONE;

    public FrontendCollector() {
        this(true, false);
    }

    private FrontendCollector(boolean enabled, boolean inPlace) {
        this.enabled = enabled;
        this.inPlace = inPlace;
    }

    /**
     * Places every frontend without touching the tree, for the in-place build of a {@code pom.xml}:
     * the build runs where the POM runs it and writes where its bundler writes, and the module that
     * takes the output builds it. Nothing moves and nothing is rewritten.
     */
    public static FrontendCollector inPlace() {
        return new FrontendCollector(true, true);
    }

    /** What the import moves and rewrites; nothing until a workspace or module is imported. */
    public synchronized FrontendFiles files() {
        return files;
    }

    /** {@code build} with its module's side-by-side frontend; a frontend that needs a module of its own waits for {@link #place}. */
    synchronized JkBuild member(Model model, JkBuild build, ImportReport.Builder report, boolean standalone) {
        if (!enabled) return build;
        FrontendImport.Applied applied = FrontendImport.member(model, build, report, standalone, inPlace);
        if (applied.relocation() != null) relocations.add(applied.relocation());
        files = files.plus(applied.files());
        return applied.build();
    }

    /** The workspace with the root's frontend and every waiting one as generated node modules. */
    synchronized FrontendImport.Relocated place(
            Model rootModel, Path rootDir, JkBuild root, Map<String, JkBuild> members, ImportReport.Builder report) {
        if (!enabled) return new FrontendImport.Relocated(root, members, FrontendFiles.NONE);
        FrontendImport.Applied rootFrontend = FrontendImport.member(rootModel, root, report, false, inPlace);
        if (rootFrontend.relocation() != null) relocations.add(rootFrontend.relocation());
        FrontendImport.Relocated placed = inPlace
                ? FrontendImport.consumeInPlace(rootDir, root, members, relocations, report)
                : FrontendImport.relocate(rootDir, root, members, relocations, report);
        files = files.plus(placed.files());
        return placed;
    }
}
