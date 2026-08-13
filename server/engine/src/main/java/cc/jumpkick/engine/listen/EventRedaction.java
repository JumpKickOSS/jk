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
            Path root;
            if (dir != null && !dir.isBlank()) {
                root = Path.of(dir);
            } else {
                root = cc.jumpkick.config.SessionContext.current().workingDir();
            }
            if (root == null) return text;
            return cc.jumpkick.config.BuildEnv.secretsFor(root).redact(text);
        } catch (RuntimeException e) {
            return text;
        }
    }

    /**
     * {@link #redactEnv} over the free-text fields of a test failure. The first line of
     * {@code printStackTrace} repeats the raw exception message, so masking {@code message}
     * alone still leaks the secret through {@code stack}.
     */
    public static @Nullable TestFailureInfo redactFailure(@Nullable String dir, @Nullable TestFailureInfo f) {
        if (f == null) return null;
        String message = redactEnv(dir, f.message());
        String stack = redactEnv(dir, f.stack());
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
}
