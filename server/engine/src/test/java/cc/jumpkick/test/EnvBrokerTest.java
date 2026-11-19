// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.CallerEnv;
import cc.jumpkick.config.RequestShell;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The broker a run's test JVMs fetch the caller's variables through: it admits a JVM that proves
 * the run's token, answers from the caller, records each name it hands over, and records nothing
 * the JVM already holds or the module's scope hides.
 */
class EnvBrokerTest {

    @Test
    void a_jvm_with_the_token_reads_and_the_broker_records_what_it_handed_over(@TempDir Path tmp) throws Exception {
        CallerEnv caller = CallerEnv.of(
                RequestShell.of(Map.of("MUST_BE_TRUE", "true", "SPRING_X", "1", "PATH", "/bin"), Set.of(), true, null),
                null);
        SuiteEnvReads.Policy policy = new SuiteEnvReads.Policy(false, List.of(), Set.of("DECLARED"));
        try (EnvBroker broker = Objects.requireNonNull(EnvBroker.start(tmp, caller, policy))) {
            String[] address =
                    Files.readString(tmp.resolve(EnvBroker.FILE)).trim().split(" ");
            assertThat(address)
                    .as("port, token and the address it bound, which a test JVM calls whatever its own loopback is")
                    .hasSize(3)
                    .endsWith(InetAddress.getLoopbackAddress().getHostAddress());
            try (Socket s = new Socket(InetAddress.getByName(address[2]), Integer.parseInt(address[0]))) {
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                DataInputStream in = new DataInputStream(s.getInputStream());
                write(out, address[1]);
                out.writeByte('G');
                out.writeInt(4);
                for (String n : List.of("MUST_BE_TRUE", "ABSENT", "PATH", "DECLARED")) write(out, n);
                out.flush();
                assertThat(readVars(in)).isEqualTo(Map.of("MUST_BE_TRUE", "true"));
                out.writeByte('N');
                out.flush();
                int n = in.readInt();
                for (int i = 0; i < n; i++) read(in);
            }
            assertThat(broker.record())
                    .as("the names handed over, set or unset, and the listing; never PATH or a declared name")
                    .isEqualTo(new SuiteEnvReads.Record(Set.of("MUST_BE_TRUE", "ABSENT"), true, false));
        }
        assertThat(tmp.resolve(EnvBroker.FILE))
                .as("closed, the address is gone")
                .doesNotExist();
    }

    @Test
    void a_jvm_without_the_token_is_turned_away(@TempDir Path tmp) throws Exception {
        CallerEnv caller = CallerEnv.of(RequestShell.of(Map.of("A", "1"), Set.of(), true, null), null);
        try (EnvBroker broker = Objects.requireNonNull(EnvBroker.start(tmp, caller, SuiteEnvReads.Policy.NONE))) {
            String[] address =
                    Files.readString(tmp.resolve(EnvBroker.FILE)).trim().split(" ");
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(address[0]))) {
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                write(out, "0".repeat(64));
                out.writeByte('A');
                out.flush();
                // The broker closes after the token without reading the 'A': when that byte has
                // reached it unread, its TCP stack resets the connection instead of ending it, on
                // either address family and on main as here. Both mean turned away unanswered; the
                // test JVM's BrokerLink reads either as a broker that cannot answer (unset).
                int answer;
                try {
                    answer = s.getInputStream().read();
                } catch (SocketException reset) {
                    answer = -1;
                }
                assertThat(answer)
                        .as("closed without an answer (end of stream, or a reset)")
                        .isEqualTo(-1);
            }
            assertThat(broker.record().isEmpty()).isTrue();
        }
    }

    @Test
    void a_volatile_name_is_handed_over_and_never_recorded(@TempDir Path tmp) {
        CallerEnv caller = CallerEnv.of(
                RequestShell.of(Map.of("OLDPWD", "/a", "SSH_CONNECTION", "x", "APP", "1"), Set.of(), true, null), null);
        try (EnvBroker broker = Objects.requireNonNull(EnvBroker.start(tmp, caller, SuiteEnvReads.Policy.NONE))) {
            assertThat(broker.values(List.of("OLDPWD", "SSH_CONNECTION", "APP")))
                    .isEqualTo(Map.of("OLDPWD", "/a", "SSH_CONNECTION", "x", "APP", "1"));
            assertThat(broker.record()).isEqualTo(new SuiteEnvReads.Record(Set.of("APP"), false, false));
        }
    }

    @Test
    void a_strict_module_gets_no_broker(@TempDir Path tmp) {
        assertThat(EnvBroker.start(tmp, CallerEnv.of(null, null), new SuiteEnvReads.Policy(true, List.of(), Set.of())))
                .isNull();
    }

    @Test
    void under_a_scope_the_rest_reads_unset_and_is_not_recorded(@TempDir Path tmp) {
        CallerEnv caller =
                CallerEnv.of(RequestShell.of(Map.of("SPRING_X", "1", "AWS_SECRET", "s"), Set.of(), true, null), null);
        try (EnvBroker broker = Objects.requireNonNull(
                EnvBroker.start(tmp, caller, new SuiteEnvReads.Policy(false, List.of("SPRING_*"), Set.of())))) {
            assertThat(broker.values(List.of("SPRING_X", "AWS_SECRET"))).isEqualTo(Map.of("SPRING_X", "1"));
            assertThat(broker.names()).containsExactly("SPRING_X");
            assertThat(broker.all()).isEqualTo(Map.of("SPRING_X", "1"));
            assertThat(broker.record()).isEqualTo(new SuiteEnvReads.Record(Set.of("SPRING_X"), true, true));
        }
    }

    /**
     * The team's cache key seed is never handed over, even to a module that declares it: asked by
     * name it reads unset, and a listing and the whole environment leave it out.
     */
    @Test
    void the_cache_key_seed_is_never_handed_over(@TempDir Path tmp) throws IOException {
        CallerEnv caller = CallerEnv.of(
                RequestShell.of(
                        Map.of("JK_CACHE_KEY_SEED", "made-up-team-seed-0123456789", "APP", "1"), Set.of(), true, null),
                null);
        List<SuiteEnvReads.Policy> policies = List.of(
                SuiteEnvReads.Policy.NONE, new SuiteEnvReads.Policy(false, List.of(), Set.of("JK_CACHE_KEY_SEED")));
        for (int i = 0; i < policies.size(); i++) {
            Path dir = Files.createDirectories(tmp.resolve("run" + i));
            try (EnvBroker broker = Objects.requireNonNull(EnvBroker.start(dir, caller, policies.get(i)))) {
                assertThat(broker.values(List.of("JK_CACHE_KEY_SEED", "APP"))).isEqualTo(Map.of("APP", "1"));
                assertThat(broker.names()).contains("APP").doesNotContain("JK_CACHE_KEY_SEED");
                assertThat(broker.all()).containsEntry("APP", "1").doesNotContainKey("JK_CACHE_KEY_SEED");
                assertThat(broker.record().names()).doesNotContain("JK_CACHE_KEY_SEED");
            }
        }
    }

    private static void write(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String read(DataInputStream in) throws IOException {
        byte[] b = new byte[in.readInt()];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static Map<String, String> readVars(DataInputStream in) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        int n = in.readInt();
        for (int i = 0; i < n; i++) out.put(read(in), read(in));
        return out;
    }
}
