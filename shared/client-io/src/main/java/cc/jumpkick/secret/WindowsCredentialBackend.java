// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import cc.jumpkick.host.Hashing;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The Windows Credential Manager, through {@code advapi32}'s {@code CredReadW}, {@code CredWriteW}
 * and {@code CredDeleteW} called in process ({@link Advapi32Credentials}). An item is a generic
 * credential whose target is {@code jk:<item>} and whose user name is {@code jk}; its blob is the
 * secret in UTF-8. A blob holds at most {@value #MAX_BLOB_BYTES} bytes, so UTF-8 keeps a token
 * (ASCII, as tokens and JWTs are) of up to {@value #MAX_BLOB_BYTES} characters in one credential.
 *
 * <p>A longer secret is split: its UTF-8 bytes go in parts {@code jk:<item>#1} … {@code
 * jk:<item>#n}, and {@code jk:<item>} holds a head naming the count, the length and the SHA-256 of
 * the whole. The head starts with a {@code 0xFF} byte, which no UTF-8 text has, so it can never be
 * mistaken for a secret. The parts are written before the head and the head is checked against
 * them on every read, so a write that stops partway leaves an item that reads as absent or
 * refuses, never a wrong secret. Parts a shorter secret no longer uses are removed after its head
 * is in place, and a delete removes the parts before the head. A secret of more than {@value
 * #MAX_PARTS} parts is refused before any call, and the login keeps it in its record.
 *
 * <p>No child process runs and the secret never leaves this process's memory on its way in or
 * out. A logon session without a credential store ({@code ERROR_NO_SUCH_LOGON_SESSION}, as a
 * service account or a network logon can have) is no keychain here, not a refusal.
 */
public final class WindowsCredentialBackend implements SecretBackend {

    /** {@code ERROR_NOT_FOUND}: no credential has the target. */
    static final int ERROR_NOT_FOUND = 1168;

    /** {@code ERROR_NO_SUCH_LOGON_SESSION}: this logon session has no credential set. */
    static final int ERROR_NO_SUCH_LOGON_SESSION = 1312;

    /** {@code CRED_MAX_CREDENTIAL_BLOB_SIZE}: 2560 bytes. */
    static final int MAX_BLOB_BYTES = 5 * 512;

    /** The parts a split secret may take: {@code 32 * 2560} bytes, far past any token. */
    static final int MAX_PARTS = 32;

    /** The first byte of a split secret's head: never the first byte of UTF-8 text. */
    static final byte HEAD_MARK = (byte) 0xFF;

    /** What a {@code CredReadW} answered: a Win32 error, and the blob when it was 0. */
    record ReadResult(int error, byte @Nullable [] blob) {}

    /** The three Credential Manager calls, each answering a Win32 error (0 is success). */
    interface Calls {
        ReadResult read(String target);

        int write(String target, String comment, String userName, byte[] blob);

        int delete(String target);
    }

    /** The calls, or why there are none. */
    private final Supplier<Advapi32Credentials.Binding> binding;

    /** This machine's Credential Manager. */
    public WindowsCredentialBackend() {
        this(Advapi32Credentials::binding);
    }

    /** Over {@code calls}: tests. */
    WindowsCredentialBackend(Calls calls) {
        this(fixed(calls));
    }

    private WindowsCredentialBackend(Supplier<Advapi32Credentials.Binding> binding) {
        this.binding = binding;
    }

    private static Supplier<Advapi32Credentials.Binding> fixed(Calls calls) {
        Advapi32Credentials.Binding b = new Advapi32Credentials.Binding(Objects.requireNonNull(calls, "calls"), null);
        return () -> b;
    }

    @Override
    public String id() {
        return "windows-credential-manager";
    }

    @Override
    public String displayName() {
        return "Windows Credential Manager";
    }

    /**
     * A read of a target no item has: {@code ERROR_NOT_FOUND} means the credential set answers.
     * It takes microseconds and can show nothing.
     */
    @Override
    public Probe probe() {
        Calls calls = binding.get().calls();
        if (calls == null) {
            return Probe.unavailable(Objects.requireNonNullElse(binding.get().reason(), "advapi32 is not bound"));
        }
        try {
            int error = calls.read(target("probe")).error();
            if (error == 0 || error == ERROR_NOT_FOUND) return Probe.AVAILABLE;
            if (error == ERROR_NO_SUCH_LOGON_SESSION) {
                return Probe.unavailable("this logon session has no credential set (" + describe(error) + ")");
            }
            return Probe.refused(describe(error));
        } catch (SecretBackendException e) {
            return Probe.unavailable(Objects.requireNonNullElse(e.getMessage(), "advapi32 failed"));
        }
    }

    @Override
    public Optional<String> read(String item) {
        Calls calls = calls();
        byte @Nullable [] head = readBlob(calls, target(item), item);
        if (head == null) return Optional.empty();
        Head split = Head.parse(head);
        if (split == null) return Optional.of(new String(head, StandardCharsets.UTF_8));
        byte[] whole = new byte[split.bytes()];
        int at = 0;
        for (int part = 1; part <= split.parts(); part++) {
            byte @Nullable [] blob = readBlob(calls, partTarget(item, part), item);
            if (blob == null || at + blob.length > whole.length) {
                Arrays.fill(whole, (byte) 0);
                throw incomplete(item, split, blob == null ? "part " + part + " is missing" : "the parts are too long");
            }
            System.arraycopy(blob, 0, whole, at, blob.length);
            at += blob.length;
            Arrays.fill(blob, (byte) 0);
        }
        try {
            if (at != whole.length || !MessageDigest.isEqual(Hashing.sha256(whole), split.sha256())) {
                throw incomplete(item, split, "the parts do not match their head");
            }
            return Optional.of(new String(whole, StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(whole, (byte) 0);
        }
    }

    @Override
    public void write(String item, String label, String secret) {
        byte[] whole = secret.getBytes(StandardCharsets.UTF_8);
        try {
            int parts = (whole.length + MAX_BLOB_BYTES - 1) / MAX_BLOB_BYTES;
            if (parts > MAX_PARTS) {
                throw new SecretBackendException("the Windows Credential Manager holds at most " + MAX_PARTS
                        + " credentials of " + MAX_BLOB_BYTES + " bytes for one secret, and the secret for " + item
                        + " is " + whole.length + " bytes in UTF-8");
            }
            Calls calls = calls();
            int stale = partsNamed(calls, item);
            if (parts <= 1) {
                put(calls, target(item), label, whole, item);
                deleteParts(calls, item, 1, stale);
                return;
            }
            // Nothing reads as the item until every part is written and the head names them.
            remove(calls, target(item), item);
            for (int part = 1; part <= parts; part++) {
                int from = (part - 1) * MAX_BLOB_BYTES;
                byte[] blob = Arrays.copyOfRange(whole, from, Math.min(whole.length, from + MAX_BLOB_BYTES));
                try {
                    put(calls, partTarget(item, part), label, blob, item);
                } finally {
                    Arrays.fill(blob, (byte) 0);
                }
            }
            put(calls, target(item), label, new Head(parts, whole.length, Hashing.sha256(whole)).encode(), item);
            deleteParts(calls, item, parts + 1, stale);
        } finally {
            Arrays.fill(whole, (byte) 0);
        }
    }

    @Override
    public void delete(String item) {
        Calls calls = calls();
        deleteParts(calls, item, 1, partsNamed(calls, item));
        remove(calls, target(item), item);
    }

    /**
     * The head of a split secret: how many parts, how many bytes in all, and their SHA-256. Encoded
     * as {@link #HEAD_MARK} and then {@code parts=<n> bytes=<n> sha256=<hex>} in ASCII.
     */
    record Head(int parts, int bytes, byte[] sha256) {

        byte[] encode() {
            String text = "parts=" + parts + " bytes=" + bytes + " sha256=" + Hashing.hex(sha256);
            byte[] ascii = text.getBytes(StandardCharsets.US_ASCII);
            byte[] blob = new byte[ascii.length + 1];
            blob[0] = HEAD_MARK;
            System.arraycopy(ascii, 0, blob, 1, ascii.length);
            return blob;
        }

        /** The head {@code blob} encodes, or null when it is a whole secret. */
        static @Nullable Head parse(byte[] blob) {
            if (blob.length == 0 || blob[0] != HEAD_MARK) return null;
            String text = new String(blob, 1, blob.length - 1, StandardCharsets.US_ASCII);
            String[] fields = text.split(" ");
            try {
                if (fields.length == 3
                        && fields[0].startsWith("parts=")
                        && fields[1].startsWith("bytes=")
                        && fields[2].startsWith("sha256=")) {
                    int parts = Integer.parseInt(fields[0].substring(6));
                    int bytes = Integer.parseInt(fields[1].substring(6));
                    byte[] sha = Hashing.unhex(fields[2].substring(7));
                    if (parts >= 2
                            && parts <= MAX_PARTS
                            && bytes > 0
                            && bytes <= parts * MAX_BLOB_BYTES
                            && sha.length == 32) {
                        return new Head(parts, bytes, sha);
                    }
                }
            } catch (IllegalArgumentException malformed) {
                // Falls through to the refusal below.
            }
            throw new SecretBackendException(
                    "the Windows Credential Manager holds a split secret whose head is" + " unreadable; log in again");
        }
    }

    /** The target of part {@code part} (from 1) of a split secret. */
    static String partTarget(String item, int part) {
        return target(item) + "#" + part;
    }

    /** The blob at {@code target}, or null when there is none. */
    private static byte @Nullable [] readBlob(Calls calls, String target, String item) {
        ReadResult r = calls.read(target);
        if (r.error() == ERROR_NOT_FOUND) return null;
        byte[] blob = r.blob();
        if (r.error() != 0 || blob == null) {
            throw new SecretBackendException(
                    "the Windows Credential Manager refused to read " + item + ": " + describe(r.error()));
        }
        return blob;
    }

    /** How many parts the item's head names; 0 when it has none, holds a whole secret or cannot be read. */
    private static int partsNamed(Calls calls, String item) {
        try {
            byte @Nullable [] head = readBlob(calls, target(item), item);
            if (head == null) return 0;
            Head split = Head.parse(head);
            Arrays.fill(head, (byte) 0);
            return split == null ? 0 : split.parts();
        } catch (SecretBackendException unreadable) {
            return 0;
        }
    }

    /**
     * Remove parts {@code known} down to {@code from}, then any further parts upward from past both
     * until one is not there. Descending first keeps what is left a run that starts at {@code from}
     * or later, so a delete that stops partway leaves what the next one finds.
     */
    private static void deleteParts(Calls calls, String item, int from, int known) {
        for (int part = known; part >= from; part--) remove(calls, partTarget(item, part), item);
        for (int part = Math.max(from, known + 1); part <= MAX_PARTS; part++) {
            if (!remove(calls, partTarget(item, part), item)) return;
        }
    }

    private static void put(Calls calls, String target, String label, byte[] blob, String item) {
        int error = calls.write(target, label, SecretItems.SERVICE, blob);
        if (error != 0) {
            throw new SecretBackendException(
                    "the Windows Credential Manager refused to store " + item + ": " + describe(error));
        }
    }

    /** Remove {@code target}: true when it was there, false when it was not. */
    private static boolean remove(Calls calls, String target, String item) {
        int error = calls.delete(target);
        if (error == 0) return true;
        if (error == ERROR_NOT_FOUND) return false;
        throw new SecretBackendException(
                "the Windows Credential Manager refused to remove " + item + ": " + describe(error));
    }

    private static SecretBackendException incomplete(String item, Head split, String why) {
        return new SecretBackendException("the Windows Credential Manager holds " + item + " split across "
                + split.parts() + " credentials, and " + why + "; log in again");
    }

    /** The credential's target name. */
    static String target(String item) {
        return SecretItems.SERVICE + ":" + SecretItems.requireValid(item);
    }

    /** A Win32 error as {@code Win32 error 1312 (ERROR_NO_SUCH_LOGON_SESSION)}, naming the ones jk meets. */
    static String describe(int error) {
        String name =
                switch (error) {
                    case 5 -> "ERROR_ACCESS_DENIED";
                    case 87 -> "ERROR_INVALID_PARAMETER";
                    case 1004 -> "ERROR_INVALID_FLAGS";
                    case ERROR_NOT_FOUND -> "ERROR_NOT_FOUND";
                    case ERROR_NO_SUCH_LOGON_SESSION -> "ERROR_NO_SUCH_LOGON_SESSION";
                    case 1783 -> "RPC_X_BAD_STUB_DATA";
                    default -> null;
                };
        return "Win32 error " + error + (name == null ? "" : " (" + name + ")");
    }

    private Calls calls() {
        Advapi32Credentials.Binding b = binding.get();
        Calls calls = b.calls();
        if (calls == null) {
            throw new SecretBackendException(
                    "the Windows Credential Manager is not reachable: " + Objects.requireNonNullElse(b.reason(), "?"));
        }
        return calls;
    }
}
