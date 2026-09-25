// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolve.testing;

import cc.jumpkick.resolve.ResolveProcessCacheControl;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Drops every process-wide resolve memo before each test and each container.
 *
 * <p>Fixture repositories reuse one coordinate with different bytes. The memos are cleared, not
 * restored. A platform listener, so the drop runs in every tier without extension autodetection.
 */
public final class ResolveProcessCacheReset implements TestExecutionListener {

    @Override
    public void executionStarted(TestIdentifier id) {
        ResolveProcessCacheControl.clearAll();
    }
}
