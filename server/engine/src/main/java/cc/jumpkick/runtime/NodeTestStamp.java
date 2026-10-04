// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ClasspathFingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The run-tests stamp of a module that declares {@code node}: a Node.js upgrade retests it. */
final class NodeTestStamp {

    private NodeTestStamp() {}

    /** {@link PlannerSupport}'s stamp extras, plus the locked Node.js of a module that declares one. */
    static List<String> testStampExtras(
            Map<String, String> workerJars,
            TestSelection selection,
            JkBuild project,
            List<String> jvmArgs,
            Path moduleDir,
            ClasspathFingerprint.EntryIdentity identity)
            throws IOException {
        List<String> extras = new ArrayList<>(
                PlannerSupport.testStampExtras(workerJars, selection, project.build(), jvmArgs, moduleDir, identity));
        String node = nodeTestToken(project, moduleDir);
        if (node != null) extras.add(node);
        return extras;
    }

    /**
     * The locked Node.js a module that declares {@code node} runs its tests with, as a stamp token:
     * a node upgrade retests its suites. {@code null} for a module that declares none.
     */
    static @Nullable String nodeTestToken(JkBuild project, Path dir) throws IOException {
        if (project.project().nodeSpec().isEmpty()) return null;
        Path lock = LockPaths.lockFile(dir);
        String token = Files.exists(lock) ? PlannerNodeSetup.token(LockfileReader.read(lock)) : "none";
        return token.startsWith("node:") ? token : "node:" + token;
    }
}
