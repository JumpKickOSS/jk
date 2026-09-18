// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

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
    public static final String WORKER_MAIN = "cc.jumpkick.plugin.process.PluginMain";

    /**
     * Append the session's {@code --offline} decision to the spec a worker is about to read. The
     * invariant is the <em>seal</em>, not any one launcher: every spec a worker JVM decodes
     * carries a stated network policy, because a worker cannot see the engine's ambient
     * {@link SessionContext} and its fail-closed default ({@code offline = true} when unstated)
     * is indistinguishable from a stated refusal. {@code PluginLaunch.javaCommand} calls this at
     * the generic plugin fork; the compiler and formatter spec writers ({@code
     * ForkedJavac.writeSpec}, {@code KotlincSpec}, {@code GroovycSpec}, the AOT trainer specs)
     * call it themselves because their forks go through {@link #command} directly. Seal a spec in
     * exactly one place — the producer or the fork, never both.
     */
    public static void sealNetworkPolicy(Path spec) throws IOException {
        List<String> line =
                new SpecWriter().offline(SessionContext.current().offline()).lines();
        Files.write(spec, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /**
     * Fork a plugin and stream its events. Returns the plugin's exit code.
     *
     * @param javaHome the JDK to launch under (the project-pinned one) — the HOME, not its
     *     launcher: the worker needs both the {@code java} to run and a {@code JAVA_HOME} to
     *     agree with it, and a caller that hands over only the launcher leaves the second to
     *     be guessed or inherited from the daemon
     * @param classpath the plugin's classpath (must include the plugin jar)
     * @param jvmFlags heap/GC/etc. tuning flags (see {@link cc.jumpkick.engine.plugin.JvmOptions})
     * @param prefix the protocol-line marker the plugin emits (its manifest prefix)
     * @param args program args passed after {@code PluginMain}
     */
    public static int run(
            Path javaHome,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            Consumer<String> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return run(javaHome, classpath, jvmFlags, prefix, args, WorkerEnv.strict(), onProtocol, onPassthrough);
    }

    /**
     * As {@link #run(Path, String, List, String, List, Consumer, Consumer)}, adding {@code env}
     * to the child process environment (e.g. isolated {@code JK_STATE_DIR} for nested-engine tests).
     *
     * <p>The test runner also uses it to hand a suite a sandboxed {@code JK_HOME}without
     * that, a forked test JVM inherits the engine's environment and reads the developer's real
     * {@code JK_HOME} / platform product layout.
     */
    public static int run(
            Path javaHome,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            WorkerEnv env,
            Consumer<String> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return run(javaHome, classpath, jvmFlags, prefix, args, env, null, onProtocol, onPassthrough);
    }

    /** As {@link #run} with an optional working directory for the child process. */
    public static int run(
            Path javaHome,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            WorkerEnv env,
            @Nullable Path workDir,
            Consumer<String> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        // One-shot: close the child's stdin immediately so suite tests that hit Confirm /
        // System.in.readLine() see EOF instead of hanging on an open protocol pipe.
        return PluginProcess.run(
                command(javaHome, classpath, jvmFlags, args),
                env.withJavaHome(javaHome),
                workDir,
                prefix,
                onProtocol,
                onPassthrough);
    }

    /**
     * As {@link #run}, but drives a two-way conversation (pull protocol): each protocol line arrives
     * with a {@link PluginProcess.Conversation} the caller can use to send commands back to the
     * plugin's stdin.
     */
    public static int converse(
            Path javaHome,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            BiConsumer<String, PluginProcess.Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(javaHome, classpath, jvmFlags, prefix, args, WorkerEnv.strict(), onProtocol, onPassthrough);
    }

    /** As {@link #converse(Path, String, List, String, List, BiConsumer, Consumer)} with the child's {@link WorkerEnv}. */
    public static int converse(
            Path javaHome,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            WorkerEnv env,
            BiConsumer<String, PluginProcess.Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(javaHome, classpath, jvmFlags, prefix, args, env, null, onProtocol, onPassthrough);
    }

    /** As {@link #converse} with optional working directory. */
    public static int converse(
            Path javaHome,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            WorkerEnv env,
            @Nullable Path workDir,
            BiConsumer<String, PluginProcess.Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return PluginProcess.converse(
                command(javaHome, classpath, jvmFlags, args),
                env.withJavaHome(javaHome),
                workDir,
                prefix,
                onProtocol,
                onPassthrough);
    }

    /** As {@link #converse} with an inactivity watchdog — see {@link PluginProcess#converse}. */
    public static int converse(
            Path javaHome,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            WorkerEnv env,
            @Nullable Path workDir,
            BiConsumer<String, PluginProcess.Conversation> onProtocol,
            @Nullable Consumer<String> onPassthrough,
            long idleTimeoutMs)
            throws IOException, InterruptedException {
        return PluginProcess.converse(
                command(javaHome, classpath, jvmFlags, args),
                env.withJavaHome(javaHome),
                workDir,
                prefix,
                onProtocol,
                onPassthrough,
                false,
                idleTimeoutMs);
    }

    /**
     * Build the plugin launch command {@code <java> <jvmFlags> -cp <classpath>
     * cc.jumpkick.plugin.process.PluginMain <args>} without running it — for callers that drive
     * the stream themselves via {@link PluginClient}.
     */
    public static List<String> command(Path javaHome, String classpath, List<String> jvmFlags, List<String> args) {
        return command(javaHome, classpath, jvmFlags, WORKER_MAIN, args);
    }

    /**
     * As {@link #command(Path, String, List, List)} for a jar that declares its own
     * {@code Main-Class} instead of running under {@link #WORKER_MAIN}. <strong>The one worker argv
     * assembly in the engine</strong> — every fork of a jk worker JVM, generic or compiler, orders
     * its elements here. It was two: {@code PluginLaunch} open-coded the same six-element shape
     * beside a comment reading "Reuse PluginLoader.command shape", which is a copy admitting to
     * being one.
     */
    public static List<String> command(
            Path javaHome, String classpath, List<String> jvmFlags, String mainClass, List<String> args) {
        var cmd = new ArrayList<String>();
        cmd.add(JdkFingerprint.java(javaHome).toString());
        cmd.addAll(jvmFlags);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(mainClass);
        cmd.addAll(args);
        return cmd;
    }
}
