// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config.testing;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Every {@code jk.env.*} system property: {@link cc.jumpkick.host.JkDirs}'s in-process overlay
 * over the environment. A test points one at its own store, cache, state or home; one left set
 * would send every later class in the JVM there.
 */
public final class JkEnvGlobal implements BoundedGlobal {

    private static final String PREFIX = "jk.env.";

    @Override
    public String name() {
        return "jk-env";
    }

    /** Bounded but not attributed: tests set these by design and leave the restore to the boundary. */
    @Override
    public boolean attributable() {
        return false;
    }

    @Override
    public Optional<Object> capture() {
        return Optional.of(current());
    }

    /** Clears every overlay {@code captured} lacks and puts back the value of every one it holds. */
    @Override
    public void restore(@Nullable Object captured) {
        if (!(captured instanceof Map<?, ?> saved)) return;
        for (String name : current().keySet()) {
            if (!saved.containsKey(name)) System.clearProperty(name);
        }
        saved.forEach((name, value) -> System.setProperty((String) name, (String) value));
    }

    private static Map<String, String> current() {
        Map<String, String> out = new TreeMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (!name.startsWith(PREFIX)) continue;
            String value = System.getProperty(name);
            if (value != null) out.put(name, value);
        }
        return out;
    }
}
