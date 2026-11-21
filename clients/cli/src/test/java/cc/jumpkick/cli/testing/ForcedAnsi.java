// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import cc.jumpkick.cli.theme.StreamCaps;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Turns the force switch on for each test of a class, as {@code JK_FORCE_ANSI} does for a shell:
 * for a class whose assertions are about what a terminal shows. A test JVM's stdout is a pipe,
 * which is plain, so a class that asserts colour or glyph chrome has to say so. It reaches every
 * thread and every {@code Jk.execute}; {@link NoAnsi#forced} inside a test still pins plain, and it
 * never starts a live region, which needs a terminal ({@code StreamCaps.asTerminal}).
 */
public final class ForcedAnsi implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        StreamCaps.forceAnsiForTests(true);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        StreamCaps.forceAnsiForTests(false);
    }
}
