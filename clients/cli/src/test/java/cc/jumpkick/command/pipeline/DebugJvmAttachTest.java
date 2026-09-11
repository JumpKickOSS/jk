// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.SharedTestCache;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk test --debug-jvm=0} forks a test JVM that really listens where the CLI said it would.
 * No IDE: a plain socket to the announced port, the JDWP handshake bytes, the 14-byte echo, then a
 * {@code VirtualMachine.Resume} so the suspended suite runs to its normal green exit.
 */
@Tag("integration")
class DebugJvmAttachTest {

    private static final byte[] HANDSHAKE = "JDWP-Handshake".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern ANNOUNCED = Pattern.compile("Debugger listening on (\\S+):(\\d+)");
    private static final Duration PATIENCE = Duration.ofMinutes(4);

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void a_suspended_test_jvm_answers_the_jdwp_handshake_on_the_announced_port(@TempDir Path dir) throws Exception {
        run("new", "--group", "com.example", "--name", "widget", "--lang", "java", "--no-module", dir.toString());
        try (var tests = Files.walk(dir.resolve("src/test"))) {
            assertThat(tests.filter(p -> p.toString().endsWith(".java")))
                    .as("the scaffold ships a test class for the JVM to run")
                    .isNotEmpty();
        }

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        AtomicInteger exit = new AtomicInteger(Integer.MIN_VALUE);
        Thread jk = Thread.ofPlatform()
                .name("jk-test-debug")
                .start(() -> exit.set(
                        run("test", "-C", dir.toString(), "--cache-dir", SharedTestCache.arg(), "--debug-jvm=0")));
        try {
            Matcher announced = awaitAnnouncement(err, jk);
            String host = announced.group(1);
            int port = Integer.parseInt(announced.group(2));
            assertThat(port).isNotZero();

            try (Socket socket = connectWhenListening(host, port, jk)) {
                socket.setSoTimeout((int) PATIENCE.toMillis());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream());
                out.write(HANDSHAKE);
                out.flush();
                byte[] echo = in.readNBytes(HANDSHAKE.length);
                assertThat(echo).as("the port is a JDWP server").isEqualTo(HANDSHAKE);

                // VirtualMachine (command set 1) / Resume (command 9): let the suspended suite run.
                out.writeInt(11);
                out.writeInt(1);
                out.writeByte(0);
                out.writeByte(1);
                out.writeByte(9);
                out.flush();
                assertThat(in.readInt()).as("reply length").isEqualTo(11);
                assertThat(in.readInt()).as("reply id").isEqualTo(1);
                assertThat(in.readByte() & 0x80).as("reply flag").isEqualTo(0x80);
                assertThat(in.readShort()).as("error code").isZero();
            }

            jk.join(PATIENCE.toMillis());
            assertThat(jk.isAlive()).as("the resumed suite finishes").isFalse();
            assertThat(exit.get())
                    .as("stderr:\n" + err.toString(StandardCharsets.UTF_8))
                    .isZero();
        } finally {
            System.setErr(originalErr);
            jk.interrupt();
        }
    }

    private static Matcher awaitAnnouncement(ByteArrayOutputStream err, Thread jk) throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            Matcher m = ANNOUNCED.matcher(err.toString(StandardCharsets.UTF_8));
            if (m.find()) return m;
            if (!jk.isAlive()) break;
            Thread.sleep(100);
        }
        throw new AssertionError("no debug announcement on stderr:\n" + err.toString(StandardCharsets.UTF_8));
    }

    /** The announcement precedes the fork by a compile, so the first connects are refused. */
    private static Socket connectWhenListening(String host, int port, Thread jk)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline && jk.isAlive()) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), 2_000);
                return socket;
            } catch (ConnectException refused) {
                last = refused;
                socket.close();
                Thread.sleep(200);
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        }
        throw new AssertionError("no JDWP listener came up on " + host + ":" + port, last);
    }
}
