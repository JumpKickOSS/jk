// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.CallerEnv;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.OwnerOnlyFiles;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The engine's end of a suite run's line to the caller's environment: the test JVMs of one
 * run-tests step ask it for the variables their own environment lacks, and it answers from the
 * caller ({@link CallerEnv}: what the request carried, else the client asked on the job's
 * connection) and notes every name it hands over. That note is the suite's {@link
 * SuiteEnvReads.Record}: delivery and recording are one act, so nothing is read unrecorded.
 *
 * <p>It listens on loopback, and admits a JVM that proves the run's token, which sits beside the
 * port and the address it bound in the probe's record directory, readable by the owner alone
 * ({@link #FILE}). A name the
 * JVM already holds when set (allow-listed or declared) and a name outside the module's {@code
 * [env] scope} are answered unset and not recorded: neither depends on the caller's shell. Values
 * pass through and are never kept here, logged or written.
 */
public final class EnvBroker implements AutoCloseable {

    /** The file in the probe's record directory naming the broker ({@code BrokerLink.FILE}). */
    public static final String FILE = "broker";

    private static final byte GET = 'G';
    private static final byte NAMES = 'N';
    private static final byte ALL = 'A';

    /** The most names one question may ask for: far more than any environment holds. */
    private static final int MAX_NAMES = 65_536;

    private final ServerSocket server;
    private final byte[] token;
    private final Path file;
    private final CallerEnv caller;
    private final SuiteEnvReads.Policy policy;
    private final Set<String> read = ConcurrentHashMap.newKeySet();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private volatile boolean listed;
    private volatile boolean whole;

    private EnvBroker(ServerSocket server, byte[] token, Path file, CallerEnv caller, SuiteEnvReads.Policy policy) {
        this.server = server;
        this.token = token;
        this.file = file;
        this.caller = caller;
        this.policy = policy;
    }

    /**
     * A broker for a run whose probe records into {@code recordDir}, answering from {@code caller}
     * under {@code policy}; null for a strict module, or when it cannot listen (the JVMs then see
     * the allow-list alone, as a strict module's do).
     */
    public static @Nullable EnvBroker start(Path recordDir, CallerEnv caller, SuiteEnvReads.Policy policy) {
        if (policy.strict()) return null;
        try {
            Files.createDirectories(recordDir);
            ServerSocket server = new ServerSocket(0, 64, InetAddress.getLoopbackAddress());
            byte[] token = new byte[32];
            new SecureRandom().nextBytes(token);
            Path file = recordDir.resolve(FILE);
            // The address it bound, not the JVM's loopback: a test JVM that prefers IPv6 answers ::1 for that.
            String line = server.getLocalPort() + " " + Hashing.hex(token) + " "
                    + server.getInetAddress().getHostAddress() + "\n";
            OwnerOnlyFiles.write(recordDir, file, line);
            EnvBroker broker = new EnvBroker(server, token, file, caller, policy);
            SessionContext.startVirtual("jk-env-broker", broker::accept);
            return broker;
        } catch (IOException | RuntimeException e) {
            Log.debug("env broker: the suite runs with the allow-list alone", e);
            return null;
        }
    }

    /** The loopback port the broker listens on. */
    public int port() {
        return server.getLocalPort();
    }

    /** What the run's JVMs were handed of the caller's environment so far. */
    public SuiteEnvReads.Record record() {
        return new SuiteEnvReads.Record(new TreeSet<>(read), listed, whole);
    }

    /** Whether a question went to a client that had gone: what the tests saw then, no key can say. */
    public boolean lost() {
        return caller.lost();
    }

    /** Whether the run has a caller at all: a client sent it. */
    public boolean attached() {
        return caller.attached();
    }

    private void accept() {
        while (!server.isClosed()) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException closed) {
                return;
            }
            open.add(s);
            SessionContext.startVirtual("jk-env-broker-jvm", () -> serve(s));
        }
    }

    private void serve(Socket s) {
        try (s) {
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            byte[] proof = readString(in).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(proof, Hashing.hex(token).getBytes(StandardCharsets.UTF_8))) return;
            while (true) {
                byte op;
                try {
                    op = in.readByte();
                } catch (EOFException done) {
                    return;
                }
                switch (op) {
                    case GET -> {
                        int n = in.readInt();
                        if (n < 0 || n > MAX_NAMES) throw new IOException("a question for " + n + " names");
                        List<String> names = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) names.add(readString(in));
                        writeVars(out, values(names));
                    }
                    case NAMES -> {
                        Set<String> names = names();
                        out.writeInt(names.size());
                        for (String name : names) writeString(out, name);
                    }
                    case ALL -> writeVars(out, all());
                    default -> {
                        return;
                    }
                }
                out.flush();
            }
        } catch (IOException | RuntimeException gone) {
            // a JVM that exits mid-question, or one that never proved itself
        } finally {
            open.remove(s);
        }
    }

    /** The caller's values for {@code names} a JVM lacks: each one the caller answers for is recorded. */
    Map<String, String> values(List<String> names) {
        List<String> asked = new ArrayList<>();
        for (String name : names) {
            if (!policy.held(name) && policy.visible(name)) asked.add(name);
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (asked.isEmpty()) return out;
        Map<String, CallerEnv.Value> values = caller.values(asked);
        for (String name : asked) {
            // A volatile name (the terminal, the login, the shell's cwd) is handed over and never
            // recorded: a framework that reads every value would otherwise key the suite on the
            // terminal it ran in, and a second terminal would never take its result.
            if (!SuiteEnvReads.volatileName(name)) read.add(SuiteEnvReads.key(name));
            // Unknown (no caller to answer) reads as unset; the key says unknown, so no build with a
            // caller takes the result.
            CallerEnv.Value v = values.getOrDefault(name, CallerEnv.Value.UNKNOWN);
            String value = v.value();
            if (v.state() == CallerEnv.State.SET && value != null) out.put(name, value);
        }
        return out;
    }

    /** The names of the caller's shell a JVM may see; recorded as a listing. */
    Set<String> names() {
        listed = true;
        Set<String> names = caller.names();
        if (names == null) return Set.of();
        Set<String> out = new TreeSet<>();
        for (String name : names) {
            if (policy.visible(name) || policy.held(name)) out.add(name);
        }
        return out;
    }

    /** The caller's whole environment as a JVM may see it, for a program it starts; recorded as such. */
    Map<String, String> all() {
        whole = true;
        Map<String, String> all = caller.all();
        if (all == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        all.forEach((name, value) -> {
            if (policy.visible(name) || policy.held(name)) out.put(name, value);
        });
        return out;
    }

    private static void writeVars(DataOutputStream out, Map<String, String> vars) throws IOException {
        out.writeInt(vars.size());
        for (Map.Entry<String, String> e : vars.entrySet()) {
            writeString(out, e.getKey());
            writeString(out, e.getValue());
        }
    }

    private static void writeString(DataOutputStream o, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.writeInt(b.length);
        o.write(b);
    }

    private static String readString(DataInputStream i) throws IOException {
        int n = i.readInt();
        if (n < 0 || n > (64 << 20)) throw new IOException("a string of " + n + " bytes");
        byte[] b = new byte[n];
        i.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Stop listening, drop every JVM's line, and take the address file away. */
    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // already closed
        }
        for (Socket s : open) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the record directory is emptied before the next run
        }
    }
}
