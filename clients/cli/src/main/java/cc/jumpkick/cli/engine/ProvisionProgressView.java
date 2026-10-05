// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.tui.JdkInstallView;
import cc.jumpkick.cli.tui.ProgressRow;
import cc.jumpkick.wire.protocol.ProvisionProgressEvent;
import org.jspecify.annotations.Nullable;

/**
 * The terminal's view of a tool the engine installs ({@code jk node install}, {@code jk tool
 * install}), drawn as {@code jk jdk install} draws a JDK: the download bar with its percentage under
 * the tool's chip ({@code Node.js}), then an installing row. The caller prints the settled line.
 * {@link AutoCloseable} so a failed install still wipes the active bar.
 */
final class ProvisionProgressView implements AutoCloseable {

    private @Nullable ProgressRow bar;
    private boolean installing;

    /** One {@code provision-progress} line from the engine. */
    void accept(ProvisionProgressEvent e) {
        if (ProvisionProgressEvent.INSTALL.equals(e.phase())) {
            if (installing) return;
            finishBar();
            installing = true;
            bar = ProgressRow.of(CliOutput.stdout(), chip(e.name()))
                    .status("Installing " + e.name())
                    .cancelSubject(chip(e.name()) + " install")
                    .open();
            return;
        }
        ProgressRow b = bar;
        if (b == null || installing) {
            finishBar();
            installing = false;
            bar = JdkInstallView.downloadRow(CliOutput.stdout(), chip(e.name()), e.name(), e.total());
            b = bar;
        }
        b.update(e.read(), e.total());
    }

    @Override
    public void close() {
        finishBar();
    }

    private void finishBar() {
        ProgressRow b = bar;
        if (b != null) {
            b.finish();
            bar = null;
        }
    }

    /** {@code Node.js 24.21.0} → {@code Node.js}: the tool, without its version. */
    static String chip(String name) {
        int space = name.lastIndexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }
}
