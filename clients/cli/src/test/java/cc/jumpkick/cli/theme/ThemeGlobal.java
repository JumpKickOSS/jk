// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.theme;

import cc.jumpkick.config.testing.BoundedGlobal;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code Theme.Holder.active}, the process-wide active theme.
 *
 * <p>Lives in {@code cc.jumpkick.cli.theme} because {@link Theme#active()} and
 * {@link Theme#setActive} are package-private — which is why the registry is a ServiceLoader SPI
 * rather than a list in {@code :core}. Fully attributable: the theme is both readable and settable.
 */
public final class ThemeGlobal implements BoundedGlobal {

    @Override
    public String name() {
        return "theme";
    }

    @Override
    public Optional<Object> capture() {
        return Optional.of(Theme.active());
    }

    @Override
    public void restore(@Nullable Object captured) {
        if (captured instanceof Theme t) Theme.setActive(t);
    }
}
