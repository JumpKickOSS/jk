// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.cli.engine.SleepMain;
import org.jspecify.annotations.NullMarked;

/** Stand-in for the resident engine in tests of unresponsive process handling. */
@NullMarked
public final class EngineMain {
    private EngineMain() {}

    public static void main(String[] args) throws InterruptedException {
        SleepMain.main(args);
    }
}
