// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;
import java.util.Map;

/**
 * {@code [jlink]}: a custom runtime image of an application, {@code target/<name>-jri/}, holding a
 * runtime linked from the project JDK with the modules {@code jdeps} finds plus {@code modules},
 * the application jar and its runtime jars under {@code app/}, a {@code bin/start} launcher, and
 * (by default) a JVM startup cache trained by starting the image.
 *
 * @param modules JDK modules linked beside the ones {@code jdeps} reports: a service the code only
 *     reaches by reflection or {@link java.util.ServiceLoader}, such as {@code jdk.crypto.ec}
 * @param stripDebug whether the linked runtime drops debug attributes ({@code --strip-debug})
 * @param aotCache the startup cache the build trains into the image
 * @param jvmArgs JVM flags {@code bin/start} puts before the application, and every training run
 *     with them, since a cache is only valid for the flags it was trained under
 * @param args the application arguments {@code bin/start} passes when it is given none
 * @param startTimeoutSeconds how long a training or test start may run before it is asked to stop
 * @param testStart whether the build starts the image once to prove it runs; a startup cache
 *     needs that start, so {@code false} requires {@code aotCache = OFF}
 * @param trainingEnv environment variables the build's starts see and the image does not, such as a
 *     port that cannot collide with a running copy
 */
public record Jlink(
        List<String> modules,
        boolean stripDebug,
        AotCache aotCache,
        List<String> jvmArgs,
        List<String> args,
        int startTimeoutSeconds,
        boolean testStart,
        Map<String, String> trainingEnv) {

    /** Default {@code start-timeout}, in seconds. */
    public static final int DEFAULT_START_TIMEOUT = 60;

    /** The startup cache a {@code [jlink]} image carries. */
    public enum AotCache {
        /** A JEP 483/514 AOT cache on a JDK 25 or newer, a CDS archive on an older one. */
        AUTO,
        /** A dynamic CDS archive, whatever the JDK. */
        CDS,
        /** No cache. */
        OFF
    }

    public Jlink {
        modules = modules == null ? List.of() : List.copyOf(modules);
        aotCache = aotCache == null ? AotCache.AUTO : aotCache;
        jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
        args = args == null ? List.of() : List.copyOf(args);
        if (startTimeoutSeconds <= 0) startTimeoutSeconds = DEFAULT_START_TIMEOUT;
        trainingEnv = trainingEnv == null ? Map.of() : Map.copyOf(trainingEnv);
    }

    /** The table with nothing but defaults: {@code [jlink]} alone. */
    public static Jlink defaults() {
        return new Jlink(List.of(), true, AotCache.AUTO, List.of(), List.of(), DEFAULT_START_TIMEOUT, true, Map.of());
    }
}
