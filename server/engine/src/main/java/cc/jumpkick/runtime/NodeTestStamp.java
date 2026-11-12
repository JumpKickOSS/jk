// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** The run-tests stamp of a module that declares {@code node}: a Node.js upgrade retests it. */
final class NodeTestStamp {

    private NodeTestStamp() {}

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
