// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.io.PrintStream;

/**
 * A widget that occupies a live terminal region (spinner, plan header, download bar). Snapshot
 * {@link #render} still works for tests and a single frozen frame.
 */
public interface LiveWidget extends Widget, AutoCloseable, LiveRegion {

    void show(PrintStream out);

    void tick(int frame);

    @Override
    void close();
}
