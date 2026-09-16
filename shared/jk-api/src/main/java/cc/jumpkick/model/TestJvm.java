// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code [test] jvm-args} and {@code [test] system-properties}: what every forked test JVM's
 * command line carries beyond jk's own tuning, in manifest order. Both are run-tests inputs; a
 * profile's {@code jvm-args} follow them, so the profile wins where the two disagree.
 *
 * @param jvmArgs flags appended verbatim ({@code -Xmx1g}, {@code --add-opens …}, an agent)
 * @param systemProperties {@code key -> value}, each forked as {@code -Dkey=value}
 */
public record TestJvm(List<String> jvmArgs, Map<String, String> systemProperties) {

    public static final TestJvm EMPTY = new TestJvm(List.of(), Map.of());

    public TestJvm {
        jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
        systemProperties = systemProperties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(systemProperties));
    }

    public boolean isEmpty() {
        return jvmArgs.isEmpty() && systemProperties.isEmpty();
    }

    /** The command-line form: the args, then one {@code -Dkey=value} per property. */
    public List<String> flags() {
        List<String> out = new ArrayList<>(jvmArgs);
        systemProperties.forEach((k, v) -> out.add("-D" + k + "=" + v));
        return List.copyOf(out);
    }
}
