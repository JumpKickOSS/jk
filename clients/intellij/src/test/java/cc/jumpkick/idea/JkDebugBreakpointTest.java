// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static java.util.Objects.requireNonNull;

import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.PsiLocation;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerManagerListener;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.NotNull;

/**
 * The gutter's Debug end to end: a project {@code jk new} scaffolds is imported, a line breakpoint
 * is set in its test, and the JumpKick run configuration the producer builds for that class runs
 * under the Debug executor. {@code jk test --debug-jvm} builds and starts the suspended JVM,
 * {@link JkDebugRunner} attaches, the session pauses on the breakpoint's line, and after a resume
 * the run exits 0. Skips when no {@code jk} is on PATH.
 */
public class JkDebugBreakpointTest extends HeavyPlatformTestCase {

    /** Covers resolving JUnit, the build and the test JVM's start on a cold store. */
    private static final long PAUSE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

    private static final long EXIT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(2);

    @Override
    protected boolean isCreateDirectoryBasedProject() {
        return true;
    }

    @Override
    protected void setUpModule() {}

    @Override
    protected void tearDown() throws Exception {
        try {
            WriteAction.run(() -> {
                ProjectJdkTable table = ProjectJdkTable.getInstance();
                for (Sdk sdk : table.getAllJdks()) {
                    if (sdk.getName().startsWith("jk-")) table.removeJdk(sdk);
                }
            });
        } finally {
            super.tearDown();
        }
    }

    public void test_a_breakpoint_in_a_jk_run_test_stops_the_debugger() throws Exception {
        JkImport.assumeJkOnPath(JkDebugBreakpointTest.class);
        Path parent = createTempDir("jk-debug").toPath();
        JkCliRunner.Result created = JkCliRunner.run(parent.toFile(), "new", "demo", "--lang", "java");
        assertTrue("jk new: " + created.stderr(), created.ok());
        Path root = parent.resolve("demo");

        JkImport.Result imported = JkImport.importProject(getProject(), root.toFile());
        assertNull("sync failed: " + imported.failure(), imported.failure());

        Path source = root.resolve("src/test/java/com/example/CalcTest.java");
        VirtualFile vf = requireNonNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source));
        int line = lineOf(Files.readAllLines(source), "assertEquals(");
        JavaLineBreakpointType type = XDebuggerUtil.getInstance().findBreakpointType(JavaLineBreakpointType.class);
        WriteAction.run(() -> XDebuggerManager.getInstance(getProject())
                .getBreakpointManager()
                .addLineBreakpoint(type, vf.getUrl(), line, type.createBreakpointProperties(vf, line)));

        PsiClass cls = ((PsiJavaFile)
                        requireNonNull(PsiManager.getInstance(getProject()).findFile(vf)))
                .getClasses()[0];
        ConfigurationFromContext from = requireNonNull(new JkRunConfigurationProducer()
                .createConfigurationFromContext(
                        ConfigurationContext.createEmptyContextForLocation(new PsiLocation<>(getProject(), cls))));
        RunnerAndConfigurationSettings settings = from.getConfigurationSettings();
        ExecutionEnvironment env = ExecutionEnvironmentBuilder.create(
                        DefaultDebugExecutor.getDebugExecutorInstance(), settings)
                .build();
        assertInstanceOf(env.getRunner(), JkDebugRunner.class);

        CompletableFuture<XDebugSession> paused = new CompletableFuture<>();
        getProject()
                .getMessageBus()
                .connect(getTestRootDisposable())
                .subscribe(XDebuggerManager.TOPIC, new XDebuggerManagerListener() {
                    @Override
                    public void processStarted(@NotNull XDebugProcess process) {
                        XDebugSession session = process.getSession();
                        session.addSessionListener(new XDebugSessionListener() {
                            @Override
                            public void sessionPaused() {
                                paused.complete(session);
                            }
                        });
                    }
                });
        CompletableFuture<RunContentDescriptor> started = new CompletableFuture<>();
        env.setCallback(started::complete);
        ExecutionManager.getInstance(getProject()).restartRunProfile(env);

        pumpUntil(started::isDone, PAUSE_TIMEOUT_MS, "debug session started");
        ProcessHandler jk = requireNonNull(started.get().getProcessHandler());
        StringBuilder output = new StringBuilder();
        jk.addProcessListener(new ProcessAdapter() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key type) {
                synchronized (output) {
                    output.append(event.getText());
                }
            }
        });
        pumpUntil(() -> paused.isDone() || jk.isProcessTerminated(), PAUSE_TIMEOUT_MS, "breakpoint hit");
        assertTrue("jk ended before the breakpoint stopped it:\n" + output, paused.isDone());

        XDebugSession session = paused.get();
        XSourcePosition at = requireNonNull(session.getCurrentPosition(), "paused without a position");
        assertEquals(vf, at.getFile());
        assertEquals("stopped on the breakpoint's line", line, at.getLine());

        session.resume();
        pumpUntil(jk::isProcessTerminated, EXIT_TIMEOUT_MS, "jk test exits after resume");
        assertEquals("jk test exit code:\n" + output, Integer.valueOf(0), jk.getExitCode());
    }

    /** The 0-based line of the first line containing {@code needle}. */
    private static int lineOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(needle)) return i;
        throw new AssertionError("no line contains " + needle);
    }

    /** Runs the EDT's queue until {@code done} holds; the debugger and the run both post to it. */
    private static void pumpUntil(BooleanSupplier done, long timeoutMs, String what) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for: " + what);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for: " + what, e);
            }
        }
    }
}
