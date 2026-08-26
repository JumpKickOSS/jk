// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.lock.Lockfile;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Stamps lock {@code [jdk]} / {@code [graal]} from the JDK (and optional GraalVM) actually selected
 * at lock time. {@code [jdk]} is always written when a home with a readable version is known.
 * {@code [graal]} is written when the Java home is itself a GraalVM, or any Graal is installed.
 */
public final class ToolchainLockStamp {

    private ToolchainLockStamp() {}

    public static Lockfile apply(Lockfile lock, Path javaHome, JdkRegistry registry) {
        if (lock == null) return null;
        List<JdkHit> hits = registry.listHits();
        Optional<JdkHit> javaHit = LockPinMatch.hitFor(javaHome, hits);
        if (javaHit.isPresent()
                && javaHit.get().version() != null
                && !javaHit.get().version().isBlank()) {
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
}
