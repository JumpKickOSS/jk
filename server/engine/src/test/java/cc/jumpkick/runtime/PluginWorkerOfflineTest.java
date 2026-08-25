// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --offline} reaches a forked plugin worker.
 *
 * <p>The engine has always put the flag on the wire and bound it into the request's
 * {@link Session}; what it never did was hand it to the worker, and a worker JVM cannot see the
 * engine's {@code SessionContext}. So these tests assert on what a <em>separate process</em> read
 * back out of the spec it was launched with — see {@link OfflineEchoWorker} — rather than on what
 * the engine wrote. Only the second of those two claims was ever true.
 *
 * <p>{@code PluginLaunch.javaCommand} is the single fork choke point for every plugin worker
 * (build steps, packagers, plugin commands, format, audit, publish, image), which is why the stamp
 * lives there and not at the ten places a spec is assembled: a plan cannot fork without it.
 *
 * <p>Each case asserts the decoded value <em>and</em> whether the spec stated a policy at all. Only
 * the second distinguishes the stamp from the fail-closed default: with the stamp removed an
 * offline job still decodes as offline, so a value-only assertion passes on a build that has the
 * defect — verified by reverting it.
 */
class PluginWorkerOfflineTest {

    @Test
    void a_forked_worker_reads_an_offline_run_off_its_spec(@TempDir Path dir) throws Exception {
        assertThat(whatTheWorkerSaw(dir, "offline", session(true))).isEqualTo("offline=true stated=true");
    }

    @Test
    void a_forked_worker_reads_an_online_run_off_its_spec(@TempDir Path dir) throws Exception {
        assertThat(whatTheWorkerSaw(dir, "online", session(false))).isEqualTo("offline=false stated=true");
    }

    /**
     * The complement of the two above, and the reason the stamp is safe to centralise: a spec that
     * never passed through the launcher carries no policy, and a worker that is told nothing
     * refuses to reach out rather than assuming it may.
     */
    @Test
    void a_spec_that_never_reached_the_launcher_is_offline(@TempDir Path dir) throws Exception {
        Path spec = writeSpec(dir.resolve("unstamped.spec"));
        assertThat(Files.readString(spec)).doesNotContain(PluginProtocol.OFFLINE);
        assertThat(runEchoWorker(spec)).isEqualTo("offline=true stated=false");
    }

    /** The launcher stamps the very file it then names on the command line — not a copy of it. */
    @Test
    void the_command_names_the_spec_the_launcher_stamped(@TempDir Path dir) throws Exception {
        Path spec = writeSpec(dir.resolve("named.spec"));
        List<String> command = SessionContext.where(session(true), () -> PluginLaunch.javaCommand(fakeJar(dir), spec));
        assertThat(command.getLast()).isEqualTo(spec.toAbsolutePath().toString());
    }

    private static String whatTheWorkerSaw(Path dir, String label, Session session) throws Exception {
        Path spec = writeSpec(dir.resolve(label + ".spec"));
        // Production path: the launcher is what puts the policy on the spec.
        SessionContext.where(session, () -> PluginLaunch.javaCommand(fakeJar(dir), spec));
        return runEchoWorker(spec);
    }

    /** Fork a real JVM that decodes {@code spec} with the production reader and reports one line. */
    private static String runEchoWorker(Path spec) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                JdkFingerprint.java(JavaHomes.runningJavaHome()).toString(),
                "-cp",
                System.getProperty("java.class.path"),
                OfflineEchoWorker.class.getName(),
                spec.toAbsolutePath().toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor())
                .describedAs("echo worker exit (%s)", output)
                .isZero();
        return output.strip();
    }

    private static Session session(boolean offline) {
        return Session.defaults().withConfig(JkConfig.empty().withOffline(offline));
    }

    private static Path writeSpec(Path spec) throws IOException {
        return Files.write(
                spec,
                new SpecWriter().op(PluginProtocol.OP_RUN_STEP, "probe", "fx").lines(),
                StandardCharsets.UTF_8);
    }

    /**
     * A stand-in worker jar, shaped as a CAS blob so {@code WorkerLaunchClasspath} treats the file
     * as its own whole classpath and looks for no sibling POM. Nothing in it is executed — the
     * fork under test is {@link #runEchoWorker}; this only has to be something the launcher will
     * build a command line around.
     */
    private static Path fakeJar(Path dir) throws IOException {
        Path jar = dir.resolve("sha256").resolve("ab").resolve("cd").resolve("0".repeat(60));
        if (!Files.exists(jar)) {
            Files.createDirectories(jar.getParent());
            Files.writeString(jar, "a plugin worker jar's stand-in");
        }
        return jar;
    }
}
