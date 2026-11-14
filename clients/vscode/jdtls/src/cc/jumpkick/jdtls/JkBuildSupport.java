// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdtls;

import java.util.List;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.ls.core.internal.managers.IBuildSupport;

/**
 * The build support of a JumpKick project: a manifest or lockfile change updates nothing here,
 * since the extension relocks after a quiet window and pushes the new model itself.
 */
public final class JkBuildSupport implements IBuildSupport {

    private static final List<String> MANIFESTS = List.of("jk.toml", "jk-lock.toml");

    @Override
    public boolean applies(IProject project) {
        return JkNature.has(project);
    }

    @Override
    public boolean isBuildFile(IResource resource) {
        return resource != null && resource.getType() == IResource.FILE && MANIFESTS.contains(resource.getName());
    }

    @Override
    public boolean isBuildLikeFileName(String fileName) {
        return MANIFESTS.contains(fileName);
    }

    @Override
    public void update(IProject project, boolean force, IProgressMonitor monitor) {}

    @Override
    public String buildToolName() {
        return "JumpKick";
    }
}
