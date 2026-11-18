// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner.env;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The test JVM's line to the engine's broker for the caller's environment: one loopback socket,
 * opened on the first question and held for the JVM's life. The broker's address and the token
 * that admits this JVM sit in a file of the record directory the engine hands the probe, readable
 * by the owner alone; no value is ever written to disk on this side.
 *
 * <p>Questions are answered in turn ({@link #values}, {@link #names}, {@link #all}); a broker that
 * cannot be reached answers nothing, which the probe reads as unset.
 */
final class BrokerLink {

    /** The file in the record directory that names the broker: {@code <port> <token> <address>}. */
    static final String FILE = "broker";

    static final byte GET = 'G';
    static final byte NAMES = 'N';
    static final byte ALL = 'A';

    private final InetAddress address;
    private final int port;
    private final String token;
    private @Nullable Socket socket;
    private @Nullable DataInputStream in;
    private @Nullable DataOutputStream out;
    private boolean broken;

    private BrokerLink(InetAddress address, int port, String token) {
        this.address = address;
        this.port = port;
        this.token = token;
    }

    /** The link the engine set up in {@code recordDir}, or null when it set none up (a strict module). */
    static @Nullable BrokerLink in(Path recordDir) {
        try {
            Path file = recordDir.resolve(FILE);
            if (!Files.isRegularFile(file)) return null;
            String[] cells =
                    Files.readString(file, StandardCharsets.UTF_8).trim().split(" ");
            if (cells.length != 3) return null;
            // A literal address: no name is looked up.
            return new BrokerLink(InetAddress.getByName(cells[2]), Integer.parseInt(cells[0]), cells[1]);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Each of {@code names} the caller's shell sets, with its value; null when the broker cannot answer. */
    synchronized @Nullable Map<String, String> values(Collection<String> names) {
        try {
            DataOutputStream o = open();
            o.writeByte(GET);
            o.writeInt(names.size());
            for (String n : names) writeString(o, n);
            o.flush();
            DataInputStream i = input();
            int n = i.readInt();
            Map<String, String> found = new LinkedHashMap<>();
            for (int k = 0; k < n; k++) {
                String name = readString(i);
                found.put(name, readString(i));
            }
            return found;
        } catch (IOException | RuntimeException e) {
            close();
            return null;
        }
    }

    /** The names the caller's shell sets, as the module may see them; null when the broker cannot answer. */
    synchronized @Nullable Set<String> names() {
        try {
            DataOutputStream o = open();
            o.writeByte(NAMES);
            o.flush();
            DataInputStream i = input();
            int n = i.readInt();
            Set<String> names = new LinkedHashSet<>();
            for (int k = 0; k < n; k++) names.add(readString(i));
            return names;
        } catch (IOException | RuntimeException e) {
            close();
            return null;
        }
    }

    /** Every variable of the caller's shell the module may see; null when the broker cannot answer. */
    synchronized @Nullable Map<String, String> all() {
        try {
            DataOutputStream o = open();
            o.writeByte(ALL);
            o.flush();
            DataInputStream i = input();
            int n = i.readInt();
            Map<String, String> vars = new LinkedHashMap<>();
            for (int k = 0; k < n; k++) {
                String name = readString(i);
                vars.put(name, readString(i));
            }
            return vars;
        } catch (IOException | RuntimeException e) {
            close();
            return null;
        }
    }

    private DataOutputStream open() throws IOException {
        if (broken) throw new IOException("the broker went away");
        DataOutputStream o = out;
        if (o != null) return o;
        Socket s = new Socket(address, port);
        s.setTcpNoDelay(true);
        socket = s;
        in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        o = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        out = o;
        writeString(o, token);
        return o;
    }

    private DataInputStream input() throws IOException {
        DataInputStream i = in;
        if (i == null) throw new IOException("not connected");
        return i;
    }

    private void close() {
        broken = true;
        Socket s = socket;
        socket = null;
        in = null;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing a socket that failed
            }
        }
    }

    static void writeString(DataOutputStream o, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.writeInt(b.length);
        o.write(b);
    }

    static String readString(DataInputStream i) throws IOException {
        int n = i.readInt();
        if (n < 0 || n > (64 << 20)) throw new IOException("a string of " + n + " bytes");
        byte[] b = new byte[n];
        i.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
