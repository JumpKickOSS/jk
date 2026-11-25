// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Which step writes a module's main jar, the artifact install shelves and publish uploads. */
@NullMarked
public final class ModuleJar {

    private ModuleJar() {}

    /**
     * {@code package-jar} for a JVM module, {@code node-package} for a node module that packages a
     * resource jar, and {@code null} for a module with no jar: a sourceless workspace root, or a node
     * module nothing depends on.
     */
    public static @Nullable String packagingStep(JkBuild project, Path moduleDir) {
        if (NodeShape.isNodeModule(project.project(), moduleDir)) {
            try {
                return NodePackaging.classpathRoot(project, moduleDir) != null ? TaskNames.NODE_PACKAGE : null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return CompileSupport.coordinatorOnly(project, moduleDir) ? null : TaskNames.PACKAGE_JAR;
    }

    /**
     * {@link #packagingStep}, or {@code null} for a module whose {@code [publish] skip} keeps it out
     * of {@code jk publish} and {@code jk install}: the step whose jar a release ships, if any.
     */
    public static @Nullable String shippedStep(JkBuild project, Path moduleDir) {
        return skipsRelease(project) ? null : packagingStep(project, moduleDir);
    }

    /** A replacement packager owns the main artifact, so this module has no Maven jar. */
    public static @Nullable String mavenRefusal(JkBuild project, Path moduleDir) {
        return ActivePlugins.packager(project, moduleDir)
                .map(plugin -> plugin.manifest().table() + ": use the release lane for packaged apps")
                .orElse(null);
    }

    /** {@code [publish] skip = true}: the module builds and tests, and no release ships it. */
    public static boolean skipsRelease(JkBuild project) {
        return project.pomMetadata().skip();
    }
}
