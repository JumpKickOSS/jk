// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import cc.jumpkick.plugin.PluginConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** What a {@link PluginCommandSpec} body sees: its CLI args, the plugin config/project facts, and output. */
public interface PluginCommandExec {

    /** The args after the command on the jk command line, verbatim. */
    List<String> args();

    PluginConfig config();

    ProjectFacts project();

    /** The project directory the command runs against. */
    Path moduleDir();

    /**
     * A declared tool artifact, resolved engine-side. Commands read both lanes: every
     * {@code [[contribute.step-dependency]]} (the same tools steps get — a bundletool a packager
     * also runs) and every {@code [[contribute.command-dependency]]} (command-only tools — an adb,
     * an SDK root — provisioned only when the command runs and in no build action key).
     */
    Optional<Path> extra(String name);

    /** As {@link #extra} but required. */
    default Path requireExtra(String name) {
        return extra(name)
                .orElseThrow(() -> new IllegalStateException("tool artifact not provided: " + name
                        + " — declare it as a [[contribute.command-dependency]] (command-only) or"
                        + " [[contribute.step-dependency]] (a step tool the command borrows)"));
    }

    /**
     * The built main artifact (the packager's, under its declared extension — an APK), or empty
     * when not built yet. Deploy-style commands consume this instead of learning jk's layout.
     */
    Optional<Path> mainArtifact();

    /** Emit one user-facing output line (the client prints these in order). */
    void out(String line);

    /**
     * The JDK this command runs against — for {@link #tool} forks. The <em>project's</em> pinned
     * JDK, resolved engine-side and stamped onto the spec, not the worker JVM's own
     * {@code java.home}: a worker is launched on the engine's floor JDK, so reading its
     * {@code java.home} silently runs a {@code keytool} or a {@code bundletool} on a different
     * JDK than the build's.
     */
    Path javaHome();

    /**
     * A {@code bin/<name>} fork off {@link #javaHome()} ({@code java}, {@code keytool}, …). The
     * same surface {@link TaskExec#tool} and {@link PackageIo#tool} give a step and a packager —
     * a command body has the same need and had no way to say it, which is why five hand-rolled
     * launchers grew here.
     */
    default TaskExec.ToolRun tool(String bin) {
        return new TaskExec.ToolRun(javaHome(), bin);
    }

    /** A fork of an arbitrary executable (a provisioned tool: adb, emulator, …). */
    default TaskExec.ToolRun tool(Path executable) {
        return new TaskExec.ToolRun(executable);
    }

    /** Convenience for the common case. */
    default TaskExec.ToolRun java() {
        return tool("java");
    }

    /**
     * Whether this job forbids network access — the user's {@code --offline}, decided once by the
     * engine and stamped onto the spec at the fork. A worker that is about to reach out asks this
     * first and refuses, naming what it wanted; it must never consult {@code JK_OFFLINE} or a
     * system property, which inside a forked JVM describe the engine daemon's startup environment
     * rather than this job.
     */
    boolean offline();

    /** Progress label (spinner text), same channel as step/packager labels. */
    void label(String text);
}
