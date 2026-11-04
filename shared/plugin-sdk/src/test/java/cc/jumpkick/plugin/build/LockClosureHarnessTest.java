// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code lock-closure} op: the harness hands a {@link LockExtension} the closure's name, the
 * module's production entries as a step's and its test entries beside them, and answers each file
 * the plugin names as one {@code pin} reply.
 */
class LockClosureHarnessTest {

    /** Answers its closure from what it was handed, so the test reads both lists back. */
    static final class FixturePlugin implements Plugin, BuildExtension, LockExtension {
        @Override
        public PluginManifest manifest() {
            return new PluginManifest("jk-fixture", "##FX:");
        }

        @Override
        public int run(List<String> args, ProtocolWriter out) throws Exception {
            return BuildPluginHarness.run(this, args, out);
        }

        @Override
        public void build(BuildContext ctx) {}

        @Override
        public List<Pin> lockClosure(String name, TaskExec exec, List<PackageIo.RuntimeEntry> testRuntimeEntries)
                throws IOException {
            if (!"deployment".equals(name)) throw new IOException("no closure named " + name);
            List<Pin> files = new ArrayList<>();
            for (PackageIo.RuntimeEntry e : exec.runtimeEntries()) files.add(new Pin(e.gav() + "-deployment", null));
            for (PackageIo.RuntimeEntry e : testRuntimeEntries) files.add(new Pin(e.gav() + "!pom", e.jar()));
            files.add(new Pin(
                    exec.config().id() + ":"
                            + exec.extra("lock-closure.deployment").orElseThrow(),
                    null));
            return files;
        }
    }

    @Test
    void each_file_of_the_closure_is_one_pin_reply(@TempDir Path dir) throws Exception {
        Path seed = Files.createDirectories(dir.resolve("seed"));
        Path spec = new SpecWriter()
                .op(PluginProtocol.OP_LOCK_CLOSURE, "deployment", "fx")
                .layout(dir.resolve("classes"), dir, dir.resolve("scratch"))
                .entry("arc-1.jar", dir.resolve("arc.jar"), false, null, "io.q", "arc", "1")
                .testEntry("junit-6.jar", dir.resolve("junit.jar"), false, null, "org.junit", "junit", "6")
                .extra("lock-closure.deployment", seed)
                .writeTempSpec();
        var out = capture();

        int exit = BuildPluginHarness.run(new FixturePlugin(), List.of(spec.toString()), out.writer());

        assertThat(exit).isZero();
        assertThat(out.pins()).containsExactly("io.q:arc:1-deployment", "org.junit:junit:6!pom", "fx:" + seed);
        assertThat(out.lines())
                .as("a file the resolver holds rides with its path")
                .anyMatch(l ->
                        l.contains("org.junit:junit:6!pom") && l.contains("\"path\":\"" + dir.resolve("junit.jar")));
    }

    @Test
    void a_closure_the_plugin_cannot_answer_fails_the_op(@TempDir Path dir) throws Exception {
        Path spec = new SpecWriter()
                .op(PluginProtocol.OP_LOCK_CLOSURE, "codegen", "fx")
                .layout(dir.resolve("classes"), dir, dir.resolve("scratch"))
                .writeTempSpec();
        var out = capture();

        int exit = BuildPluginHarness.run(new FixturePlugin(), List.of(spec.toString()), out.writer());

        assertThat(exit).isEqualTo(1);
        assertThat(out.lines())
                .anyMatch(
                        l -> l.contains("\"code\":\"lock-closure-failed\"") && l.contains("no closure named codegen"));
        assertThat(out.pins()).isEmpty();
    }

    /** A step never sees the test entries: they ride a spec only for the lock-closure op. */
    @Test
    void test_entries_are_not_a_steps_runtime_entries(@TempDir Path dir) throws Exception {
        Path spec = new SpecWriter()
                .op(PluginProtocol.OP_RUN_STEP, "nothing", "fx")
                .entry("arc-1.jar", dir.resolve("arc.jar"), false, null, "io.q", "arc", "1")
                .testEntry("junit-6.jar", dir.resolve("junit.jar"), false, null, "org.junit", "junit", "6")
                .writeTempSpec();
        BuildPluginHarness.Spec read = BuildPluginHarness.Spec.read(spec);

        assertThat(read.entries()).extracting(PackageIo.RuntimeEntry::gav).containsExactly("io.q:arc:1");
        assertThat(read.testEntries()).extracting(PackageIo.RuntimeEntry::gav).containsExactly("org.junit:junit:6");
    }

    private static Captured capture() {
        var buffer = new ByteArrayOutputStream();
        return new Captured(buffer, new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##T:"));
    }

    private record Captured(ByteArrayOutputStream buffer, ProtocolWriter writer) {
        List<String> lines() {
            return List.of(buffer.toString(StandardCharsets.UTF_8).split("\n"));
        }

        /** The {@code coordinate} of every {@code pin} reply, in order. */
        List<String> pins() {
            List<String> out = new ArrayList<>();
            for (String line : lines()) {
                if (!line.contains("\"t\":\"pin\"")) continue;
                int at = line.indexOf("\"coordinate\":\"") + "\"coordinate\":\"".length();
                out.add(line.substring(at, line.indexOf('"', at)));
            }
            return out;
        }
    }
}
