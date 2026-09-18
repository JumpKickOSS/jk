// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.PluginTunings;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PreferIpv4;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmOptionsTest {

    /** Install worker tuning onto the current session (mirrors what the CLI composition root does). */
    private static void installTuning(PluginTuning tuning) {
        SessionContext.install(SessionContext.current().withJvm(tuning));
    }

    /** {@code -XX:ActiveProcessorCount} scales with this host's core count, divided by concurrency. */
    private static int cores(int concurrency) {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / concurrency);
    }

    /** The hardening flags every worker fork gets, absent a user override, for the given concurrency. */
    private static List<String> hardening(int concurrency) {
        return List.of(
                "-XX:MaxMetaspaceSize=256m",
                "-XX:ActiveProcessorCount=" + cores(concurrency),
                "-Xss512k",
                PreferIpv4.JVM_FLAG,
                "-XX:+ExitOnOutOfMemoryError");
    }

    @Test
    void default_flags_are_50_percent_and_the_jvms_own_collector() {
        // No -XX:+Use*GC and no dedup by default: workers ride the JVM's default collector
        // (G1 on server-class machines) — see the DEFAULT_GC javadoc for why ZGC was abandoned.
        List<String> expected = new ArrayList<>(List.of("-XX:MaxRAMPercentage=50"));
        expected.addAll(hardening(1));
        assertThat(JvmOptions.flags(PluginTuning.NONE, 1)).containsExactlyElementsOf(expected);
    }

    @Test
    void suite_flags_keep_the_platform_thread_stack_and_metaspace() {
        // Test suites run on the JVM's default stack and metaspace, as Surefire's and Gradle's
        // forks do; the batch reserve and the metaspace cap are for compilers and plugin tools.
        List<String> suite = JvmOptions.suiteFlags(1);
        assertThat(suite).noneMatch(f -> f.startsWith("-Xss")).noneMatch(f -> f.startsWith("-XX:MaxMetaspaceSize"));
        List<String> expected = new ArrayList<>(JvmOptions.workerFlags(1));
        expected.remove("-Xss512k");
        expected.remove("-XX:MaxMetaspaceSize=256m");
        assertThat(suite).containsExactlyElementsOf(expected);
    }

    @Test
    void heap_cap_is_divided_across_concurrent_jvms() {
        // 4 concurrent test workers → each gets a quarter of the base cap.
        assertThat(JvmOptions.flags(PluginTuning.NONE, 4)).contains("-XX:MaxRAMPercentage=12.5");
    }

    @Test
    void explicit_settings_win_and_extra_args_append() {
        PluginTuning s = new PluginTuning(70.0, "g1", false, List.of("-XX:+AlwaysPreTouch"));
        List<String> expected = new ArrayList<>(List.of("-XX:MaxRAMPercentage=70", "-XX:+UseG1GC"));
        expected.addAll(hardening(1));
        expected.add("-XX:+AlwaysPreTouch");
        assertThat(JvmOptions.flags(s, 1)).containsExactlyElementsOf(expected);
        // string-dedup=false → no dedup flag; gc=g1 → G1, not ZGC.
    }

    @Test
    void gc_none_emits_no_collector_and_no_dedup() {
        PluginTuning s = new PluginTuning(null, "none", true, List.of());
        List<String> expected = new ArrayList<>(List.of("-XX:MaxRAMPercentage=50"));
        expected.addAll(hardening(1));
        assertThat(JvmOptions.flags(s, 1)).containsExactlyElementsOf(expected);
    }

    @Test
    void hardening_flags_skip_anything_the_caller_already_pinned() {
        PluginTuning s = new PluginTuning(
                null,
                "none",
                null,
                List.of(
                        "-XX:MaxMetaspaceSize=1g",
                        "-XX:ActiveProcessorCount=2",
                        "-Xss2m",
                        PreferIpv4.JVM_FLAG,
                        "-XX:+CrashOnOutOfMemoryError"));
        assertThat(JvmOptions.flags(s, 1))
                .containsExactly(
                        "-XX:MaxRAMPercentage=50",
                        "-XX:MaxMetaspaceSize=1g",
                        "-XX:ActiveProcessorCount=2",
                        "-Xss2m",
                        PreferIpv4.JVM_FLAG,
                        "-XX:+CrashOnOutOfMemoryError");
    }

    @org.junit.jupiter.api.BeforeEach
    void resetSharedPlan() {
        // Earlier tests in this JVM run real plans whose test step may size the shared heap
        // plan (JvmOptions.planAndApply); these assertions compare against unplanned defaults.
        JvmOptions.resetSharedPlanForTests();
    }

    @Test
    void launcher_flags_prefix_every_batch_flag_with_dash_j() {
        assertThat(JvmOptions.launcherFlags(1))
                .allMatch(f -> f.startsWith("-J-"))
                .hasSameSizeAs(JvmOptions.batchFlags(1))
                .contains("-J-XX:+UseParallelGC");
        // JEP 498 allow is host-feature gated; pin ≥ 23 so this assertion is stable on any runner.
        assertThat(JvmOptions.launcherFlags(1, 25)).contains("-J" + JvmOptions.JEP498_UNSAFE_MEMORY_ACCESS_ALLOW);
    }

    @Test
    void host_feature_reads_release_file(@TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("jdk-17");
        Files.createDirectories(home);
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"17.0.13\"\n");
        assertThat(JvmOptions.hostFeature(home)).isEqualTo(17);

        Path j25 = tmp.resolve("jdk-25");
        Files.createDirectories(j25);
        Files.writeString(j25.resolve("release"), "JAVA_VERSION=\"25.0.1\"\n");
        assertThat(JvmOptions.hostFeature(j25)).isEqualTo(25);
        assertThat(JvmOptions.hostFeatureFromExe(j25.resolve("bin").resolve("javac")))
                .isEqualTo(25);
    }

    @Test
    void batch_flags_default_to_parallel_gc_but_worker_flags_do_not() {
        // jk-owned batch forks (compilers, plugin tools) pin the throughput collector; test
        // workers ride the JVM's own default so user code sees the GC every other runner gives it.
        assertThat(JvmOptions.batchFlags(1)).contains("-XX:+UseParallelGC");
        assertThat(JvmOptions.workerFlags(1)).noneMatch(f -> f.startsWith("-XX:+Use") && f.endsWith("GC"));
    }

    @Test
    void batch_flags_acknowledge_jep498_unsafe_memory_access() {
        // Compilers host Lombok / KSP and friends; without allow, HotSpot dumps a four-line
        // terminal-deprecation banner on every compile. Test workers stay strict (user code).
        // Flag only exists on JDK 23+ — gate by host feature so older pins do not abort.
        assumeTrue(
                Runtime.version().feature() >= JvmOptions.JEP498_UNSAFE_MEMORY_ACCESS_MIN_FEATURE,
                "running JDK must be ≥ 23 for the default overload to emit the flag");
        assertThat(JvmOptions.batchFlags(1)).contains(JvmOptions.JEP498_UNSAFE_MEMORY_ACCESS_ALLOW);
        assertThat(JvmOptions.workerFlags(1)).doesNotContain(JvmOptions.JEP498_UNSAFE_MEMORY_ACCESS_ALLOW);
    }

    @Test
    void jep498_allow_is_gated_by_host_feature() {
        assertThat(JvmOptions.batchFlags(1, 17)).doesNotContain(JvmOptions.JEP498_UNSAFE_MEMORY_ACCESS_ALLOW);
        assertThat(JvmOptions.batchFlags(1, 21)).doesNotContain(JvmOptions.JEP498_UNSAFE_MEMORY_ACCESS_ALLOW);
        assertThat(JvmOptions.batchFlags(1, 22)).doesNotContain(JvmOptions.JEP498_UNSAFE_MEMORY_ACCESS_ALLOW);
        assertThat(JvmOptions.batchFlags(1, 23)).contains(JvmOptions.JEP498_UNSAFE_MEMORY_ACCESS_ALLOW);
        assertThat(JvmOptions.batchFlags(1, 25)).contains(JvmOptions.JEP498_UNSAFE_MEMORY_ACCESS_ALLOW);
        assertThat(JvmOptions.launcherFlags(1, 17)).doesNotContain("-J" + JvmOptions.JEP498_UNSAFE_MEMORY_ACCESS_ALLOW);
        assertThat(JvmOptions.launcherFlags(1, 25)).contains("-J" + JvmOptions.JEP498_UNSAFE_MEMORY_ACCESS_ALLOW);
    }

    @Test
    void gc_parallel_and_serial_are_recognized_names() {
        assertThat(JvmOptions.flags(new PluginTuning(null, "parallel", false, List.of()), 1))
                .contains("-XX:+UseParallelGC");
        assertThat(JvmOptions.flags(new PluginTuning(null, "serial", false, List.of()), 1))
                .contains("-XX:+UseSerialGC");
    }

    @Test
    void explicit_gc_pin_overrides_the_batch_default() {
        HeapPlan.Plan plan = new HeapPlan.Plan(1, 64L << 20, 256L << 20, 256L << 20, null);
        PluginTuning g1 = new PluginTuning(null, "g1", false, List.of());
        assertThat(JvmOptions.absoluteFlags(plan, g1, JvmOptions.BATCH_DEFAULT_GC))
                .contains("-XX:+UseG1GC")
                .doesNotContain("-XX:+UseParallelGC");
    }

    @Test
    void session_tuning_drives_worker_flags() {
        try {
            // The CLI carries resolved tuning on the session; worker forks read it back.
            installTuning(new PluginTuning(80.0, "g1", false, List.of()));
            List<String> expected = new ArrayList<>(List.of("-XX:MaxRAMPercentage=80", "-XX:+UseG1GC"));
            expected.addAll(hardening(1));
            assertThat(JvmOptions.workerFlags(1)).containsExactlyElementsOf(expected);
            // Concurrency still divides the resolved cap.
            assertThat(JvmOptions.workerFlags(4)).contains("-XX:MaxRAMPercentage=20");
        } finally {
            SessionContext.reset(); // don't leak into other tests
        }
    }

    @Test
    void absolute_flags_emit_xms_softmax_xmx_and_no_collector_by_default() {
        HeapPlan.Plan plan = new HeapPlan.Plan(4, 64L << 20, 512L << 20, 800L << 20, null);
        List<String> expected = new ArrayList<>(List.of("-Xms64m", "-Xmx800m", "-XX:SoftMaxHeapSize=512m"));
        expected.addAll(hardening(4)); // plan.parallelism() == 4
        assertThat(JvmOptions.absoluteFlags(plan, PluginTuning.NONE)).containsExactlyElementsOf(expected);
    }

    @Test
    void absolute_flags_pin_zgc_with_uncommit_when_asked() {
        HeapPlan.Plan plan = new HeapPlan.Plan(4, 64L << 20, 512L << 20, 800L << 20, null);
        PluginTuning zgc = new PluginTuning(null, "zgc", null, List.of());
        List<String> expected = new ArrayList<>(List.of(
                "-Xms64m",
                "-Xmx800m",
                "-XX:SoftMaxHeapSize=512m",
                "-XX:+UseZGC",
                "-XX:+ZUncommit",
                "-XX:ZUncommitDelay=10",
                "-XX:+UseStringDeduplication"));
        expected.addAll(hardening(4)); // plan.parallelism() == 4
        assertThat(JvmOptions.absoluteFlags(plan, zgc)).containsExactlyElementsOf(expected);
    }

    @Test
    void absolute_flags_skip_softmax_for_serial_gc() {
        HeapPlan.Plan plan = new HeapPlan.Plan(1, 64L << 20, 256L << 20, 256L << 20, null);
        PluginTuning serial = new PluginTuning(null, "none", false, List.of());
        List<String> expected = new ArrayList<>(List.of("-Xms64m", "-Xmx256m"));
        expected.addAll(hardening(1)); // plan.parallelism() == 1; no SoftMaxHeapSize, no collector
        assertThat(JvmOptions.absoluteFlags(plan, serial)).containsExactlyElementsOf(expected);
    }

    /**
     * A command whose fork is its only worker must not be sized like one of {@code jobs} parallel
     * builds. The process-wide plan divides both heap and cores by the job cap, which on a 20-core
     * host handed {@code jk format}'s single worker {@code -Xmx462m} and
     * {@code ActiveProcessorCount=1} — a serial formatter on nineteen idle cores.
     */
    @Test
    void sole_worker_flags_claim_the_whole_machine() {
        try {
            installTuning(PluginTuning.NONE);
            List<String> flags = JvmOptions.soleWorkerFlags();
            assertThat(flags)
                    .as("must override the plan's per-worker share, so these come last on the argv")
                    .contains("-XX:ActiveProcessorCount="
                            + Math.max(1, Runtime.getRuntime().availableProcessors()));
            assertThat(flags).anyMatch(f -> f.startsWith("-Xmx"));
            assertThat(flags).anyMatch(f -> f.startsWith("-Xms"));
        } finally {
            SessionContext.reset();
        }
    }

    /** The user's own memory pin is the answer; a sole fork does not get to double it. */
    @Test
    void sole_worker_flags_defer_to_an_explicit_heap_pin() {
        try {
            installTuning(new PluginTuning(null, null, null, List.of("-Xmx2g")));
            assertThat(JvmOptions.soleWorkerFlags()).isEmpty();
            installTuning(new PluginTuning(50.0, null, null, List.of()));
            assertThat(JvmOptions.soleWorkerFlags()).isEmpty();
        } finally {
            SessionContext.reset();
        }
    }

    @Test
    void auto_heap_disabled_when_user_pins_a_heap_flag() {
        try {
            installTuning(new PluginTuning(null, null, null, List.of("-Xmx2g")));
            assertThat(JvmOptions.autoHeapEnabled()).isFalse();
            installTuning(new PluginTuning(50.0, null, null, List.of()));
            assertThat(JvmOptions.autoHeapEnabled()).isFalse();
            installTuning(PluginTuning.NONE);
            assertThat(JvmOptions.autoHeapEnabled()).isTrue();
        } finally {
            SessionContext.reset();
        }
    }

    @Test
    void reads_jvm_table_from_toml(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, """
                group = "x"
                name = "y"
                version = "1"

                [jvm]
                max-ram-percent = 33
                gc = "g1"
                string-dedup = false
                args = ["-XX:+AlwaysPreTouch"]
                """);
        PluginTuning s = JkBuildParser.jvmTuning(toml);
        assertThat(s.maxRamPercent()).isEqualTo(33.0);
        assertThat(s.gc()).isEqualTo("g1");
        assertThat(s.stringDedup()).isFalse();
        assertThat(s.extraArgs()).containsExactly("-XX:+AlwaysPreTouch");
    }

    @Test
    void missing_jvm_table_is_empty(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, "group=\"x\"\nname=\"y\"\nversion=\"1\"\n");
        assertThat(JkBuildParser.jvmTuning(toml)).isEqualTo(PluginTuning.NONE);
    }

    /**
     * {@code [jvm]} is read by the manifest owner, so the owner's interpolation whitelist decides
     * what a {@code [jvm]} value may contain. A JVM flag sourced from the environment is exactly
     * what {@link cc.jumpkick.config.Interpolation} exists to refuse: it feeds a worker fork
     * without feeding the action key.
     */
    @Test
    void jvm_table_interpolation_is_rejected(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, """
                [jvm]
                gc = "${MY_GC}"
                """);
        assertThatThrownBy(() -> JkBuildParser.jvmTuning(toml))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("jvm.gc");
    }

    @Test
    void cli_overrides_toml(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group="x"
                name="y"
                version="1"
                [jvm]
                max-ram-percent = 30
                """);
        // CLI maxRam is non-null → wins over the toml layer regardless of env.
        PluginTuning cli = new PluginTuning(80.0, null, null, List.of());
        PluginTuning eff = PluginTunings.resolve(cli, dir);
        assertThat(eff.maxRamPercent()).isEqualTo(80.0);
    }
}
