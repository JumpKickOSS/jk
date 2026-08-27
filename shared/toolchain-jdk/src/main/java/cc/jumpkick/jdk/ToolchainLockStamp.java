// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.ToolchainSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Stamps lock {@code [jdk]} / {@code [graal]} from the manifest's declaration and the toolchain
 * that actually resolved at lock time.
 *
 * <p>{@code [jdk]} is always written. The table is a record of what built the lock, and its
 * suggested version is a floor on the major only — so naming the host's JVM constrains a later
 * build to the same major, not to the same install. An earlier revision suppressed the stamp
 * unless the project declared a matching {@code jdk} major, because every consumer then read the
 * table as a hard pin and a stamp of the ambient JVM would silently satisfy a JDK the project
 * never got. That is no longer how the table reads: a pin now has to say {@code required-*}, and
 * only an {@code =} in the manifest writes one.
 *
 * <p>{@code [graal]} is written when the Java home is itself a GraalVM, or when the project asked
 * for Graal via {@code [native]}. A bare {@code [native]} declares no vendor, so whichever GraalVM
 * distribution resolves is the one recorded — as a suggestion, which is all it is.
 */
public final class ToolchainLockStamp {

    private ToolchainLockStamp() {}

    public static Lockfile apply(
            Lockfile lock,
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

        Lockfile.JdkPin jdkPin = LockPinMatch.jdkPin(jdk, javaHit);
        if (!jdkPin.isEmpty()) lock = lock.withJdk(jdkPin);

        if (javaHit != null && DefaultGraalPolicy.isGraal(javaHit)) {
            return withGraal(lock, LockPinMatch.graalPin(graal, javaHit));
        }
        if (graalDeclared || !graal.isEmpty()) {
            Optional<JdkHit> graalHit = DefaultGraalPolicy.choose(hits);
            return withGraal(lock, LockPinMatch.graalPin(graal, graalHit.orElse(null)));
        }
        return lock;
    }

    private static Lockfile withGraal(Lockfile lock, Lockfile.GraalPin pin) {
        return pin.isEmpty() ? lock : lock.withGraal(pin);
    }
}
