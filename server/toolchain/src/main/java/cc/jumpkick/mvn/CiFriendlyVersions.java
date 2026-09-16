// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Maven's CI-friendly versions: a {@code <version>} spelled {@code ${revision}}, {@code
 * ${changelist}}, {@code ${sha1}} or any other property takes its value from the POM chain's
 * {@code <properties>} or from {@code -D} on Maven's command line. An import has no command line,
 * so the chain is the only source and a placeholder it does not define stays as written.
 */
final class CiFriendlyVersions {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    /** The version written when a placeholder has no value anywhere in the POM chain. */
    static final String FALLBACK = "0.0.0-SNAPSHOT";

    private CiFriendlyVersions() {}

    static boolean hasPlaceholder(String version) {
        return version.contains("${");
    }

    /** Replace every placeholder {@code properties} has a value for; the others stay as written. */
    static String interpolate(String version, Function<String, @Nullable String> properties) {
        if (!hasPlaceholder(version)) return version;
        Matcher m = PLACEHOLDER.matcher(version);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = properties.apply(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The property names {@code version} still references, in order of appearance. */
    static List<String> unresolved(String version) {
        List<String> names = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(version);
        while (m.find()) names.add(m.group(1));
        return names;
    }
}
