// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.configurations.RemoteState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The running {@code jk} process of a {@link JkRunConfiguration}. Under the Debug executor the
 * state picks a free loopback port before the launch, tells jk to start the JVM with a suspended
 * JDWP listener there ({@code --debug-jvm=localhost:<port>}), and hands the same address to the
 * debugger as a {@link RemoteConnection}; the debugger keeps connecting until the JVM listens,
 * which is after jk has built the module.
 */
final class JkCommandState extends CommandLineState implements RemoteState {

    private final JkRunConfiguration configuration;
    private final @Nullable RemoteConnection connection;

    JkCommandState(@NotNull ExecutionEnvironment environment, JkRunConfiguration configuration) {
        super(environment);
        this.configuration = configuration;
        this.connection = DefaultDebugExecutor.EXECUTOR_ID.equals(
                        environment.getExecutor().getId())
                ? new RemoteConnection(true, "localhost", String.valueOf(pickPort()), false)
                : null;
    }

    /** The command jk runs, as {@code jk} sees it. */
    List<String> args() {
        return JkCommandLines.args(
                configuration.kind(), configuration.moduleRel(), configuration.className(), debugAddress());
    }

    private @Nullable String debugAddress() {
        return connection == null ? null : connection.getDebuggerHostName() + ":" + connection.getDebuggerAddress();
    }

    @Override
    protected @NotNull ProcessHandler startProcess() throws ExecutionException {
        GeneralCommandLine cmd = new GeneralCommandLine(JkBin.path())
                .withParameters(args())
                .withWorkDirectory(Path.of(configuration.rootDir()).toFile())
                .withCharset(StandardCharsets.UTF_8)
                .withEnvironment("NO_COLOR", "1");
        KillableColoredProcessHandler handler = new KillableColoredProcessHandler(cmd);
        ProcessTerminatedListener.attach(handler);
        return handler;
    }

    /** Where the debugger attaches; only a Debug executor's state has one. */
    @Override
    public @NotNull RemoteConnection getRemoteConnection() {
        if (connection == null) throw new IllegalStateException("not a debug run");
        return connection;
    }

    private static int pickPort() {
        try {
            return JkCommandLines.freePort();
        } catch (IOException e) {
            return 5005;
        }
    }
}
