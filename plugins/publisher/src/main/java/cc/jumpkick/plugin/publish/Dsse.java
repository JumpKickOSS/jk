// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * A <a href="https://github.com/secure-systems-lab/dsse/blob/master/envelope.md">DSSE</a> envelope
 * over an in-toto statement, written as one JSON line: the base64 {@code payload}, its {@code
 * payloadType}, and one signature whose {@code sig} is a binary OpenPGP signature over the
 * pre-authentication encoding and whose {@code keyid} is the signing key's fingerprint.
 */
public final class Dsse {

    /** The payload type of an in-toto statement. */
    public static final String IN_TOTO = "application/vnd.in-toto+json";

    private Dsse() {}

    /** The envelope over {@code statement}, signed by {@code signer}, newline-terminated. */
    public static byte[] envelope(byte[] statement, GpgSigner signer) throws IOException {
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(signer, "signer");
        Base64.Encoder base64 = Base64.getEncoder();
        String signature = JsonFields.object()
                .string("keyid", signer.fingerprint())
                .string("sig", base64.encodeToString(signer.signBinary(pae(IN_TOTO, statement))))
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
