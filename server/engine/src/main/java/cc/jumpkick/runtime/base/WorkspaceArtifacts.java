// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Linking;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Computes and materializes workspace-root links for module artifacts. */
public final class WorkspaceArtifacts {

    private WorkspaceArtifacts() {}

    /**
     * Compute source-to-workspace-target links. Colliding filenames receive the module group as a
     * prefix.
     */
    public static Map<Path, Path> computeLinks(Iterable<Path> moduleDirs, Path workspaceRoot) {
        Path wsRoot = workspaceRoot.toAbsolutePath().normalize();
        Map<Path, List<Path>> moduleArtifacts = new LinkedHashMap<>();
        Map<Path, String> moduleGroup = new LinkedHashMap<>();
        for (Path moduleDir : moduleDirs) {
            Path normalDir = moduleDir.toAbsolutePath().normalize();
            if (normalDir.equals(wsRoot)) continue;
            Path buildFile = moduleDir.resolve(ManifestPaths.MANIFEST);
            if (!Files.exists(buildFile)) continue;
            JkBuild build;
            try {
                build = JkBuildParser.parse(buildFile);
            } catch (Exception ignored) {
                continue;
            }
            BuildLayout layout = BuildLayout.of(wsRoot, moduleDir, build);
            if (!layout.packagedAtRoot()) continue;
            List<Path> candidates = new ArrayList<>();
            candidates.add(layout.mainJar());
            candidates.add(layout.assemblyJar());
            candidates.add(layout.nativeBinary());
            candidates.add(layout.nativeLibrary());
            candidates.add(layout.ociImageTar());
            candidates.add(sidecarPom(layout.mainJar()));
            candidates.add(sidecarPom(layout.assemblyJar()));
            moduleArtifacts.put(normalDir, candidates);
            moduleGroup.put(normalDir, build.project().group());
        }

        Map<String, Long> filenameCounts = new HashMap<>();
        for (List<Path> artifacts : moduleArtifacts.values()) {
            for (Path artifact : artifacts) {
                filenameCounts.merge(artifact.getFileName().toString(), 1L, Long::sum);
            }
        }

        Path wsTarget = wsRoot.resolve(BuildLayout.TARGET);
        Map<Path, Path> links = new LinkedHashMap<>();
        for (var entry : moduleArtifacts.entrySet()) {
            String group = moduleGroup.get(entry.getKey());
            for (Path artifact : entry.getValue()) {
                String filename = artifact.getFileName().toString();
                String linkName = filenameCounts.getOrDefault(filename, 0L) > 1 ? group + "-" + filename : filename;
                links.put(artifact, wsTarget.resolve(linkName));
            }
        }
        return links;
    }

    /** Apply links whose sources belong to {@code moduleDir}; failures are best-effort. */
    public static void linkModule(Path moduleDir, Map<Path, Path> workspaceLinks) {
        if (workspaceLinks.isEmpty()) return;
        Path normalDir = moduleDir.toAbsolutePath().normalize();
        for (var entry : workspaceLinks.entrySet()) {
            Path source = entry.getKey();
            if (!source.startsWith(normalDir) || !Files.isRegularFile(source)) continue;
            try {
                Linking.linkOrCopy(source, entry.getValue());
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    private static Path sidecarPom(Path jar) {
        String name = jar.getFileName().toString();
        return jar.resolveSibling(
                name.endsWith(".jar") ? name.substring(0, name.length() - 4) + ".pom" : name + ".pom");
    }
}
