// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.JarPackager;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.build.In;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Where a node build's output goes: into its module's resource jar under the classpath root, which
 * a JVM dependant puts on its classpath, or into a war dependant's root under the webapp root. A
 * JVM module's own node build goes into that module's jar or war.
 */
final class NodePackaging {

    /** The classpath root a node module packages under when something depends on it and it names none. */
    static final String DEFAULT_CLASSPATH_ROOT = "static";

    private NodePackaging() {}

    /** A node sibling's output as a war takes it: the directory, and the war-relative path it lands under. */
    record WebContent(Path out, String root) {}

    /**
     * The root {@code project}'s node output sits under in its jar: {@code [node] classpath-root}, else
     * {@value #DEFAULT_CLASSPATH_ROOT} when a workspace member depends on the module, else {@code null}
     * (nothing is packaged). An empty root is the jar's own root.
     */
    static @Nullable String classpathRoot(JkBuild project, Path moduleDir) throws IOException {
        String declared = project.node().classpathRoot();
        if (declared != null) return trim(declared);
        return hasDependants(project, moduleDir) ? DEFAULT_CLASSPATH_ROOT : null;
    }

    /** Whether a workspace member reads {@code moduleDir} on its runtime classpath. */
    static boolean hasDependants(JkBuild project, Path moduleDir) throws IOException {
        Path self = moduleDir.toAbsolutePath().normalize();
        Path root = BuildLayout.of(self, project).workspaceRoot();
        if (root.equals(self)) return false;
        JkBuild rootBuild = JkBuildParser.parse(ManifestPaths.manifestIn(root));
        for (Map.Entry<Path, JkBuild> member :
                WorkspaceLoader.loadModules(root, rootBuild).entrySet()) {
            Path dir = member.getKey().toAbsolutePath().normalize();
            if (dir.equals(self)) continue;
            if (WorkspaceClasspath.closureSiblings(dir, member.getValue(), WorkspaceClasspath.RUNTIME_SCOPES)
                    .containsKey(self)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The node modules among {@code moduleDir}'s runtime siblings, with each one's output and the
     * path under the war root it lands at ({@code [node] webapp-root}, the root itself by default).
     */
    static List<WebContent> webContent(Path moduleDir, JkBuild project) throws IOException {
        List<WebContent> out = new ArrayList<>();
        if (NodeShape.kind(project, moduleDir) == NodeShape.Kind.SIDE_BY_SIDE) {
            Path own = NodeShape.outputDir(project, moduleDir);
            String root = project.node().webappRoot();
            if (own != null) out.add(new WebContent(own, root == null ? "" : trim(root)));
        }
        for (Map.Entry<Path, JkBuild> sibling : WorkspaceClasspath.closureSiblings(
                        moduleDir, project, WorkspaceClasspath.RUNTIME_SCOPES)
                .entrySet()) {
            JkBuild build = sibling.getValue();
            if (NodeShape.kind(build, sibling.getKey()) != NodeShape.Kind.MODULE) continue;
            Path dir = NodeShape.outputDir(build, sibling.getKey());
            if (dir == null) continue;
            String root = build.node().webappRoot();
            out.add(new WebContent(dir, root == null ? "" : trim(root)));
        }
        return out;
    }

    /** The jars of {@code webContent}'s modules, which a war carries as web content rather than in WEB-INF/lib. */
    static List<Path> nodeJars(Path moduleDir, JkBuild project) throws IOException {
        List<Path> jars = new ArrayList<>();
        for (Map.Entry<Path, JkBuild> sibling : WorkspaceClasspath.closureSiblings(
                        moduleDir, project, WorkspaceClasspath.RUNTIME_SCOPES)
                .entrySet()) {
            if (NodeShape.kind(sibling.getValue(), sibling.getKey()) != NodeShape.Kind.MODULE) continue;
            jars.add(BuildLayout.of(sibling.getKey(), sibling.getValue())
                    .mainJar()
                    .toAbsolutePath()
                    .normalize());
        }
        return jars;
    }

    /**
     * Where a JVM module's own node build ({@code src/main/node}) is staged for its packaging: the
     * output under the classpath root, merged into the module's jar like its classes. {@code null}
     * for a module without one, and for a {@code [war]} module, whose war takes the output instead.
     */
    static @Nullable Path sideBySideStage(@Nullable JkBuild project, BuildLayout layout) {
        if (project == null || project.build().war() != null) return null;
        if (NodeShape.kind(project, layout.moduleRoot()) != NodeShape.Kind.SIDE_BY_SIDE) return null;
        return PluginBuild.taskScratch(layout, In.NODE_CLASSPATH);
    }

    /** {@link #sideBySideStage} when it has been staged, for the packaged dirs. */
    static List<Path> packagedRoot(@Nullable JkBuild project, BuildLayout layout) {
        Path stage = sideBySideStage(project, layout);
        return stage != null && Files.isDirectory(stage) ? List.of(stage) : List.of();
    }

    /** The root a JVM module's own node output sits under in its jar: {@code [node] classpath-root}, else {@value #DEFAULT_CLASSPATH_ROOT}. */
    static String sideBySideRoot(JkBuild project) {
        String declared = project.node().classpathRoot();
        return declared == null ? DEFAULT_CLASSPATH_ROOT : trim(declared);
    }

    /**
     * Make {@code stage} hold exactly {@code out}'s files under {@code root}: what is already the same
     * is left alone, what the output no longer has is removed.
     */
    static void stage(Path out, Path stage, String root) throws IOException {
        Path into = root.isEmpty() ? stage : stage.resolve(root);
        if (Files.isDirectory(stage)) {
            List<Path> stale = new ArrayList<>();
            try (Stream<Path> files = Files.find(stage, Integer.MAX_VALUE, (p, attrs) -> attrs.isRegularFile())) {
                files.filter(f -> !f.startsWith(into)
                                || !Files.isRegularFile(
                                        out.resolve(into.relativize(f).toString())))
                        .forEach(stale::add);
            }
            for (Path f : stale) Files.deleteIfExists(f);
        }
        Files.createDirectories(into);
        PathUtil.copyTree(out, into);
    }

    /** Write {@code jar} with {@code out}'s files under {@code root}, staged in {@code stage}. */
    static void packageJar(Path out, String root, Path stage, Path jar, Map<String, String> manifest)
            throws IOException {
        PathUtil.deleteRecursivelyOrThrow(stage);
        Path into = root.isEmpty() ? stage : stage.resolve(root);
        Files.createDirectories(into);
        PathUtil.copyTree(out, into);
        new JarPackager().packageJar(JarPackager.JarRequest.of(stage, jar).withAttributes(manifest));
    }

    private static String trim(String root) {
        String r = root.replace('\\', '/');
        while (r.startsWith("/")) r = r.substring(1);
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r.equals(".") ? "" : r;
    }
}
