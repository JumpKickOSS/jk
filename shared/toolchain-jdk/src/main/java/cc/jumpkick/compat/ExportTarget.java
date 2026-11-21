// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.Map;
import java.util.Objects;

/**
 * The build tool {@code jk export} writes for, and the rules both exports share: what a dependency
 * with no coordinate becomes, which version an edge is written with, and which {@code jk.toml}
 * concerns are dropped. Only the spellings differ between the two.
 */
public enum ExportTarget {
    MAVEN(
            "Maven",
            "jk install it, then add a <dependency>",
            "jk install it, then add a <dependency>",
            "",
            "LATEST, which Maven 3 deprecates",
            "LATEST, which Maven 3 deprecates",
            "LATEST",
            "LATEST"),
    GRADLE(
            "Gradle",
            "includeBuild a local checkout",
            "includeBuild that directory",
            "; consider Gradle feature variants",
            "latest.release",
            "latest.integration",
            "latest.release",
            // Gradle's own name for the same intent: newest, pre-releases included.
            "latest.integration");

    private final String tool;
    private final String gitFix;
    private final String pathFix;
    private final String featuresNote;
    private final String latestSaid;
    private final String snapshotSaid;
    private final String latest;
    private final String snapshot;

    ExportTarget(
            String tool,
            String gitFix,
            String pathFix,
            String featuresNote,
            String latestSaid,
            String snapshotSaid,
            String latest,
            String snapshot) {
        this.tool = tool;
        this.gitFix = gitFix;
        this.pathFix = pathFix;
        this.featuresNote = featuresNote;
        this.latestSaid = latestSaid;
        this.snapshotSaid = snapshotSaid;
        this.latest = latest;
        this.snapshot = snapshot;
    }

    /**
     * True, with a warning, when {@code d} has no coordinate the tool can name: a workspace edge
     * that names no member or shared dependency, or a git, path or content-addressed file source.
     * The export leaves it out.
     */
    public boolean dropped(Dependency d, ImportReport.Builder report) {
        if (d.isWorkspace()) {
            report.warning(d.workspaceName() + ": no workspace member or shared dependency; dropped");
            return true;
        }
        if (d.isGit()) {
            report.warning(d.module() + ": git source dropped; " + gitFix);
            return true;
        }
        if (d.isPath()) {
            report.warning(Objects.requireNonNull(d.pathSource()).rawPath() + ": path dependency dropped; " + pathFix);
            return true;
        }
        if (d.isFile()) {
            report.warning(d.module() + ": sha256 file dependency dropped");
            return true;
        }
        return false;
    }

    /**
     * The version {@code d} is written with: its lock row (keyed by package id, {@code
     * g:a:type:classifier}, then by bare {@code group:artifact}), else its selector collapsed to one
     * version, with a warning when that loses meaning.
     */
    public String version(Dependency d, Map<String, String> locked, ImportReport.Builder report) {
        String fromLock = locked.get(d.packageKey());
        if (fromLock == null || fromLock.isBlank()) fromLock = locked.get(d.module());
        if (fromLock != null && !fromLock.isBlank()) return fromLock;
        return version(d.version(), d.module(), report);
    }

    /** {@code v} as one version the tool reads, warning when that loses what {@code v} meant. */
    public String version(VersionSelector v, String module, ImportReport.Builder report) {
        return switch (v) {
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Caret c -> {
                report.warning(module + ": ^" + c.version() + " pinned to " + c.version());
                yield c.version();
            }
            case VersionSelector.Tilde t -> {
                report.warning(module + ": ~" + t.version() + " pinned to " + t.version());
                yield t.version();
            }
            case VersionSelector.Range r -> {
                // jk range expressions usually read as the tool's own ranges; the user reviews.
                report.warning(module + ": range " + r.raw() + " written as is; check " + tool + "'s syntax");
                yield r.raw();
            }
            case VersionSelector.Latest l -> {
                report.warning(module + ": latest written as " + latestSaid);
                yield latest;
            }
            case VersionSelector.Snapshot s -> {
                report.warning(module + ": snapshot written as " + snapshotSaid);
                yield snapshot;
            }
        };
    }

    /**
     * Warn about the {@code jk.toml} concerns the tool has no place for: features, profiles, the
     * dev-only dependency scopes, and a {@code [war]} name or webapp directory of its own.
     */
    public void warnDropped(JkBuild jk, ImportReport.Builder report) {
        if (!jk.features().isEmpty()) {
            report.warning("[features] dropped" + featuresNote);
        }
        if (jk.profiles() != null && !jk.profiles().byName().isEmpty()) {
            report.warning("[profiles] dropped");
        }
        for (Scope s : new Scope[] {Scope.DEV, Scope.TEST_DEV}) {
            if (!jk.dependencies().of(s).isEmpty()) report.warning("[" + s.tomlSection() + "] dropped");
        }
        BuildBlock.War war = jk.build().war();
        if (war != null && (war.name() != null || !BuildBlock.War.DEFAULT_WEBAPP.equals(war.webapp()))) {
            report.warning("[war] name and webapp dropped; the war plugin reads " + BuildBlock.War.DEFAULT_WEBAPP);
        }
    }
}
