// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.IntSupplier;

/**
 * Stdout capture for tests that assert on CLI rendering. {@code System.out} is swapped for a
 * buffer around {@code body} and always restored, so a throwing test can't silence later ones.
 * UTF-8 is pinned on both the writer and the read-back so captured glyphs never depend on the
 * platform default charset.
 */
public final class Capture {
    private Capture() {}

    public static String stdout(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * Overload for exit-code-returning bodies, e.g. {@code Capture.stdout(() -> run("tasks"))};
     * the exit code is discarded — assert it inside the lambda if it matters.
     */
    public static String stdout(IntSupplier body) {
        return stdout((Runnable) body::getAsInt);
    }
}
