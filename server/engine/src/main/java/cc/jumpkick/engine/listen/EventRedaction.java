// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.Redacted;
import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.test.JUnitLauncher;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Mask known secret values in free-form text that leaves the engine — the {@code .env}-declared
 * ones and the repository credentials the request resolved, composed in {@link #redactorFor}.
 * Failures fall through to the original text — redaction must never break a build.
 */
public final class EventRedaction {

    private EventRedaction() {}

    public static String redactEnv(@Nullable String dir, @Nullable String text) {
        if (text == null || text.isEmpty()) return text;
        SecretRedactor redactor;
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
     * {@link #redactEnv} over a whole error list, with the redactor hoisted — building one walks
     * for a workspace root and parses {@code .env}, so a per-row call would repeat that work.
     *
     * <p>The result is typed {@link Redacted} because {@code ProtoEvents.workspaceFinish} accepts
     * nothing else: this method (through {@link cc.jumpkick.config.SecretRedactor#redactAll}) is
     * the only way an engine verb can produce the terminal's error rows.
     *
     * <p>Fail-open, like every other method here: if the redactor cannot be built or throws, the
     * rows are wrapped unmasked and the once-per-run warning fires. A security control that
     * silently disables itself must stay discoverable, but it may not fail a build.
     */
    public static List<Redacted> redactErrors(@Nullable String dir, @Nullable List<String> errors) {
        if (errors == null || errors.isEmpty()) return List.of();
        SecretRedactor redactor;
        try {
            redactor = redactorFor(dir);
        } catch (RuntimeException e) {
            // A blank dir with no session is a routine off-request call, not a broken redactor.
            if (dir != null && !dir.isBlank()) warnFailOpen(e);
            redactor = SecretRedactor.none();
        }
        try {
            return redactor.redactAll(errors);
        } catch (RuntimeException e) {
            warnFailOpen(e);
            return SecretRedactor.none().redactAll(errors);
        }
    }

    /**
     * Redaction never breaks a build — but a silently-disabled security control must still be
     * discoverable. One warning per engine run, on stderr (merged into the engine log
     * by the spawn line).
     */
    private static final AtomicBoolean WARNED_FAIL_OPEN = new AtomicBoolean();

    static void warnFailOpen(RuntimeException e) {
        if (WARNED_FAIL_OPEN.compareAndSet(false, true)) {
            System.err.println("jk engine: secret redaction failed open ("
                    + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage())
                    + ") — output may contain unmasked secrets for this run");
        }
    }

    /** Test seam: re-arm the once-per-run fail-open warning. */
    static void resetFailOpenWarning() {
        WARNED_FAIL_OPEN.set(false);
    }

    /**
     * The redactor for work rooted at {@code dir} (session working dir when blank): the
     * {@code .env}-declared secrets of that tree, plus the repository credentials this workspace's
     * build resolved. Two sources, two owners, merged only here — a resolved credential is never
     * pushed into {@link cc.jumpkick.config.EnvLookup} to make it visible.
     *
     * <p>Building one re-derives the env lookup — a workspace-root walk plus {@code .env} parsing —
     * so per-line callers must hoist the result instead of calling {@link #redactEnv} per line.
     * Never null; throws only what {@link #redactEnv} already swallows.
     */
    public static SecretRedactor redactorFor(@Nullable String dir) {
        Path root;
        if (dir != null && !dir.isBlank()) {
            root = Path.of(dir);
        } else {
            root = SessionContext.current().workingDir();
        }
        if (root == null) return SecretRedactor.none();
        return ResolvedSecrets.plus(root, BuildEnv.secretsFor(root));
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
    public static @Nullable TestFailureInfo redactFailure(SecretRedactor redactor, @Nullable TestFailureInfo f) {
        if (f == null) return null;
        String message = redactTruncationSeam(
                redactor, redactSafe(redactor, f.message()), JUnitLauncher.MESSAGE_TRUNCATION_MARKER);
        String stack =
                redactTruncationSeam(redactor, redactSafe(redactor, f.stack()), JUnitLauncher.STACK_TRUNCATION_MARKER);
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
     * pass cannot match. When {@code text} carries the capture marker, mask a dangling
     * secret prefix at the cut point.
     */
    private static @Nullable String redactTruncationSeam(
            SecretRedactor redactor, @Nullable String text, String marker) {
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

    private static @Nullable String redactSafe(SecretRedactor redactor, @Nullable String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            return redactor.redact(text);
        } catch (RuntimeException e) {
            warnFailOpen(e);
            return text;
        }
    }
}
