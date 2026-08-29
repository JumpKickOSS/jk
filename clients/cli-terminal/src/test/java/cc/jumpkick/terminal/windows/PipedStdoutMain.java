// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.windows;

import cc.jumpkick.terminal.Terminals;

/** Child JVM for {@link WindowsUtf8PipedStdoutTest}: bootstrap, one println, one bare print, exit. */
public final class PipedStdoutMain {

    private PipedStdoutMain() {}

    public static void main(String[] args) {
        Terminals.bootstrap();
        System.out.println("piped-line-alive");
        System.out.print("piped-tail-alive");
        Terminals.shutdown();
    }
}
