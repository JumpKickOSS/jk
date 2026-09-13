// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/**
 * Names this test JVM as the owner of every engine it spawns ({@link EngineSpawn#forwarded}
 * carries the property into the engine, whose watchdog stops it once the owner is gone). A
 * launcher-level listener rather than a fixture call: a killed or crashed worker never reaches
 * the teardown hooks, and any of the CLI's commands can start an engine, not only the suites that
 * materialize one on purpose. Registered via {@code META-INF/services}, so every test JVM runs it
 * once, before the first test.
 */
public final class EngineOwnerListener implements TestExecutionListener {

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        System.setProperty(
                EngineSpawn.OWNER_PID_PROPERTY,
                Long.toString(ProcessHandle.current().pid()));
    }
}
