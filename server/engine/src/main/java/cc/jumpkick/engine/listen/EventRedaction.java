// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import cc.jumpkick.run.TestFailureInfo;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Mask {@code .env}-sourced values in free-form text that leaves the engine. Failures fall
 * through to the original text — redaction must never break a build.
 */
public final class EventRedaction {

    private EventRedaction() {}

    public static String redactEnv(@Nullable String dir, @Nullable String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            return redactorFor(dir).redact(text);
        } catch (RuntimeException e) {
            return text;
        }
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
            return f;
        }
    }

    /** {@link #redactFailure(String, TestFailureInfo)} with a hoisted redactor (per-plan callers). */
    public static @Nullable TestFailureInfo redactFailure(
            cc.jumpkick.config.SecretRedactor redactor, @Nullable TestFailureInfo f) {
        if (f == null) return null;
        String message = redactSafe(redactor, f.message());
        String stack = redactSafe(redactor, f.stack());
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

    private static @Nullable String redactSafe(cc.jumpkick.config.SecretRedactor redactor, @Nullable String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            return redactor.redact(text);
        } catch (RuntimeException e) {
            return text;
        }
    }
}
