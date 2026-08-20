// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.TestAnsi;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Shared fixture helpers for the {@code JkManager*Test} family. */
final class JkManagerTestSupport {

    private JkManagerTestSupport() {}

    static List<String> stripAll(List<String> lines) {
        return lines.stream().map(TestAnsi::strip).toList();
    }

    static PrintStream stream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }
}
