// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.lock.Lockfile;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Stamps lock {@code [jdk]} / {@code [graal]} from the JDK (and optional GraalVM) actually selected
 * at lock time.
 *
 * <p>{@code [jdk]} is written only when the project declared a {@code jdk} major and the selected
 * home is that major. Every consumer reads the table as a pin, so stamping the JVM jk happened to
 * run on would answer a question the project never asked: on a host without the declared JDK the
 * stamp names the host's own JVM, that pin then reads as satisfied, and the declared JDK is never
 * provisioned — the build succeeds on the wrong JDK in silence. A project that declares no
 * {@code jdk} gets no table, which is what leaves resolution free to pick.
 *
 * <p>{@code [graal]} is written when the Java home is itself a GraalVM, or any Graal is installed.
 */
public final class ToolchainLockStamp {

    private ToolchainLockStamp() {}

    public static Lockfile apply(Lockfile lock, Path javaHome, JdkRegistry registry, int declaredJdkMajor) {
        if (lock == null) return null;
        List<JdkHit> hits = registry.listHits();
        Optional<JdkHit> javaHit = LockPinMatch.hitFor(javaHome, hits);
        if (javaHit.isPresent()
                && javaHit.get().version() != null
                && !javaHit.get().version().isBlank()
                && isDeclaredMajor(javaHit.get(), declaredJdkMajor)) {
            lock = lock.withJdk(LockPinMatch.jdkPin(javaHit.get()));
        }
        if (javaHit.isPresent() && DefaultGraalPolicy.isGraal(javaHit.get())) {
            return lock.withGraal(LockPinMatch.graalPin(javaHit.get()));
        }
        Optional<JdkHit> graal = DefaultGraalPolicy.choose(hits);
        if (graal.isPresent()
                && graal.get().version() != null
                && !graal.get().version().isBlank()) {
            return lock.withGraal(LockPinMatch.graalPin(graal.get()));
        }
        return lock;
    }

    /** True when the project declared a {@code jdk} major (non-zero) and {@code hit} is that major. */
    private static boolean isDeclaredMajor(JdkHit hit, int declaredJdkMajor) {
        if (declaredJdkMajor <= 0) return false;
        Integer major = JdkKeywords.leadingMajor(hit.version());
        return major != null && major == declaredJdkMajor;
    }
}
