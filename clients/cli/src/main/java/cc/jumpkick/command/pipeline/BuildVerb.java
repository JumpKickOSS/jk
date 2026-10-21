// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * One build command: how far its goal runs and what it adds to the one pipeline every build
 * command runs ({@link BuildVerbCommand}). An instance lives for one invocation, so it may keep the
 * state its own options parsed into.
 *
 * <p>The runner owns everything the verbs share: the shared options, the session, the transcript,
 * module selection, the JDK preflight, the workspace run and its settle, the exit code. A verb
 * supplies only what differs.
 */
sealed interface BuildVerb permits CompileVerb, TestVerb, PackageVerb, NativeVerb, ImageVerb, TrainVerb, RunVerb {

    /** The words a run is told in: {@code Build}, {@code building}, {@code build}, {@code Building}. */
    record Words(String name, String gerund, String infinitive, String title) {}

    String name();

    String description();

    default List<String> aliases() {
        return List.of();
    }

    /** Positional parameters, after the options. */
    default List<Param> parameters() {
        return List.of();
    }

    Words words();

    BuildFlags.Reach reach();

    /**
     * False for a verb that runs one module and has no workspace run: a member directory or a
     * one-module selection runs that module standalone, and a workspace root asks for {@code -m}.
     */
    default boolean workspaceShaped() {
        return true;
    }

    default BuildFlags.Affected affected() {
        return BuildFlags.Affected.MODULES;
    }

    /** Options no other build verb has; never one of {@link BuildFlags#options}. */
    default List<Opt> ownOptions() {
        return List.of();
    }

    /**
     * Read this verb's own options and put what the engine reads off the session onto it. Runs
     * after the shared options are installed.
     *
     * @throws IllegalArgumentException a value the run cannot honour; the message is printed
     */
    default void parse(Invocation in, BuildRun run) {}

    /**
     * Anything the verb settles before the build: an answer that replaces it (an exit code), or
     * {@code -1} to go on. The selection is already narrowed to a member directory.
     */
    default int before(BuildRun run) {
        return -1;
    }

    /** The modules a selection resolved to, before a workspace run starts: an exit code, or {@code -1} to go on. */
    default int selected(BuildRun run, List<String> moduleDirs) {
        return -1;
    }

    /** Client-side preparation of a workspace run (prompts, installs): an exit code, or {@code -1} to go on. */
    default int prepareWorkspace(BuildRun run, Path entryDir, BuildRun.Selection selection) {
        return -1;
    }

    /** Drive the workspace run of {@code entryDir} over {@code modules}. */
    WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException;

    /** The success and failure wedges of a workspace run that started at {@code startNanos}. */
    WorkspaceRunView.Tails tails(BuildRun run, WorkspaceRunView view, long startNanos);

    /** Build one standalone project; the run's helpers settle it. */
    int single(BuildRun run, Path dir) throws IOException;

    /** Work after a standalone build ({@code --aot-cache}): the exit code to report. */
    default int afterSingle(BuildRun run, Path dir, int code) {
        return code;
    }

    /** Runs once the run is over, however it ended: the exit code to report. */
    default int after(BuildRun run, int code) {
        return code;
    }
}
