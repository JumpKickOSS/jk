// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.JkDirs;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The OS keychain a test sandbox stands in for. jk's test launcher sets {@value #ENV} on every test
 * JVM, naming a directory in the module's test home; a {@code jk} that JVM runs inherits it, and so
 * does the engine such a {@code jk} starts. With it set, a login's secret goes to an owner-only file
 * in that directory instead of the Secret Service, the macOS Keychain or the Windows Credential
 * Manager, and a backend over the real keychain refuses ({@link #refuse}) with an {@link Error}:
 * a test never reads, writes or deletes the credentials of whoever runs it, and one that tries
 * fails.
 *
 * <p>{@value #OFF} (or an empty value) is the explicit way out, for a suite whose tests are about
 * the real keychain and touch only items they create.
 *
 * <p>Unset, a process whose {@code JK_HOME} is a test home ({@link TestHomes#contains}) is
 * sandboxed all the same, in {@value #DIR} under that home: a test JVM an engine launched before
 * the launcher named the variable, or one a fixture started with a home of its own, never reaches
 * the real keychain either.
 */
public final class KeychainSandbox {

    /** The variable: a directory, or {@value #OFF}. */
    public static final String ENV = "JK_KEYCHAIN_SANDBOX";

    /** The value that leaves the real keychain reachable. */
    public static final String OFF = "off";

    /** The sandbox keychain's directory in a test home. */
    public static final String DIR = "keychain";

    /** Refusals kept between drains; one is enough to fail a class, the rest are the same story. */
    static final int KEPT = 16;

    /**
     * What was refused in this JVM and not yet {@linkplain #drainRefusals drained}: at most {@link
     * #KEPT}, and only ever in a sandboxed process.
     */
    private static final List<String> REFUSED = new ArrayList<>();

    private KeychainSandbox() {}

    /** The sandbox keychain's directory, or {@code null} when this process may use the real keychain. */
    public static @Nullable Path dir() {
        String value = JkDirs.env(ENV);
        if (value != null) {
            String v = value.strip();
            return v.isEmpty() || v.toLowerCase(Locale.ROOT).equals(OFF) ? null : Path.of(v);
        }
        String home = JkDirs.env("JK_HOME");
        if (home == null || home.isBlank()) return null;
        try {
            Path h = Path.of(home.strip());
            return TestHomes.contains(h) ? h.resolve(DIR) : null;
        } catch (InvalidPathException notAPath) {
            return null;
        }
    }

    /**
     * Refuse to reach {@code keychain}, the real one, when this process is sandboxed; do nothing
     * otherwise. The refusal is kept until {@link #drainRefusals}, so a harness can fail the test
     * class even when the code under test swallowed the error.
     *
     * @throws Refused when {@value #ENV} names a directory
     */
    public static void refuse(String keychain) {
        Path dir = dir();
        if (dir == null) return;
        String what = "asked the real " + keychain + " while " + ENV + " is set; this process keeps"
                + " secrets in its sandbox keychain at " + dir + ". A test that means the real keychain"
                + " is tagged live, creates and removes only its own items, and runs in a suite that"
                + " sets " + ENV + " = \"" + OFF + "\"";
        synchronized (REFUSED) {
            if (REFUSED.size() < KEPT) REFUSED.add(what);
        }
        throw new Refused(what);
    }

    /** Every refusal since the last call, oldest first, and forget them. */
    public static List<String> drainRefusals() {
        synchronized (REFUSED) {
            List<String> out = List.copyOf(REFUSED);
            REFUSED.clear();
            return out;
        }
    }

    /**
     * A sandboxed process reached for the real keychain. An {@link Error}, so the {@code catch
     * (SecretBackendException)} and {@code catch (RuntimeException)} that turn a keychain's
     * refusal into a notice do not turn this one into a pass.
     */
    public static final class Refused extends Error {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }
}
