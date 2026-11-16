// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * The order between a group root's members: member A depends on member B when a manifest of A (its
 * own, or one of its modules') declares a {@code path} dependency into B's directory. Read from the
 * manifests' dependency tables as written, with no parse into a project, so a member's own errors
 * stay its own build's to report.
 */
final class PathEdges {

    private PathEdges() {}

    /** Member dir → the member dirs its {@code path} dependencies name, itself never among them. */
    static Map<Path, Set<Path>> between(List<WorkspaceGroup.Member> members) {
        Map<Path, Set<Path>> edges = new LinkedHashMap<>();
        for (WorkspaceGroup.Member m : members) {
            Set<Path> prereqs = new LinkedHashSet<>();
            for (Path manifest : manifests(m.dir())) {
                Path declaring = Objects.requireNonNull(manifest.getParent(), "a manifest's directory");
                for (String raw : pathDependencies(manifest)) {
                    Path target = declaring.resolve(raw).normalize();
                    for (WorkspaceGroup.Member other : members) {
                        if (!other.dir().equals(m.dir()) && target.startsWith(other.dir())) prereqs.add(other.dir());
                    }
                }
            }
            edges.put(m.dir(), prereqs);
        }
        return edges;
    }

    /** The member's manifest and, for a workspace, each module's. */
    private static List<Path> manifests(Path memberDir) {
        List<Path> out = new ArrayList<>();
        Path root = ManifestPaths.manifestIn(memberDir);
        if (!Files.isRegularFile(root)) return out;
        out.add(root);
        try {
            for (Path module : WorkspaceModules.memberDirs(memberDir)) {
                Path manifest = ManifestPaths.manifestIn(module);
                if (Files.isRegularFile(manifest)) out.add(manifest);
            }
        } catch (JkBuildParseException unexpandable) {
            // The member's own build reports a module glob that matches nothing.
        }
        return out;
    }

    /** The {@code path} of every dependency entry in {@code manifest}'s dependency tables. */
    private static List<String> pathDependencies(Path manifest) {
        TomlParseResult doc;
        try {
            doc = JkBuildParser.document(manifest);
        } catch (IOException | RuntimeException unreadable) {
            return List.of(); // the member's own build reports it
        }
        List<String> out = new ArrayList<>();
        for (String key : doc.keySet()) {
            if (key.endsWith("dependencies") && doc.get(List.of(key)) instanceof TomlTable scope) collect(scope, out);
        }
        return out;
    }

    private static void collect(TomlTable scope, List<String> out) {
        for (String library : scope.keySet()) {
            // As a one-element key path: a library key may itself contain a dot.
            if (scope.get(List.of(library)) instanceof TomlTable entry && entry.isString("path")) {
                out.add(entry.getString("path"));
            }
        }
    }
}
