// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A <a href="https://github.com/secure-systems-lab/dsse/blob/master/envelope.md">DSSE</a> envelope
 * over an in-toto statement, written as one JSON line: the base64 {@code payload}, its {@code
 * payloadType}, and one signature over the pre-authentication encoding: a binary OpenPGP signature
 * whose {@code keyid} is the signing key's fingerprint, or a keyless Sigstore one with no {@code
 * keyid}.
 */
public final class Dsse {

    /** The payload type of an in-toto statement. */
    public static final String IN_TOTO = "application/vnd.in-toto+json";

    private Dsse() {}

    /** The envelope over {@code statement}, signed by {@code signer}, newline-terminated. */
    public static byte[] envelope(byte[] statement, GpgSigner signer) throws IOException {
        Objects.requireNonNull(statement, "statement");
        return envelope(statement, signer.fingerprint(), signer.signBinary(pae(IN_TOTO, statement)));
    }

    /**
     * The envelope over {@code statement} carrying {@code sig}, a signature over {@link #pae} of the
     * statement as {@link #IN_TOTO}; a {@code null} {@code keyid} is left out.
     */
    static byte[] envelope(byte[] statement, @Nullable String keyid, byte[] sig) {
        Base64.Encoder base64 = Base64.getEncoder();
        String signature = JsonFields.object()
                .optionalString("keyid", keyid)
                .string("sig", base64.encodeToString(sig))
                .finish();
        String line = JsonFields.object()
                .string("payloadType", IN_TOTO)
                .string("payload", base64.encodeToString(statement))
                .token("signatures", "[" + signature + "]")
                .finish();
        return (line + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The bytes a DSSE signature covers: {@code DSSEv1 <len(type)> <type> <len(payload)> <payload>},
     * each length the byte count in ASCII decimal, the fields separated by one space.
     */
    static byte[] pae(String payloadType, byte[] payload) {
        byte[] type = payloadType.getBytes(StandardCharsets.UTF_8);
        byte[] head = ("DSSEv1 " + type.length + " " + payloadType + " " + payload.length + " ")
                .getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + payload.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(payload, 0, out, head.length, payload.length);
        return out;
    }

    /**
     * Whether two envelopes carry the same payload type and payload, whoever signed them and when.
     * Bytes that are not an envelope carry neither.
     */
    static boolean samePayload(byte[] a, byte[] b) {
        String left = new String(a, StandardCharsets.UTF_8);
        String right = new String(b, StandardCharsets.UTF_8);
        String payload = Jsonl.str(left, "payload");
        String type = Jsonl.str(left, "payloadType");
        return payload != null
                && type != null
                && payload.equals(Jsonl.str(right, "payload"))
                && type.equals(Jsonl.str(right, "payloadType"));
    }
}
