// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.CallerEnv;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How one run's test JVMs reach the caller's environment: the module's policy, the environment
 * probe's flag (null when the JVMs run without it), the caller, the environment the JVMs start
 * with ({@link #unprobedEnvironment}) and, while they run, the broker they fetch through ({@link
 * EnvBroker}).
 */
public final class SuiteEnvDelivery implements AutoCloseable {

    private final SuiteEnvReads.Policy policy;
    private final @Nullable String probe;
    private final CallerEnv caller;
    private final WorkerEnv launchEnv;
    private final Path recordDir;
    private final @Nullable String unhooked;
    private @Nullable EnvBroker broker;

    private SuiteEnvDelivery(
            SuiteEnvReads.Policy policy,
            @Nullable String probe,
            CallerEnv caller,
            WorkerEnv launchEnv,
            Path recordDir,
            @Nullable String unhooked) {
        this.policy = policy;
        this.probe = probe;
        this.caller = caller;
        this.launchEnv = launchEnv;
        this.recordDir = recordDir;
        this.unhooked = unhooked;
    }

    /**
     * The delivery for {@code project}'s run on the JDK at {@code javaHome}, whose probe records into
     * {@code recordDir}: the probe cut under {@code cacheRoot} unless {@code classesDir} defines it
     * ({@link EnvProbeJar#prepare}), marking the classes from directories when {@code uses}. On a JDK whose process launch the probe cannot reach ({@link
     * EnvProbeHook}), a program a test started would see the test JVM's own environment alone: the
     * run's JVMs start without the probe instead, with the caller's whole environment, and the
     * suite is keyed on all of it ({@link #unhooked}).
     */
    public static SuiteEnvDelivery prepare(
            Path cacheRoot,
            Path recordDir,
            Path classesDir,
            Path javaHome,
            JkBuild project,
            WorkerEnv testEnv,
            boolean uses) {
        SuiteEnvReads.Policy policy = SuiteEnvReads.Policy.of(project);
        String probe = EnvProbeJar.prepare(cacheRoot, recordDir, classesDir, uses);
        CallerEnv caller = CallerEnv.current();
        String unhooked =
                probe == null || policy.strict() || !caller.attached() ? null : EnvProbeHook.unhooked(javaHome, probe);
        if (unhooked != null) probe = null;
        WorkerEnv launchEnv = unprobedEnvironment(testEnv, probe == null, policy, caller);
        return new SuiteEnvDelivery(policy, probe, caller, launchEnv, recordDir, unhooked);
    }

    /** The warning code of a run whose JDK refused the probe's hook on its process launch. */
    public static final String UNHOOKED = "suite-env-unhooked";

    /**
     * The line a run says when its JDK refused the probe's hook ({@link EnvProbeHook}): its test
     * JVMs got the caller's whole environment, keyed on every value; null when the hook took.
     */
    public @Nullable String unhooked() {
        return unhooked == null
                ? null
                : "this JDK does not let jk hand your shell to the programs a test starts (" + unhooked
                        + "), so the test JVMs got your whole environment and the suite is keyed on all of it";
    }

    /** The probe's flag every test JVM starts with, or null when they run without it. */
    public @Nullable String probe() {
        return probe;
    }

    /** The environment the run's test JVMs start with. */
    public WorkerEnv launchEnv() {
        return launchEnv;
    }

    /** Start the run's broker, last before the launch: none for a strict module or JVMs without the probe. */
    public void start() {
        if (probe != null) broker = EnvBroker.start(recordDir, caller, policy);
    }

    /** Stop the broker: the JVMs are done. */
    @Override
    public void close() {
        EnvBroker b = broker;
        if (b != null) b.close();
    }

    /**
     * What one run's JVMs read of the caller's environment, whether a question went unanswered
     * because the client had gone ({@code lost}: such a run stores no marker, since what its tests
     * saw of a variable no one could answer for is not what the key would say), and whether a
     * client sent the run at all ({@code attached}).
     */
    public record Seen(SuiteEnvReads.Record record, boolean lost, boolean attached) {
        public static final Seen NONE = new Seen(SuiteEnvReads.Record.NONE, false, false);
    }

    /**
     * What the run read: what its broker handed over; for JVMs that ran without the probe under a
     * module that is not strict, the whole environment they were given.
     */
    public Seen seen() {
        EnvBroker b = broker;
        if (b != null) return new Seen(b.record(), b.lost(), b.attached());
        if (probe == null && !policy.strict())
            return new Seen(new SuiteEnvReads.Record(Set.of(), false, true), caller.lost(), caller.attached());
        return Seen.NONE;
    }

    /**
     * The environment of a suite whose JVMs run without the environment probe ({@code unprobed}):
     * none fetches what it reads, so each gets the caller's whole environment beneath what
     * JumpKick sets — the least surprise when nothing can be delivered just in time — and the suite
     * is keyed on all of it ({@link #seen}). A strict module's JVMs get the allow-list, probe or
     * not; so does a build with no caller.
     */
    static WorkerEnv unprobedEnvironment(
            WorkerEnv testEnv, boolean unprobed, SuiteEnvReads.Policy policy, CallerEnv caller) {
        if (!unprobed || policy.strict()) return testEnv;
        Map<String, String> all = caller.all();
        if (all == null || all.isEmpty()) return testEnv;
        Map<String, String> visible = new LinkedHashMap<>();
        all.forEach((name, value) -> {
            if (policy.visible(name) || policy.held(name)) visible.put(name, value);
        });
        return testEnv.withDefaults(visible);
    }
}
