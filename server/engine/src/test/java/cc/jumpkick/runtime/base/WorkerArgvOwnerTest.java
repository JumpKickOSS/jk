// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.ForkedJavac;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One worker argv assembly in the engine, and it is {@link PluginLoader#command}.
 *
 * <p>There were three. {@code PluginLaunch} open-coded the same six elements next to a comment
 * reading "Reuse PluginLoader.command shape", and {@code WorkerCompileDriver} open-coded them a
 * third time with its own private {@code WORKER_MAIN} constant. The two copies agreed with the
 * owner by inspection only, which is the state every drift in this tree started from.
 *
 * <p>Each case below re-derives the expected tail from the owner rather than restating it, so
 * changing the owner's ordering moves every fork site: reorder {@code PluginLoader.command} and
 * {@link #the_owner_pins_the_order} fails; re-open a private copy at a fork site and that site's
 * case fails while the owner's stays green. That is the difference between a test of the owner and
 * a test of ownership.
 */
class WorkerArgvOwnerTest {

    private static final String CP = "/tmp/worker.jar:/tmp/dep.jar";

    /** The owner's contract, stated once. Every other case derives its expectation from it. */
    @Test
    void the_owner_pins_the_order(@TempDir Path tmp) {
        // The owner is named a JDK HOME, not a launcher: it heads the argv with that home's own
        // java through JdkFingerprint, and the worker's JAVA_HOME is the same home. Both are
        // Paths, so nothing but this case distinguishes a home from a launcher at the call.
        Path javaHome = tmp.resolve("jdk-25");
        String launcher = JdkFingerprint.java(javaHome).toString();

        assertThat(PluginLoader.command(javaHome, CP, List.of("-Xmx1g"), List.of("@spec")))
                .containsExactly(launcher, "-Xmx1g", "-cp", CP, PluginLoader.WORKER_MAIN, "@spec");
        // The main-class arm exists for a third-party jar that declares its own entry point; it
        // substitutes exactly one element and moves nothing else.
        assertThat(PluginLoader.command(javaHome, CP, List.of("-Xmx1g"), "vendor.Main", List.of("@spec")))
                .containsExactly(launcher, "-Xmx1g", "-cp", CP, "vendor.Main", "@spec");
    }

    /**
     * The generic plugin fork — build steps, packagers, plugin commands, format, audit, publish,
     * image. It adds a heap plan around the owner's argv and nothing else, so everything from
     * {@code -cp} onwards must be the owner's output verbatim.
     */
    @Test
    void the_generic_plugin_fork_uses_the_owner(@TempDir Path tmp) throws Exception {
        Path spec = writeSpec(tmp.resolve("step.spec"));
        Path jar = fakeWorkerJar(tmp);

        List<String> argv = PluginLaunch.javaCommand(jar, List.of("-Dprobe=1"), spec);

        assertOwnerTail(
                argv,
                jar.toAbsolutePath().toString(),
                List.of(spec.toAbsolutePath().toString()));
        // The launcher's own contribution: the extra JVM arg it was handed, ahead of -cp.
        assertThat(argv.subList(0, argv.indexOf("-cp"))).contains("-Dprobe=1");
    }

    /** The prefix-naming overload is the same fork with one more {@code -D}, not another assembly. */
    @Test
    void the_prefix_naming_fork_uses_the_owner(@TempDir Path tmp) throws Exception {
        Path spec = writeSpec(tmp.resolve("prefixed.spec"));
        Path jar = fakeWorkerJar(tmp);

        List<String> argv = PluginLaunch.javaCommand(jar, spec, "##JKSB:");

        assertOwnerTail(
                argv,
                jar.toAbsolutePath().toString(),
                List.of(spec.toAbsolutePath().toString()));
        assertThat(argv).contains("-Djk.plugin.prefix=##JKSB:");
    }

    /**
     * The compiler family forks through {@code PluginLoader.command} directly rather than through
     * {@code PluginLaunch} (it needs the AOT flags and the spec is an {@code @file}). Same owner,
     * so the same tail.
     */
    @Test
    void the_java_compiler_aot_trainer_uses_the_owner(@TempDir Path tmp) throws Exception {
        Path scratch = Files.createDirectories(tmp.resolve("scratch"));

        List<String> argv =
                ForkedJavac.trainerCommand(tmp.resolve("jdk"), CP, tmp.resolve("worker.aot"), scratch, 25, List.of());

        assertOwnerTail(argv, CP, List.of("@" + scratch.resolve("train.spec").toAbsolutePath()));
    }

    /** The formatter worker's AOT trainer — the third module in the fork family. */
    @Test
    void the_formatter_aot_trainer_uses_the_owner(@TempDir Path tmp) throws Exception {
        Path scratch = Files.createDirectories(tmp.resolve("fmt"));

        List<String> argv = FormatPlans.trainerCommand(
                tmp.resolve("jdk"),
                CP,
                tmp.resolve("fmt.aot"),
                scratch,
                "palantir",
                "kotlinlang",
                List.of(tmp.resolve("palantir.jar")),
                List.of(),
                List.of(tmp.resolve("ktfmt.jar")),
                List.of(),
                false,
                true,
                true,
                true);

        assertOwnerTail(
                argv, CP, List.of(scratch.resolve("train.spec").toAbsolutePath().toString()));
    }

    /**
     * From {@code -cp} onwards, {@code argv} must equal what the owner produces — classpath, then
     * the worker main class, then the args, then nothing. Derived from
     * {@link PluginLoader#command} so a change there is a change here.
     */
    private static void assertOwnerTail(List<String> argv, String classpath, List<String> args) {
        int cp = argv.indexOf("-cp");
        assertThat(cp).describedAs("no -cp in %s", argv).isNotNegative();
        // Any home: only the tail from -cp onwards is compared, never the launcher it heads with.
        List<String> owned = PluginLoader.command(Path.of("any-jdk-home"), classpath, List.of(), args);
        assertThat(argv.subList(cp, argv.size())).containsExactlyElementsOf(owned.subList(1, owned.size()));
    }

    private static Path writeSpec(Path spec) throws IOException {
        return Files.write(
                spec,
                new SpecWriter().op(PluginProtocol.OP_RUN_STEP, "probe", "fx").lines(),
                StandardCharsets.UTF_8);
    }

    /**
     * A CAS-shaped stand-in so {@code WorkerLaunchClasspath} treats the file as its whole classpath
     * and looks for no sibling POM, and so {@code PluginLaunch} finds no {@code Main-Class} and
     * falls back to the SDK host. Nothing is executed — only the argv is under test.
     */
    private static Path fakeWorkerJar(Path dir) throws IOException {
        Path jar = dir.resolve("sha256").resolve("ab").resolve("cd").resolve("0".repeat(60));
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "a plugin worker jar's stand-in");
        return jar;
    }
}
