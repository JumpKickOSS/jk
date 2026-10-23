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
}
