// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner.env;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the no-argument {@code System.getenv()} answers in a test JVM that reads the caller's
 * shell: an unmodifiable view of this JVM's environment and the caller's, recorded at the grain of
 * what is used. {@link #get}, {@link #containsKey} and {@link #getOrDefault} read one variable,
 * fetched and recorded as {@link EnvLookups#getenv(String)} reads it. Listing the names — {@link
 * #keySet}, {@link #size}, an iteration that never asks an entry for its value — lists them once
 * (the engine records that the names were seen, and keys the suite on them), and an entry's value
 * is fetched only when the entry is asked for it. So a framework that lists the names to bind
 * {@code MY_APP_FOO} to {@code my.app.foo} and reads the few it binds is keyed on the names and
 * those values, never on a value it did not read.
 *
 * <p>One instance per JVM, so code that compares the map it holds with {@code System.getenv()} by
 * identity (Spring Boot does, to tell the environment cannot change) finds the same one.
 */
final class RecordingEnv extends AbstractMap<String, String> {

    @Override
    public @Nullable String get(@Nullable Object key) {
        return key instanceof String name ? EnvLookups.getenv(name) : null;
    }

    @Override
    public boolean containsKey(@Nullable Object key) {
        return get(key) != null;
    }

    @Override
    public @Nullable String getOrDefault(@Nullable Object key, @Nullable String defaultValue) {
        String v = get(key);
        return v != null ? v : defaultValue;
    }

    @Override
    public Set<Map.Entry<String, String>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Map.Entry<String, String>> iterator() {
                Iterator<String> names = EnvLookups.names().iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return names.hasNext();
                    }

                    @Override
                    public Map.Entry<String, String> next() {
                        return new Lazy(names.next());
                    }
                };
            }

            @Override
            public int size() {
                return EnvLookups.names().size();
            }
        };
    }

    /** An entry whose value is fetched, and recorded, when asked for. */
    private static final class Lazy implements Map.Entry<String, String> {
        private final String name;

        Lazy(String name) {
            this.name = name;
        }

        @Override
        public String getKey() {
            return name;
        }

        @Override
        public String getValue() {
            String v = EnvLookups.getenv(name);
            return v == null ? "" : v;
        }

        @Override
        public String setValue(String value) {
            throw new UnsupportedOperationException("the environment is read-only");
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof Map.Entry<?, ?> e
                    && name.equals(e.getKey())
                    && getValue().equals(e.getValue());
        }

        @Override
        public int hashCode() {
            return name.hashCode() ^ getValue().hashCode();
        }

        @Override
        public String toString() {
            return name + "=" + getValue();
        }
    }
}
