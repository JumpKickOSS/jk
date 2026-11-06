// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http.testing;

import cc.jumpkick.config.testing.BoundedGlobal;
import cc.jumpkick.http.ConnectFaults;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Every address {@link ConnectFaults} is refusing. Restore-only: a port one test found dead can be
 * the next test's live server, so the memo is dropped rather than put back.
 */
public final class ConnectFaultsGlobal implements BoundedGlobal {

    @Override
    public String name() {
        return "connect-faults";
    }

    @Override
    public Optional<Object> capture() {
        return Optional.empty();
    }

    @Override
    public void restore(@Nullable Object captured) {
        ConnectFaults.forget();
    }
}
