// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdtls;

import java.io.File;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.ls.core.internal.AbstractProjectImporter;

/**
 * Claims a workspace folder holding a {@code jk.toml}, so neither Buildship, m2e nor the invisible
 * project imports it. It creates nothing itself: the projects come from the engine's model, which
 * the JumpKick extension pushes through {@link ApplyModelHandler}, and persist in the language
 * server's workspace between sessions.
 */
public final class JkProjectImporter extends AbstractProjectImporter {

    @Override
    public boolean applies(IProgressMonitor monitor) {
        return rootFolder != null && isJk(rootFolder);
    }

    @Override
    public boolean isResolved(File folder) {
        return folder != null && isJk(folder);
    }

    @Override
    public void importToWorkspace(IProgressMonitor monitor) {}

    @Override
    public void reset() {}

    static boolean isJk(File folder) {
        return new File(folder, "jk.toml").isFile();
    }
}
