// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner.env;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The test JVM's side of reading the caller's shell: a variable its own environment lacks is
 * fetched once from the broker; listing the names asks for names alone, and an entry's value is
 * fetched when it is read; a name outside a listing is unset without a question.
 */
class EnvDeliveryTest {

    private static final Map<String, String> CALLER = Map.of("JK_DELIVERY_A", "a", "JK_DELIVERY_B", "b");

    private final List<String> questions = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        EnvLookups.deliverThrough(null);
    }

    @Test
    void a_variable_is_fetched_once_and_a_listing_fetches_no_value(@TempDir Path dir) throws Exception {
        try (ServerSocket server = broker(dir)) {
            EnvLookups.deliverThrough(BrokerLink.in(dir));
            assertThat(EnvLookups.getenv("JK_DELIVERY_A")).isEqualTo("a");
            assertThat(EnvLookups.getenv("JK_DELIVERY_A")).isEqualTo("a");
            assertThat(EnvLookups.getenv("JK_DELIVERY_NONE")).isNull();
            assertThat(questions).containsExactly("G JK_DELIVERY_A", "G JK_DELIVERY_NONE");

            Map<String, String> view = EnvLookups.getenv();
            assertThat(view).isSameAs(EnvLookups.getenv());
            assertThat(view.keySet()).contains("JK_DELIVERY_A", "JK_DELIVERY_B");
            assertThat(questions).as("listing asks for names, no value").endsWith("N");
            int before = questions.size();
            assertThat(view.get("JK_DELIVERY_OUTSIDE"))
                    .as("not among the names: unset")
                    .isNull();
            assertThat(questions).hasSize(before);
            for (Map.Entry<String, String> e : view.entrySet()) {
                if (e.getKey().equals("JK_DELIVERY_B")) assertThat(e.getValue()).isEqualTo("b");
            }
            assertThat(questions).as("only the value read was fetched").endsWith("G JK_DELIVERY_B");
        }
    }

    /**
     * The link calls the address the broker's file names, not this JVM's loopback: a test JVM run
     * with {@code -Djava.net.preferIPv6Addresses=true} answers {@code ::1} for that, while the
     * engine's broker listens on {@code 127.0.0.1}. A broker on the other loopback from this JVM's
     * own is reached all the same.
     */
    @Test
    void the_link_calls_the_address_the_broker_named(@TempDir Path dir) throws Exception {
        InetAddress own = InetAddress.getLoopbackAddress();
        InetAddress other = InetAddress.getByName(own instanceof Inet6Address ? "127.0.0.1" : "::1");
        ServerSocket server;
        try {
            server = broker(dir, other);
        } catch (IOException noSuchStack) {
            Assumptions.abort("this host has no " + other.getHostAddress() + " to listen on");
            return;
        }
        try (server) {
            EnvLookups.deliverThrough(BrokerLink.in(dir));
            assertThat(EnvLookups.getenv("JK_DELIVERY_B")).isEqualTo("b");
            assertThat(questions).containsExactly("G JK_DELIVERY_B");
        }
    }

    @Test
    void process_builder_is_repointed_at_the_recorder() throws Exception {
        byte[] bytes;
        try (InputStream in =
                Objects.requireNonNull(Object.class.getResourceAsStream("/java/lang/ProcessBuilder.class"))) {
            bytes = in.readAllBytes();
        }
        byte[] rewritten = EnvProbe.repoint(bytes, EnvProbe.PROCESS_IMPL, "start", Set.of(EnvProbe.START_DESC));
        assertThat(rewritten).as("ProcessBuilder.start calls ProcessImpl.start").isNotNull();
        assertThat(new String(Objects.requireNonNull(rewritten), StandardCharsets.ISO_8859_1))
                .contains("cc/jumpkick/testrunner/env/EnvLookups");
    }

    /**
     * A JDK whose {@code ProcessBuilder} reaches {@code ProcessImpl.start} by another descriptor is
     * left as compiled: the hook does not take, and the engine's check says so ({@code EnvProbe.CHECK}).
     */
    @Test
    void a_process_builder_of_another_shape_is_left_as_compiled() throws Exception {
        byte[] bytes;
        try (InputStream in =
                Objects.requireNonNull(Object.class.getResourceAsStream("/java/lang/ProcessBuilder.class"))) {
            bytes = in.readAllBytes();
        }
        assertThat(EnvProbe.repoint(
                        bytes, EnvProbe.PROCESS_IMPL, "start", Set.of("([Ljava/lang/String;)Ljava/lang/Process;")))
                .isNull();
    }

    /** A broker answering from {@link #CALLER} on loopback, noting each question; its address in {@code dir}. */
    private ServerSocket broker(Path dir) throws IOException {
        return broker(dir, InetAddress.getLoopbackAddress());
    }

    /** As {@link #broker(Path)}, listening on {@code at}. */
    private ServerSocket broker(Path dir, InetAddress at) throws IOException {
        ServerSocket server = new ServerSocket(0, 8, at);
        Files.writeString(
                dir.resolve(BrokerLink.FILE),
                server.getLocalPort() + " token " + server.getInetAddress().getHostAddress() + "\n");
        Thread serving = new Thread(() -> {
            try (Socket s = server.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                BrokerLink.readString(in);
                while (true) {
                    byte op = in.readByte();
                    if (op == BrokerLink.GET) {
                        int n = in.readInt();
                        Map<String, String> found = new LinkedHashMap<>();
                        for (int i = 0; i < n; i++) {
                            String name = BrokerLink.readString(in);
                            questions.add("G " + name);
                            if (CALLER.containsKey(name)) found.put(name, CALLER.get(name));
                        }
                        out.writeInt(found.size());
                        for (var e : found.entrySet()) {
                            BrokerLink.writeString(out, e.getKey());
                            BrokerLink.writeString(out, e.getValue());
                        }
                    } else if (op == BrokerLink.NAMES) {
                        questions.add("N");
                        out.writeInt(CALLER.size());
                        for (String name : CALLER.keySet()) BrokerLink.writeString(out, name);
                    }
                    out.flush();
                }
            } catch (IOException done) {
                // the test closed the server
            }
        });
        serving.setDaemon(true);
        serving.start();
        return server;
    }
}
