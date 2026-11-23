// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.http.Http;
import cc.jumpkick.wire.EnginePaths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * What one launch of a suite starts its test JVMs with that is the same on every host: the flags jk
 * adds around the module's own, the runner's selection arguments and the environment defaults the
 * JVMs get unless the module sets the name itself. The heap, the temp and work directories, the
 * ports, the worker count and the paths of the jars are each host's and stay out.
 *
 * <p>A run-tests stamp keys this value ({@link #token}), and {@link JUnitLauncher} launches from it,
 * so a change to what it spells moves the key of exactly the suites whose launch spells it, and a
 * change to the code that forks the JVMs moves none.
 *
 * @param assertions whether the test JVMs run with {@code -ea} ({@code [test] assertions})
 * @param failureSnapshot whether a failure carries its throwing frames' variables ({@code [test]
 *     failure-snapshot})
 * @param idleTimeoutMs the silence after which a test JVM is killed ({@code [test] idle-timeout});
 *     0 when the module sets none
 * @param workerHomes whether each pooled test JVM gets a {@code JK_HOME} of its own
 * @param serialTags the class tags whose classes run on one trailing worker ({@code [test]
 *     serial-tags})
 * @param includeTags the run's include tags; empty for no include filter
 * @param excludeTags the run's exclude tags
 * @param classPatterns the run's {@code --class} patterns; empty for every class
 * @param excludeClasses the classes no JVM of the run discovers or runs ({@code [test]
 *     exclude-classes})
 * @param walled whether the launch is the fast tier's, whose JVMs may not leave the host or start an
 *     engine
 */
public record ForkSpec(
        boolean assertions,
        boolean failureSnapshot,
        long idleTimeoutMs,
        boolean workerHomes,
        List<String> serialTags,
        List<String> includeTags,
        List<String> excludeTags,
        List<String> classPatterns,
        List<String> excludeClasses,
        boolean walled) {

    /** A launch with nothing set: assertions on, every class, no walls. */
    public static final ForkSpec DEFAULT =
            new ForkSpec(true, false, 0, false, List.of(), List.of(), List.of(), List.of(), List.of(), false);

    /**
     * The plugin the test JVM's plugin host runs. The module under test rides the runner's
     * classpath, so a module that is itself a plugin would otherwise show the host two {@code
     * Plugin} services.
     */
    static final String RUNNER_PLUGIN_CLASS = "cc.jumpkick.testrunner.TestRunner";

    public ForkSpec {
        idleTimeoutMs = Math.max(0, idleTimeoutMs);
        serialTags = List.copyOf(serialTags);
        includeTags = List.copyOf(includeTags);
        excludeTags = List.copyOf(excludeTags);
        classPatterns = List.copyOf(classPatterns);
        excludeClasses = List.copyOf(excludeClasses);
    }

    /** The flags ahead of the module's own JVM arguments, which may override them. */
    public List<String> leading() {
        // Surefire and Gradle fork test JVMs with assertions on; a Java or Kotlin `assert` in a test
        // is a check the author wrote to run.
        return assertions ? List.of("-ea") : List.of();
    }

    /** The flags after the module's own JVM arguments. */
    public List<String> trailing() {
        // The nested engines and compiler workers under a suite must not record caches.
        return List.of("-Djk.plugin.class=" + RUNNER_PLUGIN_CLASS, "-Djk.worker.aot=off");
    }

    /** The {@code --class} patterns as one class-name regex for the runner; null for every class. */
    @Nullable
    String classFilter() {
        return classPatterns.isEmpty() ? null : JUnitClassFilter.patternRegex(classPatterns);
    }

    /** The runner's {@code --method=} arguments when a {@code --class} pattern names a method; else empty. */
    List<String> methodArgs() {
        return classPatterns.isEmpty() ? List.of() : JUnitClassFilter.methodArgs(classPatterns);
    }

    /**
     * The excluded classes as a regex alternation, with {@code settled} (classes that passed earlier
     * under the same stamp) by exact binary name; null when neither names one.
     */
    @Nullable
    String excludeBody(List<String> settled) {
        return JUnitClassFilter.excludeBody(excludeClasses, settled);
    }

    /** The runner's tag arguments, {@code extraExcludes} added to the excluded tags. */
    List<String> tagArgs(List<String> extraExcludes) {
        List<String> exclude = new ArrayList<>(excludeTags);
        for (String tag : extraExcludes) {
            if (!exclude.contains(tag)) exclude.add(tag);
        }
        List<String> out = new ArrayList<>();
        if (!includeTags.isEmpty()) out.add("--include-tags=" + String.join(",", includeTags));
        if (!exclude.isEmpty()) out.add("--exclude-tags=" + String.join(",", exclude));
        return out;
    }

    /**
     * The variables every test JVM of the launch gets unless its environment already names them: no
     * prompt on the developer's terminal, and on the fast tier every host but this one refused
     * ({@link Http#DENY_HOSTS_ENV}) and no engine started or taken over ({@link
     * EnginePaths#NO_SPAWN_ENV}).
     */
    public Map<String, String> env() {
        Map<String, String> env = new LinkedHashMap<>();
        // A suite JVM must never prompt on the controlling terminal or wait for a keystroke.
        env.put("JK_NONINTERACTIVE", "1");
        if (walled) {
            env.put(Http.DENY_HOSTS_ENV, Http.ANY_REMOTE_HOST);
            env.put(EnginePaths.NO_SPAWN_ENV, "1");
        }
        return env;
    }

    /** The stamp token of this launch: everything it spells, in the order the launch spells it. */
    public String token() {
        List<String> parts = new ArrayList<>(leading());
        parts.add("<jvm-args>");
        parts.addAll(trailing());
        parts.addAll(tagArgs(List.of()));
        String include = classFilter();
        if (include != null) parts.add("include=" + include);
        parts.addAll(methodArgs());
        String exclude = excludeBody(List.of());
        if (exclude != null) parts.add("exclude=" + exclude);
        if (!serialTags.isEmpty()) parts.add("serial-tags=" + String.join(",", serialTags));
        if (workerHomes) parts.add("worker-homes");
        if (failureSnapshot) parts.add("failure-snapshot");
        if (idleTimeoutMs > 0) parts.add("idle-timeout=" + idleTimeoutMs);
        new TreeMap<>(env()).forEach((name, value) -> parts.add("env:" + name + "=" + value));
        return String.join(" ", parts);
    }
}
