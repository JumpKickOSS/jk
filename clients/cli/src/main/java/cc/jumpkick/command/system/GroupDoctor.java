// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.WorkspaceGroup;
import cc.jumpkick.host.OutputDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk doctor} at a group root: a {@code [workspaces] members} entry with no project in its
 * directory (a build at the root refuses to start), and a project below the root that no entry
 * lists (the root's verbs never run it).
 */
final class GroupDoctor {

    /** Directories the walk for unlisted projects never enters. */
    private static final Set<String> SKIPPED = Set.of(OutputDirs.TARGET, "node_modules", "build", "out");

    private GroupDoctor() {}

    /**
     * What the group root at {@code dir} is missing and leaves out.
     *
     * @param missing listed entries with no project
     * @param unlisted projects below the root no entry lists, relative to it
     * @param error why the root could not be read, or {@code null}
     */
    record Report(
            List<String> missing,
            List<String> unlisted,
            @Nullable String error) {}

    /** The report for {@code dir}, or {@code null} when it is no group root. */
    static @Nullable Report of(Path dir) {
        if (!WorkspaceGroup.declares(dir)) return null;
        WorkspaceGroup group;
        try {
            group = WorkspaceGroup.load(dir);
        } catch (JkBuildParseException | IOException e) {
            return new Report(List.of(), List.of(), e.getMessage());
        }
        int depth = 3;
        for (WorkspaceGroup.Member m : group.members())
            depth = Math.max(depth, m.path().split("/").length);
        for (String m : group.missing()) depth = Math.max(depth, m.split("/").length);
        List<Path> listed =
                group.members().stream().map(WorkspaceGroup.Member::dir).toList();
        List<String> unlisted = new ArrayList<>();
        walk(group.root(), group.root(), listed, depth, unlisted);
        return new Report(group.missing(), List.copyOf(unlisted), null);
    }

    private static void walk(Path root, Path dir, List<Path> listed, int depth, List<String> unlisted) {
        if (depth == 0) return;
        List<Path> children = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (child, attrs) -> {
                if (attrs.isDirectory()) children.add(child);
                return true;
            });
        } catch (IOException unreadable) {
            return;
        }
        children.sort(null);
        for (Path child : children) {
            Path leaf = child.getFileName();
            String name = leaf == null ? "" : leaf.toString();
            if (name.startsWith(".") || SKIPPED.contains(name)) continue;
            Path normalized = child.toAbsolutePath().normalize();
            if (listed.contains(normalized)) continue; // a member's own modules are its business
            if (ManifestPaths.describesProject(child)) {
                unlisted.add(root.relativize(normalized).toString().replace('\\', '/'));
                continue;
            }
            walk(root, child, listed, depth - 1, unlisted);
        }
    }

    /** The doctor row for {@code report}. */
    static DoctorCommand.Check check(Report report) {
        if (report.error() != null) return new DoctorCommand.Check(DoctorCommand.Status.FAIL, "group", report.error());
        List<String> parts = new ArrayList<>();
        if (!report.missing().isEmpty()) {
            parts.add("listed with no project: " + String.join(", ", report.missing()));
        }
        if (!report.unlisted().isEmpty()) {
            parts.add("not listed in [workspaces] members: " + String.join(", ", report.unlisted()));
        }
        if (parts.isEmpty()) return new DoctorCommand.Check(DoctorCommand.Status.OK, "group", "every member is there");
        DoctorCommand.Status status =
                report.missing().isEmpty() ? DoctorCommand.Status.WARN : DoctorCommand.Status.FAIL;
        return new DoctorCommand.Check(status, "group", String.join("; ", parts));
    }
}
