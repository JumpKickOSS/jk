// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import org.jspecify.annotations.Nullable;

/** Reads single registry values. A value that is missing, unreadable or of another type reads as null. */
public interface Registry {

    /** The predefined root keys the host checks read. */
    enum Hive {
        LOCAL_MACHINE,
        CURRENT_USER
    }

    /** No registry: every value is missing. */
    Registry NONE = new Registry() {
        @Override
        public @Nullable Integer dword(Hive hive, String key, String value) {
            return null;
        }

        @Override
        public @Nullable String string(Hive hive, String key, String value) {
            return null;
        }
    };

    @Nullable
    Integer dword(Hive hive, String key, String value);

    @Nullable
    String string(Hive hive, String key, String value);
}
