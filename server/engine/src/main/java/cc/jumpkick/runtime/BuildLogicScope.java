// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.runtime.base.BuildLogicAnchor;
import cc.jumpkick.runtime.base.CompileSupport;
import java.nio.file.Path;

/**
 * What a directory is to build logic, which decides the stems its {@code jk/} or {@code .jk/} may
 * carry.
 *
 * <p>The directory's shape decides, never the anchor that happens to be running. A standalone
 * project runs the module anchors and {@code guard} over the same directory, and a classification
 * read off the anchor accepted {@code after-resources} on the build's pass and refused it on the
 * guard's.
 */
public enum BuildLogicScope {
    /** Listed in an ancestor's {@code [workspace] modules}: the module stems only. */
    MEMBER("a module"),

    /**
     * Declares {@code [workspace]} and compiles nothing itself: {@code after-build} and {@code
     * guard} only, since there is no compile or package step for a module stem to cut against.
     */
    WORKSPACE_ROOT("a workspace root"),

    /**
     * The invocation root that is also the module: one {@code jk.toml} with no {@code [workspace]},
     * or a workspace root that carries its own sources. The module stems and {@code guard}, not
     * {@code after-build} — that anchor belongs to the sourceless root's plan, and this plan never
     * reaches it.
     */
    STANDALONE("a standalone project");

    private final String where;

    BuildLogicScope(String where) {
        this.where = where;
    }

    /** Whether a script at {@code anchor} may live beside this directory's {@code jk.toml}. */
    public boolean accepts(BuildLogicAnchor anchor) {
        return switch (this) {
            case MEMBER -> !anchor.workspaceScoped();
            case WORKSPACE_ROOT -> anchor.workspaceScoped();
            case STANDALONE -> anchor != BuildLogicAnchor.AFTER_BUILD;
        };
    }

    /** The scope as a refusal names it: "not a valid stem for {@code where}". */
    String where() {
        return where;
    }

    /** The stems to use instead, for the refusal a misplaced script earns. */
    String use() {
        return switch (this) {
            case MEMBER ->
                "before-compile / after-compile / after-resources / before-package —"
                        + " after-build and gate are the invocation root's anchors";
            case WORKSPACE_ROOT ->
                "after-build or gate — the root has no compile or package step for the others to cut against";
            case STANDALONE ->
                "guard for a once-per-build check — after-build belongs to a workspace root that"
                        + " compiles nothing itself, and this project compiles its own sources";
        };
    }

    /**
     * Classify {@code projectDir}: a member by the ancestor whose {@code [workspace] modules} lists
     * it, a workspace root by its own {@code [workspace]} table and the absence of sources, and
     * anything else standalone. Reads the manifest through the memoized scan, so a build asking
     * once per anchor pays one read.
     */
    public static BuildLogicScope of(Path projectDir) {
        Path dir = projectDir.toAbsolutePath().normalize();
        if (WorkspaceScan.findRoot(dir).isPresent()) return MEMBER;
        boolean declaresWorkspace = TomlScan.scan(ManifestPaths.manifestIn(dir), "workspace.modules")
                .hasSection("workspace");
        if (declaresWorkspace && !CompileSupport.hasSources(dir)) return WORKSPACE_ROOT;
        return STANDALONE;
    }
}
