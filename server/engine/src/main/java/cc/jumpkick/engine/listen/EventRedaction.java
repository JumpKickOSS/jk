// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import cc.jumpkick.run.TestFailureInfo;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Mask {@code .env}-sourced values in free-form text that leaves the engine. Failures fall
 * through to the original text — redaction must never break a build.
 */
public final class EventRedaction {

    private EventRedaction() {}

    public static String redactEnv(@Nullable String dir, @Nullable String text) {
        if (text == null || text.isEmpty()) return text;
        cc.jumpkick.config.SecretRedactor redactor;
        try {
            redactor = redactorFor(dir);
        } catch (RuntimeException e) {
            // A blank dir with no session is a routine off-request call, not a broken redactor.
            if (dir != null && !dir.isBlank()) warnFailOpen(e);
            return text;
        }
        try {
            return redactor.redact(text);
        } catch (RuntimeException e) {
            warnFailOpen(e);
            return text;
        }
    }

    /**
     * Redaction never breaks a build — but a silently-disabled security control must still be
     * discoverable (JK-1965). One warning per engine run, on stderr (merged into the engine log
     * by the spawn line).
     */
    private static final AtomicBoolean WARNED_FAIL_OPEN = new AtomicBoolean();

    static void warnFailOpen(RuntimeException e) {
        if (WARNED_FAIL_OPEN.compareAndSet(false, true)) {
            System.err.println("jk engine: secret redaction failed open ("
                    + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage())
                    + ") — output may contain unmasked .env values for this run");
        }
    }

    /** Test seam: re-arm the once-per-run fail-open warning. */
    static void resetFailOpenWarning() {
        WARNED_FAIL_OPEN.set(false);
    }

    /**
     * The redactor for work rooted at {@code dir} (session working dir when blank). Building one
     * re-derives the env lookup — a workspace-root walk plus {@code .env} parsing — so per-line
     * callers must hoist the result instead of calling {@link #redactEnv} per line (JK-1942).
     * Never null; throws only what {@link #redactEnv} already swallows.
     */
    public static cc.jumpkick.config.SecretRedactor redactorFor(@Nullable String dir) {
        Path root;
        if (dir != null && !dir.isBlank()) {
            root = Path.of(dir);
        } else {
            root = cc.jumpkick.config.SessionContext.current().workingDir();
        }
        if (root == null) return cc.jumpkick.config.SecretRedactor.none();
        return cc.jumpkick.config.BuildEnv.secretsFor(root);
    }

    /**
     * {@link #redactEnv} over the free-text fields of a test failure. The first line of
     * {@code printStackTrace} repeats the raw exception message, so masking {@code message}
     * alone still leaks the secret through {@code stack}.
     */
    public static @Nullable TestFailureInfo redactFailure(@Nullable String dir, @Nullable TestFailureInfo f) {
        if (f == null) return null;
        try {
            return redactFailure(redactorFor(dir), f);
        } catch (RuntimeException e) {
            if (dir != null && !dir.isBlank()) warnFailOpen(e);
            return f;
        }
    }

    /** {@link #redactFailure(String, TestFailureInfo)} with a hoisted redactor (per-plan callers). */
    public static @Nullable TestFailureInfo redactFailure(
            cc.jumpkick.config.SecretRedactor redactor, @Nullable TestFailureInfo f) {
        if (f == null) return null;
        String message = redactTruncationSeam(
                redactor, redactSafe(redactor, f.message()), cc.jumpkick.test.JUnitLauncher.MESSAGE_TRUNCATION_MARKER);
        String stack = redactTruncationSeam(
                redactor, redactSafe(redactor, f.stack()), cc.jumpkick.test.JUnitLauncher.STACK_TRUNCATION_MARKER);
        if (Objects.equals(message, f.message()) && Objects.equals(stack, f.stack())) {
            return f;
        }
        return new TestFailureInfo(
                f.module(),
                f.engine(),
                f.className(),
                f.method(),
                f.exceptionClass(),
                message,
                stack,
                f.worker(),
                f.file(),
                f.line(),
                f.snippetStart(),
                f.snippet());
    }

    /**
     * Capture-time truncation can cut a secret mid-value, leaving a prefix the exact-substring
     * pass cannot match (JK-1960). When {@code text} carries the capture marker, mask a dangling
     * secret prefix at the cut point.
     */
    private static @Nullable String redactTruncationSeam(
            cc.jumpkick.config.SecretRedactor redactor, @Nullable String text, String marker) {
        if (text == null || text.isEmpty()) return text;
        try {
            int at = text.lastIndexOf(marker);
            if (at < 0) return text;
            String head = text.substring(0, at);
            String masked = redactor.maskTrailingSecretPrefix(head);
            return masked.equals(head) ? text : masked + text.substring(at);
        } catch (RuntimeException e) {
            return text;
        }
    }

    private static @Nullable String redactSafe(cc.jumpkick.config.SecretRedactor redactor, @Nullable String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            return redactor.redact(text);
        } catch (RuntimeException e) {
            warnFailOpen(e);
            return text;
        }
    }
}
