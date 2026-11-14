// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a declared suite's own test JVMs get beyond {@code [test]}'s: {@code env} on top of the
 * module's (the suite wins a name both set), {@code jvmArgs} and {@code systemProperties} after its
 * flags, and a per-method {@code timeoutMs} (null for none). A suite with any of them runs in JVMs of
 * its own.
 */
public record SuiteSettings(
        Map<String, String> env,
        List<String> jvmArgs,
        Map<String, String> systemProperties,
        @Nullable Long timeoutMs) {

    public static final SuiteSettings NONE = new SuiteSettings(Map.of(), List.of(), Map.of(), null);

    public SuiteSettings {
        env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
        jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
        systemProperties = systemProperties == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(systemProperties));
    }

    /** Whether the suite sets nothing, so its tests can share JVMs with the module's other suites. */
    public boolean isEmpty() {
        return env.isEmpty() && jvmArgs.isEmpty() && systemProperties.isEmpty() && timeoutMs == null;
    }

    /**
     * The flags the suite adds to a test JVM, after {@code [test]}'s: its {@code jvm-args}, one
     * {@code -Dkey=value} per system property, then JUnit Jupiter's timeout settings when it has a
     * {@code timeout}: every test and lifecycle method stops at it, run on a separate thread so a
     * method that ignores interrupts still fails, with a thread dump printed first.
     */
    public List<String> forkArgs() {
        List<String> out = new ArrayList<>(jvmArgs);
        systemProperties.forEach((key, value) -> out.add("-D" + key + "=" + value));
        if (timeoutMs != null) {
            out.add("-Djunit.jupiter.execution.timeout.default=" + timeoutMs + "ms");
            out.add("-Djunit.jupiter.execution.timeout.thread.mode.default=SEPARATE_THREAD");
            out.add("-Djunit.jupiter.execution.timeout.threaddump.enabled=true");
        }
        return List.copyOf(out);
    }
}
