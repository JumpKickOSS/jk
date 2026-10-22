// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.lock.GraalPin;
import cc.jumpkick.lock.JdkPin;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ToolchainPin;
import cc.jumpkick.model.ToolchainSpec;
import java.nio.file.Path;
import java.util.List;
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
 * <p>{@code previous} is the lock being replaced. It keeps an undeclared suggestion stable: the
 * record of what built the lock should not move because a colleague ran it on a different JDK. A
 * plain {@code jk lock} keeps it whatever resolved; {@code jk update} keeps it while it names the
 * major that resolved, and refreshes it only when the major moved, so two hosts on different
 * patches of one major never flip it. An unknown-vendor suggestion is not kept — it cannot be
 * installed and would fail the next build after the manifest pin is gone.
 */
public final class ToolchainLockStamp {

    private ToolchainLockStamp() {}

    /**
     * @param keepAnyMajor keep {@code previous}'s suggestion even when the toolchain that resolved
     *     is on another major ({@code jk lock}); otherwise only on the same major ({@code jk update})
     */
    public static @Nullable Lockfile apply(
            @Nullable Lockfile lock,
            @Nullable Lockfile previous,
            boolean keepAnyMajor,
            Path javaHome,
            JdkRegistry registry,
            @Nullable ToolchainSpec jdkSpec,
            @Nullable ToolchainSpec graalSpec,
            boolean graalDeclared) {
        if (lock == null) return null;
        ToolchainSpec jdk = jdkSpec == null ? ToolchainSpec.NONE : jdkSpec;
        ToolchainSpec graal = graalSpec == null ? ToolchainSpec.NONE : graalSpec;
        List<JdkHit> hits = registry.listHits();
        JdkHit javaHit = LockPinMatch.hitFor(javaHome, hits).orElse(null);

        JdkPin prevJdk = previous == null ? null : previous.jdk();
        JdkPin jdkPin = LockPinMatch.jdkPin(jdk, javaHit, kept(prevJdk, javaHit, keepAnyMajor));
        if (!jdkPin.isEmpty()) lock = lock.withJdk(jdkPin);

        GraalPin prevGraal = previous == null ? null : previous.graal();
        if (javaHit != null && DefaultGraalPolicy.isGraal(javaHit)) {
            return withGraal(lock, LockPinMatch.graalPin(graal, javaHit, kept(prevGraal, javaHit, keepAnyMajor)));
        }
        if (graalDeclared || !graal.isEmpty()) {
            JdkHit graalHit = DefaultGraalPolicy.choose(hits).orElse(null);
            return withGraal(lock, LockPinMatch.graalPin(graal, graalHit, kept(prevGraal, graalHit, keepAnyMajor)));
        }
        return lock;
    }

    /**
     * {@code previous} when it may stand: always under {@code keepAnyMajor}, else while its
     * suggestion names the major of {@code resolved} (or nothing resolved to compare against).
     */
    private static <T extends ToolchainPin> @Nullable T kept(
            @Nullable T previous, @Nullable JdkHit resolved, boolean keepAnyMajor) {
        if (previous == null || keepAnyMajor || resolved == null) return previous;
        Integer was = JdkKeywords.leadingMajor(previous.suggestedVersion());
        Integer now = JdkKeywords.leadingMajor(resolved.version());
        return was != null && was.equals(now) ? previous : null;
    }

    private static Lockfile withGraal(Lockfile lock, GraalPin pin) {
        return pin.isEmpty() ? lock : lock.withGraal(pin);
    }
}
