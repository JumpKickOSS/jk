// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One project summary for the snapshot surfaces ({@code GET /api/project}, MCP
 * {@code jk_project}/{@code jk_bind}): durable identity plus best-effort manifest facts. Never
 * throws — an unparseable or missing {@code jk.toml} yields nulls, and identity still resolves
 * (lock / identity file / hash) so a broken checkout keeps its durable id. Each surface encodes
 * the subset it serves; the wire {@code project-info} IDE contract stays its own richer type.
 */
public record ProjectCard(
        String dir,
        @Nullable String projectId,
        @Nullable String coord,
        @Nullable String description,
        @Nullable String version,
        int javaRelease,
        @Nullable String jdk,
        List<Member> members,
        boolean lockStale) {

    /** A workspace member: dir plus its coord when the member manifest parses. */
    public record Member(String dir, @Nullable String coord) {}

    public static ProjectCard of(Path root) {
        String projectId = null;
        try {
            String id = ProjectIdentity.resolve(root).id();
            if (id != null && !id.isBlank()) projectId = id;
        } catch (RuntimeException ignored) {
            // invalid path — card still carries the dir
        }
        String coord = null;
        String description = null;
        String version = null;
        int javaRelease = 0;
        String jdk = null;
        List<Member> members = List.of();
        try {
            JkBuild build = JkBuildParser.parse(root.resolve(ManifestPaths.MANIFEST));
            var p = build.project();
            coord = p.group() + ":" + p.name();
            description = p.description();
            version = p.version();
            javaRelease = p.javaRelease();
            jdk = p.jdk();
            members = membersOf(root, build);
        } catch (Exception ignored) {
            // missing/unparseable jk.toml — identity-only card
        }
        boolean lockStale;
        try {
            lockStale = LockFreshness.needsRefresh(root);
        } catch (RuntimeException e) {
            lockStale = true;
        }
        return new ProjectCard(
                root.toString(), projectId, coord, description, version, javaRelease, jdk, members, lockStale);
    }

    private static List<Member> membersOf(Path root, JkBuild build) {
        if (!build.isWorkspaceRoot() || build.workspace() == null) return List.of();
        List<Member> out = new ArrayList<>();
        for (String rel : build.workspace().modules()) {
            Path moduleDir = root.resolve(rel).normalize();
            String coord = null;
            try {
                var p = JkBuildParser.parseLocal(moduleDir.resolve(ManifestPaths.MANIFEST))
                        .project();
                coord = p.group() + ":" + p.name();
            } catch (Exception ignored) {
                // path-only member
            }
            out.add(new Member(moduleDir.toString(), coord));
        }
        return List.copyOf(out);
    }
}
