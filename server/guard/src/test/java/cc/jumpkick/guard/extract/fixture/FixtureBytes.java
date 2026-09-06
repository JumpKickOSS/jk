// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** The compiled bytes of a fixture class, read back through the class loader. */
public final class FixtureBytes {

    private FixtureBytes() {}

    public static byte[] of(Class<?> c) throws IOException {
        String res = c.getName().replace('.', '/') + ".class";
        try (InputStream in = Objects.requireNonNull(c.getClassLoader().getResourceAsStream(res), res)) {
            return in.readAllBytes();
        }
    }
}
