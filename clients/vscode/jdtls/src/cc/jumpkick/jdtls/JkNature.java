// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdtls;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectNature;

/** Marks a project this bundle created from the JumpKick model, and so may update or delete. */
public final class JkNature implements IProjectNature {

    /** The bundle's symbolic name and the extension's id. */
    public static final String ID = "cc.jumpkick.jdtls.nature";

    private IProject project;

    @Override
    public void configure() {}

    @Override
    public void deconfigure() {}

    @Override
    public IProject getProject() {
        return project;
    }

    @Override
    public void setProject(IProject project) {
        this.project = project;
    }

    static boolean has(IProject project) {
        try {
            return project.isOpen() && project.hasNature(ID);
        } catch (Exception e) {
            return false;
        }
    }
}
