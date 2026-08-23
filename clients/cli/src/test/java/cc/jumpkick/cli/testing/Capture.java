// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.IntSupplier;

/**
 * Console capture for tests that assert on CLI rendering. The chosen stream — or both, when the
 * assertion is about which one wrote — is swapped for a buffer around {@code body} and always
 * restored, so a throwing test can't silence later ones. UTF-8 is pinned on both the writer and
 * the read-back so captured glyphs never depend on the platform default charset.
 */
public final class Capture {
    private Capture() {}

    /**
     * Line terminators normalized to {@code \n}. Chrome assertions are about content, not about
     * which terminator the host's {@code println} emits — {@code \r\n} on Windows.
     */
    private static String lf(String captured) {
        return captured.replace("\r\n", "\n");
    }

    public static String stdout(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return lf(buffer.toString(StandardCharsets.UTF_8));
    }

    /**
     * Overload for exit-code-returning bodies, e.g. {@code Capture.stdout(() -> run("tasks"))};
     * the exit code is discarded — assert it inside the lambda if it matters.
     */
    public static String stdout(IntSupplier body) {
        return stdout((Runnable) body::getAsInt);
    }

    /** Captured {@code System.err} for {@code body} — the human diagnostic stream. */
    public static String stderr(Runnable body) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(original);
        }
        return lf(buffer.toString(StandardCharsets.UTF_8));
    }

    /** Overload for exit-code-returning bodies; the exit code is discarded. */
    public static String stderr(IntSupplier body) {
        return stderr((Runnable) body::getAsInt);
    }

    /** What one body wrote to each console stream. */
    public record Streams(String out, String err) {}

    /**
     * Both streams from a single run — needed whenever the assertion is about <em>which</em> stream
     * something landed on: the blank-line envelope closes on the stream that wrote last, and a
     * settle chip goes to stdout or stderr depending on the outcome.
     */
    public static Streams both(Runnable body) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Streams(lf(out.toString(StandardCharsets.UTF_8)), lf(err.toString(StandardCharsets.UTF_8)));
    }

    /** Overload for exit-code-returning bodies; the exit code is discarded. */
    public static Streams both(IntSupplier body) {
        return both((Runnable) body::getAsInt);
    }
}
