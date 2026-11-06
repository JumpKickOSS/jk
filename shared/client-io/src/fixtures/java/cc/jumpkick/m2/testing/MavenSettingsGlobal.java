// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2.testing;

import cc.jumpkick.config.testing.BoundedGlobal;
import cc.jumpkick.m2.MavenSettings;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The {@link MavenSettings#SETTINGS_PROPERTY} system property: the {@code settings.xml} every
 * {@link MavenSettings#current()} reads ahead of {@code JK_M2_SETTINGS} and {@code ~/.m2}. A test
 * points it at its own file, with a mirror or a server credential; one left set would route every
 * later class in the JVM through a mirror that no longer answers.
 */
public final class MavenSettingsGlobal implements BoundedGlobal {

    /** Captured when the property is unset, since a captured value must be present to be put back. */
    private static final String UNSET = "";

    @Override
    public String name() {
        return "m2-settings";
    }

    /** Bounded but not attributed: tests set this by design and leave the restore to the boundary. */
    @Override
    public boolean attributable() {
        return false;
    }

    @Override
    public Optional<Object> capture() {
        String value = System.getProperty(MavenSettings.SETTINGS_PROPERTY);
        return Optional.of(value == null ? UNSET : value);
    }

    /** Clears the property when it was unset, and puts back its value otherwise. */
    @Override
    public void restore(@Nullable Object captured) {
        if (!(captured instanceof String value)) return;
        if (value.isEmpty()) System.clearProperty(MavenSettings.SETTINGS_PROPERTY);
        else System.setProperty(MavenSettings.SETTINGS_PROPERTY, value);
    }
}
