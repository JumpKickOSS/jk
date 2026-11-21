// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Maven's {@code ${name}} interpolation of one POM field over a property map. A property whose
 * value names other properties is valued in turn, to any depth, and once per field however often
 * it is named. A reference that names no property, or one that leads back to a property still
 * being valued, is left as written, so the resolver's diagnostics can show it. A field that would
 * grow past {@value #MAX_LENGTH} characters is refused: properties that each name the next several
 * times otherwise grow exponentially.
 */
final class PomInterpolation {

    static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)\\}");

    /** The longest value a field may interpolate to; no real coordinate, URL or license comes near it. */
    static final int MAX_LENGTH = 65_536;

    private PomInterpolation() {}

    /** {@code raw} with every {@code ${...}} valued from {@code props}; {@code raw} itself when none is. */
    static @Nullable String interpolate(@Nullable String raw, Map<String, String> props) {
        if (raw == null || !raw.contains("${")) return raw;
        return new Run(raw, props).expand(raw);
    }

    /** One field's interpolation: the properties valued so far and the ones being valued. */
    private static final class Run {
        private final String field;
        private final Map<String, String> props;
        private final Map<String, String> valued = new HashMap<>();
        private final Set<String> open = new HashSet<>();

        Run(String field, Map<String, String> props) {
            this.field = field;
            this.props = props;
        }

        String expand(String text) {
            Matcher m = PROPERTY_REF.matcher(text);
            if (!m.find()) return text;
            StringBuilder out = new StringBuilder();
            int last = 0;
            boolean changed = false;
            do {
                out.append(text, last, m.start());
                String value = valueOf(m.group(1));
                out.append(value == null ? m.group() : value);
                changed |= value != null;
                last = m.end();
                bound(out);
            } while (m.find());
            if (!changed) return text;
            out.append(text, last, text.length());
            bound(out);
            return out.toString();
        }

        private @Nullable String valueOf(String name) {
            String done = valued.get(name);
            if (done != null) return done;
            String raw = props.get(name);
            if (raw == null || !open.add(name)) return null;
            try {
                String value = expand(raw);
                valued.put(name, value);
                return value;
            } finally {
                open.remove(name);
            }
        }

        private void bound(StringBuilder out) {
            if (out.length() > MAX_LENGTH) {
                String shown = field.length() > 80 ? field.substring(0, 80) + "…" : field;
                throw new PomParseException("POM value " + shown + " grows past " + MAX_LENGTH
                        + " characters as its properties are filled in; a property names others without bound");
            }
        }
    }
}
