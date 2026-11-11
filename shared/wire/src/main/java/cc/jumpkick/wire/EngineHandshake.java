// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire;

import cc.jumpkick.jsonl.BoundedLineReader;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.AuthAckFrame;
import cc.jumpkick.wire.protocol.AuthFrame;
import cc.jumpkick.wire.protocol.AuthProofFrame;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * The loopback-TCP handshake, which each side passes only by holding the engine's token. The client
 * sends {@link EngineProtocol#AUTH} with a fresh nonce; the engine answers {@link
 * EngineProtocol#AUTH_ACK} with its own nonce and {@code HMAC-SHA256(token, "jk-engine" n_c n_e)};
 * the client checks that proof and only then sends {@link EngineProtocol#AUTH_PROOF} with {@code
 * HMAC-SHA256(token, "jk-client" n_c n_e)}. The token never crosses the wire, and a process that
 * took the port after the engine died never sees a request, the shell environment or a secret.
 */
public final class EngineHandshake {

    private EngineHandshake() {}

    /** How long the client waits for the engine's {@link EngineProtocol#AUTH_ACK}. */
    public static final long ACK_TIMEOUT_MILLIS = 10_000;

    private static final String ENGINE_LABEL = "jk-engine";
    private static final String CLIENT_LABEL = "jk-client";

    /** A nonce longer than this is not one a client of this engine sent. */
    private static final int MAX_NONCE = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Client side, on a freshly connected {@code ch}: run the handshake and return once the engine has
     * proved it holds {@code token} and the client's proof is written. An engine that refuses, answers
     * anything else or fails its proof is an {@link IOException}, and nothing more was written.
     */
    public static void client(SocketChannel ch, String token, long ackTimeoutMillis) throws IOException {
        BufferedWriter w =
                new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
        String clientNonce = nonce();
        w.write(new AuthFrame(clientNonce).encode());
        w.write('\n');
        w.flush();
        // Not closed: closing the reader closes the channel. The engine writes nothing after the ack
        // until the client's next line, so this reader buffers nothing a later one needs.
        @SuppressWarnings("resource")
        BoundedLineReader r = new BoundedLineReader(
                new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8),
                ch,
                ackTimeoutMillis,
                4 * 1024);
        String ack = r.readLine();
        if (ack == null) throw new IOException("the engine closed the connection during the handshake");
        String type = EngineProtocol.typeOf(ack);
        if (EngineProtocol.ERROR.equals(type)) {
            throw new IOException("the engine refused the handshake: " + Jsonl.str(ack, "message"));
        }
        String engineNonce = EngineProtocol.AUTH_ACK.equals(type) ? Jsonl.str(ack, "nonce") : null;
        String proof = EngineProtocol.AUTH_ACK.equals(type) ? Jsonl.str(ack, "proof") : null;
        if (engineNonce == null
                || proof == null
                || !matches(proof, mac(token, ENGINE_LABEL, clientNonce, engineNonce))) {
            throw new IOException("the process on the engine port did not prove it is the engine");
        }
        w.write(new AuthProofFrame(mac(token, CLIENT_LABEL, clientNonce, engineNonce)).encode());
        w.write('\n');
        w.flush();
    }

    /**
     * Engine side: the reply to a client's {@code authLine}, or {@code null} when it is not a
     * well-formed {@link EngineProtocol#AUTH}. Answer with {@link #ack()}, then hand the client's next
     * line to {@link #accepts}.
     */
    public static @Nullable Challenge challenge(String token, @Nullable String authLine) {
        if (authLine == null || !EngineProtocol.AUTH.equals(EngineProtocol.typeOf(authLine))) return null;
        String clientNonce = Jsonl.str(authLine, "nonce");
        if (clientNonce == null || clientNonce.isEmpty() || clientNonce.length() > MAX_NONCE) return null;
        return new Challenge(token, clientNonce, nonce());
    }

    /** One engine-side handshake in progress. */
    public static final class Challenge {
        private final String token;
        private final String clientNonce;
        private final String engineNonce;

        private Challenge(String token, String clientNonce, String engineNonce) {
            this.token = token;
            this.clientNonce = clientNonce;
            this.engineNonce = engineNonce;
        }

        /** The {@link EngineProtocol#AUTH_ACK} line proving this engine holds the token. */
        public String ack() {
            return new AuthAckFrame(engineNonce, mac(token, ENGINE_LABEL, clientNonce, engineNonce)).encode();
        }

        /** Whether {@code proofLine} is the client's {@link EngineProtocol#AUTH_PROOF} for this exchange. */
        public boolean accepts(@Nullable String proofLine) {
            if (proofLine == null || !EngineProtocol.AUTH_PROOF.equals(EngineProtocol.typeOf(proofLine))) return false;
            String proof = Jsonl.str(proofLine, "proof");
            return proof != null && matches(proof, mac(token, CLIENT_LABEL, clientNonce, engineNonce));
        }
    }

    private static String nonce() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static boolean matches(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    static String mac(String token, String label, String clientNonce, String engineNonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out =
                    mac.doFinal((label + '\n' + clientNonce + '\n' + engineNonce).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
