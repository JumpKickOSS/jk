// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2.testing;

import cc.jumpkick.config.testing.BoundedGlobal;
import cc.jumpkick.repo.M2Dirs;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The {@link M2Dirs#LOCAL_PROPERTY} system property: the local repository every
 * {@link M2Dirs#localRepository()} returns ahead of {@code JK_M2_LOCAL} and {@code settings.xml}. A
 * test points it at a {@code @TempDir}; one left set would send every later class in the JVM to a
 * directory JUnit has already deleted.
 */
public final class MavenLocalRepoGlobal implements BoundedGlobal {

    /** Captured when the property is unset, since a captured value must be present to be put back. */
    private static final String UNSET = "";

    @Override
    public String name() {
        return "m2-local";
    }

    /** Bounded but not attributed: tests set this by design and leave the restore to the boundary. */
    @Override
    public boolean attributable() {
        return false;
    }

    @Override
    public Optional<Object> capture() {
        String value = System.getProperty(M2Dirs.LOCAL_PROPERTY);
        return Optional.of(value == null ? UNSET : value);
    }

    /** Clears the property when it was unset, and puts back its value otherwise. */
    @Override
    public void restore(@Nullable Object captured) {
        if (!(captured instanceof String value)) return;
        if (value.isEmpty()) System.clearProperty(M2Dirs.LOCAL_PROPERTY);
        else System.setProperty(M2Dirs.LOCAL_PROPERTY, value);
    }
}
