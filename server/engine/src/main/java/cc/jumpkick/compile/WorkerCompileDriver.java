// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.engine.plugin.WorkerAotCache;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Drives a secondary-language compile by forking its worker plugin, which runs that language's
 * compiler in-process on jk's own runtime.
 *
 * <p>One fork body for every language. A worker is launched as {@code <hostJavaHome>/bin/java -cp
 * <workerClasspath> cc.jumpkick.plugin.process.PluginMain @<spec>}; it streams JSONL back on stdout
 * (each line carrying the plugin's protocol prefix) and we collect the diagnostics and the terminal
 * result. The plugin is jk's OWN process: it runs on jk's runtime, because plugins are built at
 * jk's language level and must not be hostage to the project's pinned JDK. The project's toolchain
 * is an INPUT, written into the spec.
 *
 * <p>Kotlin and Groovy are not the same compile and this does not pretend they are. The whole of
 * the difference is the one exhaustive {@code switch} in {@link #plan}: kotlinc gets the project
 * JDK as {@code -jdk-home} and a startup-cached worker; groovyc gets neither, and reports located
 * diagnostics where the Kotlin Build Tools logger reports bare text. Adding a third JVM language
 * adds an arm there and a spec writer beside {@link KotlincSpec}/{@link GroovycSpec} — not a
 * fourth driver.
 */
public final class WorkerCompileDriver {

    /** Mirrors the {@code jk-kotlin-compiler} manifest prefix. */
    static final String KOTLIN_PREFIX = "##JKKC:";

    /** Mirrors the {@code jk-groovy-compiler} manifest prefix. */
    private static final String GROOVY_PREFIX = "##JKGC:";

    /** How much non-protocol worker chatter to keep for a worker that dies before speaking. */
    private static final int CHATTER_TAIL = 40;

    private WorkerCompileDriver() {}

    /** Compile Kotlin by forking {@code jk-kotlin-compiler} (the Kotlin Build Tools API). */
    public static CompileResult compile(KotlincRequest request, WorkerEnv env) {
        return compile(new Job.Kotlin(request, env));
    }

    /** Compile Groovy by forking {@code jk-groovy-compiler} (the Groovy 5 compiler). */
    public static CompileResult compile(GroovycRequest request, WorkerEnv env) {
        return compile(new Job.Groovy(request, env));
    }

    /** One worker compile. Sealed so {@link #plan} is exhaustive and a new language cannot forget an arm. */
    sealed interface Job {

        /** The tool named in failure text ({@code kotlinc}, {@code groovyc}). */
        String tool();

        /** What the worker JVM starts with. */
        WorkerEnv env();

        record Kotlin(KotlincRequest request, WorkerEnv env) implements Job {
            @Override
            public String tool() {
                return "kotlinc";
            }
        }

        record Groovy(GroovycRequest request, WorkerEnv env) implements Job {
            @Override
            public String tool() {
                return "groovyc";
            }
        }
    }

    private static CompileResult compile(Job job) {
        try {
            return run(job);
        } catch (IOException e) {
            // One retry when the worker pipe closes mid-compile (flake).
            if (PluginProcess.isPipeClosed(e)) {
                try {
                    return run(job);
                } catch (IOException e2) {
                    throw new UncheckedIOException(
                            job.tool() + " compile failed after pipe-closed retry: " + e2.getMessage(), e2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(job.tool() + " compile interrupted on retry", ie);
                }
            }
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(job.tool() + " compile interrupted", e);
        }
    }

    /**
     * Everything the shared fork body needs, derived once per language by {@link #plan}: the
     * rendered spec, the joined worker classpath, the JVM flags in front of {@code -cp} and how to
     * read one {@code diagnostic} reply.
     */
    private record Fork(
            String prefix,
            Path spec,
            String classpath,
            List<String> jvmFlags,
            Function<String, CompileResult.Diagnostic> decode) {}

    private static Fork plan(Job job, Path hostJavaHome) throws IOException {
        return switch (job) {
            case Job.Kotlin(KotlincRequest request, WorkerEnv env) -> {
                String classpath = Classpaths.join(request.workerClasspath());
                // The Kotlin compiler IS this classpath, so a startup cache tames its multi-second
                // JIT warmup. Mapped when one exists for (host JDK, GC, classpath); else a background
                // trainer compiles a synthetic hello.kt so the NEXT Kotlin build maps it.
                yield new Fork(
                        KOTLIN_PREFIX,
                        KotlincSpec.write(request),
                        classpath,
                        WorkerAotCache.flags(
                                "kotlinc",
                                hostJavaHome,
                                classpath,
                                List.of(),
                                (aotOutput, scratch) -> KotlincSpec.trainerCommand(
                                        request, classpath, hostJavaHome, aotOutput, scratch)),
                        // The BTA logger surfaces text only — no file/line/col.
                        json -> WorkerDiagnostics.text(Jsonl.str(json, "sev"), Jsonl.str(json, "msg")));
            }
            case Job.Groovy(GroovycRequest request, WorkerEnv env) ->
                new Fork(
                        GROOVY_PREFIX,
                        GroovycSpec.write(request),
                        Classpaths.join(request.workerClasspath()),
                        List.of(),
                        json -> WorkerDiagnostics.located(
                                Jsonl.str(json, "sev"),
                                Jsonl.str(json, "file"),
                                Jsonl.longValue(json, "line", 0),
                                Jsonl.longValue(json, "col", 0),
                                Jsonl.str(json, "msg"),
                                null));
        };
    }

    private static CompileResult run(Job job) throws IOException, InterruptedException {
        Path hostJavaHome = JavaHomes.runningJavaHome();
        Fork fork = plan(job, hostJavaHome);
        try {
            List<String> jvmFlags = new ArrayList<>(fork.jvmFlags());
            // Silence the JDK's native-access / Unsafe warnings the compiler triggers.
            jvmFlags.add("--enable-native-access=ALL-UNNAMED");
            // One worker argv assembly for the whole engine (PluginLoader.command); JvmOptions
            // re-heads it with the java binary plus this job's memory flags.
            List<String> assembled = PluginLoader.command(
                    hostJavaHome,
                    fork.classpath(),
                    jvmFlags,
                    List.of("@" + fork.spec().toAbsolutePath()));
            List<String> cmd = JvmOptions.javaCommand(hostJavaHome, 1, assembled.subList(1, assembled.size()));

            List<CompileResult.Diagnostic> diagnostics = new ArrayList<>();
            @Nullable String[] status = {null};
            // Non-protocol lines (JDK/compiler chatter) are dropped on success, but a plugin
            // that DIES before speaking protocol (a broken classpath, a JVM crash) leaves its
            // whole story there — keep a bounded tail and surface it on failure, or the build
            // fails with an empty diagnostic and no way to see why.
            ArrayDeque<String> chatter = new ArrayDeque<>();
            int exit = new PluginClient(fork.prefix())
                    .on(
                            PluginProtocol.DIAGNOSTIC,
                            json -> diagnostics.add(fork.decode().apply(json)))
                    .on(PluginProtocol.RESULT, json -> status[0] = Jsonl.str(json, "status"))
                    .passthrough(line -> {
                        if (chatter.size() >= CHATTER_TAIL) chatter.removeFirst();
                        chatter.addLast(line);
                    })
                    .run(cmd, job.env().withJavaHome(hostJavaHome));
            boolean success = exit == 0 && "COMPILATION_SUCCESS".equals(status[0]);
            if (!success && diagnostics.isEmpty() && !chatter.isEmpty()) {
                StringBuilder tail =
                        new StringBuilder(job.tool() + " worker exited " + exit + " without diagnostics; last output:");
                for (String line : chatter) tail.append('\n').append(line);
                diagnostics.add(
                        new CompileResult.Diagnostic(CompileResult.Severity.ERROR, null, 0, 0, tail.toString()));
            }
            return new CompileResult(success, diagnostics);
        } finally {
            Files.deleteIfExists(fork.spec());
        }
    }
}
