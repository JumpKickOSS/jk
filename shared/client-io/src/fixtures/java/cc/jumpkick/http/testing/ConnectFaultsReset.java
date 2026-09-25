// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http.testing;

import cc.jumpkick.http.ConnectFaults;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Drops every address {@link ConnectFaults} is refusing, before each test and each container.
 *
 * <p>The memo is cleared, not restored: a port one test found dead can be the next test's live
 * server. A platform listener, so the drop runs in every tier without extension autodetection.
 */
public final class ConnectFaultsReset implements TestExecutionListener {

    @Override
    public void executionStarted(TestIdentifier id) {
        ConnectFaults.forget();
    }
}
