// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract.fixture;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Static fields the static initializer assigns in every shape the extractor records. */
public final class Statics {

    private Statics() {}

    static final Map<String, String> MUTABLE = new ConcurrentHashMap<>();

    static final List<String> FIXED = List.of("a", "b");

    static final Map<String, String> WRAPPED = Collections.unmodifiableMap(new HashMap<>(Map.of("k", "v")));

    static final Pattern PATTERN = Pattern.compile("x+");

    static final String CONSTANT = "c";

    static final String[] ARRAY = {"a"};

    static @Nullable Object lazy;

    static Object lazy() {
        if (lazy == null) lazy = new Object();
        return lazy;
    }
}
