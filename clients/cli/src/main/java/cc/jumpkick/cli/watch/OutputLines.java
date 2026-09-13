// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import java.io.IOException;
import java.io.Reader;
import java.util.function.Consumer;

/**
 * Splits a child's output into the lines a terminal would have ended up showing. A line feed ends
 * a line. A bare carriage return is a repaint of the current row — a progress bar, a spinner, a
 * percentage — so everything before it is dropped and only the row's final state is reported when
 * the line feed arrives; {@code \r\n} is a plain line end. Whatever is pending at end of stream is
 * a line too.
 */
final class OutputLines {

    private OutputLines() {}

    static void read(Reader in, Consumer<String> lines) throws IOException {
        StringBuilder row = new StringBuilder();
        boolean returned = false;
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                lines.accept(row.toString());
                row.setLength(0);
                returned = false;
                continue;
            }
            if (returned) row.setLength(0);
            returned = c == '\r';
            if (!returned) row.append((char) c);
        }
        if (!row.isEmpty()) lines.accept(row.toString());
    }
}
