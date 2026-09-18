// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.debugger.impl.GenericDebuggerRunner;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Debug for a {@link JkRunConfiguration}: start the {@code jk} command with its JDWP address and
 * attach the Java debugger to that address, retrying until the JVM jk starts is listening. The
 * retry window covers the build that precedes a test JVM; a jk run that ends first ends the
 * session with it.
 */
public final class JkDebugRunner extends GenericDebuggerRunner {

    /** How long the debugger keeps trying to connect while jk builds and then starts the JVM. */
    static final long ATTACH_POLL_MS = 10 * 60 * 1000L;

    @Override
    public @NotNull String getRunnerId() {
        return "JumpKick.Debug";
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof JkRunConfiguration;
    }

    @Override
    protected @Nullable RunContentDescriptor createContentDescriptor(
            @NotNull RunProfileState state, @NotNull ExecutionEnvironment environment) throws ExecutionException {
        JkCommandState jk = (JkCommandState) state;
        return attachVirtualMachine(state, environment, jk.getRemoteConnection(), ATTACH_POLL_MS);
    }
}
