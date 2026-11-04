// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The Windows Credential Manager, through {@code advapi32}'s {@code CredReadW}, {@code CredWriteW}
 * and {@code CredDeleteW} called in process ({@link Advapi32Credentials}). An item is a generic
 * credential whose target is {@code jk:<item>} and whose user name is {@code jk}; its blob is the
 * secret in UTF-16, the way the Credential Manager UI and {@code cmdkey} show one.
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

    /** {@code CRED_MAX_CREDENTIAL_BLOB_SIZE}: 2560 bytes, so 1280 UTF-16 code units. */
    static final int MAX_BLOB_BYTES = 5 * 512;

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

    @Override
    public boolean daemonReachable() {
        return false;
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
        ReadResult r = calls().read(target(item));
        if (r.error() == ERROR_NOT_FOUND) return Optional.empty();
        byte[] blob = r.blob();
        if (r.error() != 0 || blob == null) {
            throw new SecretBackendException(
                    "the Windows Credential Manager refused to read " + item + ": " + describe(r.error()));
        }
        return Optional.of(new String(blob, StandardCharsets.UTF_16LE));
    }

    @Override
    public void write(String item, String label, String secret) {
        byte[] blob = secret.getBytes(StandardCharsets.UTF_16LE);
        if (blob.length > MAX_BLOB_BYTES) {
            throw new SecretBackendException("the Windows Credential Manager holds at most " + MAX_BLOB_BYTES / 2
                    + " UTF-16 characters, and the secret for " + item + " has " + blob.length / 2);
        }
        int error = calls().write(target(item), label, SecretItems.SERVICE, blob);
        if (error != 0) {
            throw new SecretBackendException(
                    "the Windows Credential Manager refused to store " + item + ": " + describe(error));
        }
    }

    @Override
    public void delete(String item) {
        int error = calls().delete(target(item));
        if (error == 0 || error == ERROR_NOT_FOUND) return;
        throw new SecretBackendException(
                "the Windows Credential Manager refused to remove " + item + ": " + describe(error));
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
