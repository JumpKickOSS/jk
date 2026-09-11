// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.ToolchainSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Stamps lock {@code [jdk]} / {@code [graal]} from the manifest's declaration and the toolchain
 * that actually resolved at lock time.
 *
 * <p>{@code [jdk]} is always written. The table is a record of what built the lock, and its
 * suggested version is a floor on the major only — so naming the host's JVM constrains a later
 * build to the same major, not to the same install. The stamp is safe to write unconditionally
 * because no consumer reads the table as a hard pin: a pin has to say {@code required-*}, and only
 * an {@code =} in the manifest writes one.
 *
 * <p>{@code [graal]} is written when the Java home is itself a GraalVM, or when the project asked
 * for Graal via {@code [native]}. A bare {@code [native]} declares no vendor, so whichever GraalVM
 * distribution resolves is the one recorded — as a suggestion, which is all it is.
 *
 * <p>{@code previous} is the lock being replaced, passed by every re-lock but {@code jk update}.
 * It keeps an undeclared suggestion stable across a plain {@code jk lock}: the record of what built
 * the lock should not move because a colleague ran it on a different JDK. An unknown-vendor
 * suggestion is not kept — it cannot be installed and would fail the next build after the
 * manifest pin is gone. {@code jk update} passes null, and the suggestion refreshes.
 */
public final class ToolchainLockStamp {

    private ToolchainLockStamp() {}

    public static Lockfile apply(
            Lockfile lock,
            @Nullable Lockfile previous,
            Path javaHome,
            JdkRegistry registry,
            ToolchainSpec jdkSpec,
            ToolchainSpec graalSpec,
            boolean graalDeclared) {
        if (lock == null) return null;
        ToolchainSpec jdk = jdkSpec == null ? ToolchainSpec.NONE : jdkSpec;
        ToolchainSpec graal = graalSpec == null ? ToolchainSpec.NONE : graalSpec;
        List<JdkHit> hits = registry.listHits();
        JdkHit javaHit = LockPinMatch.hitFor(javaHome, hits).orElse(null);

        Lockfile.JdkPin jdkPin = LockPinMatch.jdkPin(jdk, javaHit, previous == null ? null : previous.jdk());
        if (!jdkPin.isEmpty()) lock = lock.withJdk(jdkPin);

        Lockfile.GraalPin prevGraal = previous == null ? null : previous.graal();
        if (javaHit != null && DefaultGraalPolicy.isGraal(javaHit)) {
            return withGraal(lock, LockPinMatch.graalPin(graal, javaHit, prevGraal));
        }
        if (graalDeclared || !graal.isEmpty()) {
            Optional<JdkHit> graalHit = DefaultGraalPolicy.choose(hits);
            return withGraal(lock, LockPinMatch.graalPin(graal, graalHit.orElse(null), prevGraal));
        }
        return lock;
    }

    private static Lockfile withGraal(Lockfile lock, Lockfile.GraalPin pin) {
        return pin.isEmpty() ? lock : lock.withGraal(pin);
    }
}
