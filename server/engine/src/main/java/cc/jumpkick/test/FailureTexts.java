// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One copy of each distinct failure message and stack a report holds. A suite whose tests all fail
 * the same way (an application context that will not load) repeats one trace of tens of kilobytes
 * per test; kept once, a thousand such failures cost one trace in the engine heap, not a thousand.
 * Not thread-safe: each report calls it under its own lock.
 */
final class FailureTexts {

    private final Map<String, String> canonical = new HashMap<>();

    /** The copy of {@code text} already held, else {@code text}, now held; {@code null} stays {@code null}. */
    @Nullable
    String of(@Nullable String text) {
        return text == null ? null : held(text);
    }

    /** The copy of {@code text} already held, else {@code text}, now held. */
    String held(String text) {
        String held = canonical.putIfAbsent(text, text);
        return held != null ? held : text;
    }
}
