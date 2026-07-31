// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Forks a jk plugin process and bridges its JSONL event stream back to the caller. A plugin is
 * launched as {@code <java> <jvmFlags> -cp <classpath> cc.jumpkick.plugin.process.PluginMain
 * <args>}: {@code PluginMain} {@code ServiceLoader}-loads the single {@link
 * cc.jumpkick.plugin.Plugin} on that classpath, runs it, and the plugin emits {@code
 * <prefix>}-tagged protocol lines on stdout.
 *
 * <p>jk has no host JVM and the CLI is a closed-world native image, so plugins never run in-process
 * — every plugin is its own JVM, dispatched directly. The caller supplies the plugin's classpath
 * (which must carry the plugin jar plus whatever it needs — e.g. the user's test classes + engines
 * for the test runner), the JVM tuning flags ({@link cc.jumpkick.engine.plugin.JvmOptions}), and the
 * protocol prefix the plugin emits.
 */
public final class PluginLoader {

    private PluginLoader() {}

    /** Fully-qualified main class every plugin jar runs under (vendored from plugin-api). */
    static final String WORKER_MAIN = "cc.jumpkick.plugin.process.PluginMain";

    /**
     * Fork a plugin and stream its events. Returns the plugin's exit code.
     *
     * @param javaExe the JVM to launch (the project-pinned JDK's {@code java})
     * @param classpath the plugin's classpath (must include the plugin jar)
     * @param jvmFlags heap/GC/etc. tuning flags (see {@link cc.jumpkick.engine.plugin.JvmOptions})
     * @param prefix the protocol-line marker the plugin emits (its manifest prefix)
     * @param args program args passed after {@code PluginMain}
     */
    public static int run(
            Path javaExe,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            Consumer<String> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return run(javaExe, classpath, jvmFlags, prefix, args, Map.of(), onProtocol, onPassthrough);
    }

    /**
     * As {@link #run(Path, String, List, String, List, Consumer, Consumer)}, adding {@code extraEnv}
     * to the child process environment (e.g. isolated {@code JK_STATE_DIR} for nested-engine tests).
     *
     * <p>The test runner also uses it to hand a suite a sandboxed {@code JK_HOME}without
     * that, a forked test JVM inherits the engine's environment and reads the developer's real
     * {@code ~/.jk}.
     */
    public static int run(
            Path javaExe,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            Map<String, String> extraEnv,
            Consumer<String> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return run(javaExe, classpath, jvmFlags, prefix, args, extraEnv, null, onProtocol, onPassthrough);
    }

    /** As {@link #run} with an optional working directory for the child process. */
    public static int run(
            Path javaExe,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            Map<String, String> extraEnv,
            Path workDir,
            Consumer<String> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return PluginProcess.converse(
                command(javaExe, classpath, jvmFlags, args),
                extraEnv,
                workDir,
                prefix,
                (json, convo) -> onProtocol.accept(json),
                onPassthrough);
    }

    /**
     * As {@link #run}, but drives a two-way conversation (pull protocol): each protocol line arrives
     * with a {@link PluginProcess.Conversation} the caller can use to send commands back to the
     * plugin's stdin.
     */
    public static int converse(
            Path javaExe,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            BiConsumer<String, PluginProcess.Conversation> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(javaExe, classpath, jvmFlags, prefix, args, Map.of(), onProtocol, onPassthrough);
    }

    /**
     * As {@link #converse(Path, String, List, String, List, BiConsumer, Consumer)}, adding {@code
     * extraEnv} to the child process environment.
     */
    public static int converse(
            Path javaExe,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            Map<String, String> extraEnv,
            BiConsumer<String, PluginProcess.Conversation> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(javaExe, classpath, jvmFlags, prefix, args, extraEnv, null, onProtocol, onPassthrough);
    }

    /** As {@link #converse} with optional working directory. */
    public static int converse(
            Path javaExe,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            Map<String, String> extraEnv,
            Path workDir,
            BiConsumer<String, PluginProcess.Conversation> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return PluginProcess.converse(
                command(javaExe, classpath, jvmFlags, args), extraEnv, workDir, prefix, onProtocol, onPassthrough);
    }

    /**
     * Build the plugin launch command {@code <java> <jvmFlags> -cp <classpath>
     * cc.jumpkick.plugin.process.PluginMain <args>} without running it — for callers that drive
     * the stream themselves via {@link PluginClient}.
     */
    public static List<String> command(Path javaExe, String classpath, List<String> jvmFlags, List<String> args) {
        var cmd = new ArrayList<String>();
        cmd.add(javaExe.toString());
        cmd.addAll(jvmFlags);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(WORKER_MAIN);
        cmd.addAll(args);
        return cmd;
    }
}
