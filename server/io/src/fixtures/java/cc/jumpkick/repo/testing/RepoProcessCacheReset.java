// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo.testing;

import cc.jumpkick.repo.RepoProcessMemos;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Drops this module's process-wide fetch memos before each test and each container.
 *
 * <p>The resolver's fan-out clears these and its own memo, but this module's tests cannot take
 * that jar: the resolver already depends here. The memos are cleared, not restored.
 */
public final class RepoProcessCacheReset implements TestExecutionListener {

    @Override
    public void executionStarted(TestIdentifier id) {
        RepoProcessMemos.clear();
    }
}
