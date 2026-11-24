// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.AccessLedger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreCasGcTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final Duration DAY = Duration.ofDays(1);

    private static byte[] macho(int salt) {
        byte[] b = new byte[2048];
        b[0] = (byte) 0xCF;
        b[1] = (byte) 0xFA;
        b[2] = (byte) 0xED;
        b[3] = (byte) 0xFE;
        b[4] = (byte) salt;
        return b;
    }

    private static byte[] zip(int size, int salt) {
        byte[] b = new byte[size];
        b[0] = 'P';
        b[1] = 'K';
        b[2] = (byte) salt;
        b[3] = (byte) (salt >> 8);
        return b;
    }

    /** Puts {@code data} in the CAS, {@code daysOld} days before {@link #NOW}; returns its sha. */
    private static String put(Cas cas, byte[] data, double daysOld) throws IOException {
        Path blob = cas.put(data);
        Files.setLastModifiedTime(blob, FileTime.fromMillis(NOW - (long) (daysOld * DAY.toMillis())));
        return cas.hashFromPath(blob).orElseThrow();
    }

    @Test
    void a_pinned_blob_survives_however_old(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        String pinned = put(cas, macho(1), 400);
        String loose = put(cas, macho(2), 400);

        StoreCasGc.Report report = StoreCasGc.run(
                cas, Set.of(pinned), Map.of(), StoreCasGc.Policy.defaults(1.0).withKeepNative(0), NOW, false);

        assertThat(cas.contains(pinned)).isTrue();
        assertThat(cas.contains(loose)).isFalse();
        assertThat(report.deleted()).isEqualTo(1);
        assertThat(report.pinned()).isEqualTo(1);
    }

    @Test
    void an_unpinned_native_goes_12_hours_after_its_last_use_and_the_newest_is_kept_for_a_rollback(@TempDir Path root)
            throws Exception {
        Cas cas = new Cas(root);
        double hour = 1.0 / 24;
        String newest = put(cas, macho(1), 20 * hour);
        String old = put(cas, macho(2), 30 * hour);
        String older = put(cas, macho(3), 10);

        StoreCasGc.Report report = StoreCasGc.run(cas, Set.of(), Map.of(), StoreCasGc.Policy.defaults(1.0), NOW, false);

        assertThat(cas.contains(newest))
                .as("past its 12 hours, but the newest unpinned native is kept for a rollback")
                .isTrue();
        assertThat(cas.contains(old)).isFalse();
        assertThat(cas.contains(older)).isFalse();
        assertThat(report.deleted()).isEqualTo(2);

        Cas young = new Cas(root.resolve("young"));
        String within = put(young, macho(4), 6 * hour);
        String keptNewest = put(young, macho(5), 2 * hour);
        StoreCasGc.run(young, Set.of(), Map.of(), StoreCasGc.Policy.defaults(1.0), NOW, false);
        assertThat(young.contains(within)).as("inside its 12 hours").isTrue();
        assertThat(young.contains(keptNewest)).isTrue();
    }

    /** A jar with {@code mainClass} in its manifest and one class at each of {@code classes}. */
    private static byte[] jar(String mainClass, String... classes) throws IOException {
        var out = new ByteArrayOutputStream();
        var mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        try (var jar = new JarOutputStream(out, mf)) {
            for (String c : classes) {
                jar.putNextEntry(new ZipEntry(c));
                jar.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
                jar.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void a_retired_installs_engine_and_worker_jars_go_once_the_minimum_age_passes(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        double hour = 1.0 / 24;
        String engine = put(cas, jar("cc.jumpkick.engine.EngineMain", "cc/jumpkick/engine/EngineMain.class"), 2 * hour);
        String worker = put(
                cas, jar("cc.jumpkick.plugin.process.PluginMain", "cc/jumpkick/testrunner/TestRunner.class"), 2 * hour);
        String thirdParty = put(
                cas,
                jar(
                        "cc.jumpkick.plugin.process.PluginMain",
                        "cc/jumpkick/plugin/process/PluginMain.class",
                        "com/acme/AcmePlugin.class"),
                2 * hour);
        String keptEngine = put(cas, jar("cc.jumpkick.engine.EngineMain", "cc/jumpkick/engine/A.class"), 2 * hour);
        String justShelved = put(cas, jar("cc.jumpkick.engine.EngineMain", "cc/jumpkick/engine/B.class"), 0.2 * hour);

        StoreCasGc.run(cas, Set.of(keptEngine), Map.of(), StoreCasGc.Policy.defaults(1.0), NOW, false);

        assertThat(cas.contains(engine)).as("a retired install's engine jar").isFalse();
        assertThat(cas.contains(worker))
                .as("a retired install's first-party worker jar")
                .isFalse();
        assertThat(cas.contains(thirdParty))
                .as("a third-party plugin under the same host keeps the ordinary jar window")
                .isTrue();
        assertThat(cas.contains(keptEngine))
                .as("pinned by the current or previous install")
                .isTrue();
        assertThat(cas.contains(justShelved)).as("younger than the minimum age").isTrue();
    }

    @Test
    void a_large_jar_goes_in_a_week_and_a_small_one_in_three(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        StoreCasGc.Policy policy = StoreCasGc.Policy.defaults(1.0);
        int large = (int) policy.largeBytes() + 1;
        String bigOld = put(cas, zip(large, 1), 8);
        String bigYoung = put(cas, zip(large, 2), 6);
        String smallEightDays = put(cas, zip(4096, 3), 8);
        String smallOld = put(cas, zip(4096, 4), 22);

        StoreCasGc.run(cas, Set.of(), Map.of(), policy, NOW, false);

        assertThat(cas.contains(bigOld)).isFalse();
        assertThat(cas.contains(bigYoung)).isTrue();
        assertThat(cas.contains(smallEightDays)).isTrue();
        assertThat(cas.contains(smallOld)).isFalse();
    }

    @Test
    void disk_pressure_shortens_every_window(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        String smallThreeDays = put(cas, zip(4096, 1), 3);

        StoreCasGc.run(cas, Set.of(), Map.of(), StoreCasGc.Policy.defaults(1.0), NOW, false);
        assertThat(cas.contains(smallThreeDays)).as("21 days of room").isTrue();

        StoreCasGc.run(cas, Set.of(), Map.of(), StoreCasGc.Policy.defaults(0.1), NOW, false);
        assertThat(cas.contains(smallThreeDays))
                .as("2.1 days of room at the emergency level")
                .isFalse();
    }

    @Test
    void even_under_emergency_pressure_a_blob_younger_than_the_minimum_age_stays(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        String fresh = put(cas, zip(4096, 1), 0.01);

        StoreCasGc.run(cas, Set.of(), Map.of(), StoreCasGc.Policy.defaults(0.01), NOW, false);

        assertThat(cas.contains(fresh)).isTrue();
    }

    @Test
    void a_dry_run_reports_and_removes_nothing(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        String old = put(cas, zip(4096, 1), 100);

        StoreCasGc.Report report = StoreCasGc.run(cas, Set.of(), Map.of(), StoreCasGc.Policy.defaults(1.0), NOW, true);

        assertThat(report.deleted()).isEqualTo(1);
        assertThat(cas.contains(old)).isTrue();
    }

    @Test
    void a_class_file_is_not_a_native_binary(@TempDir Path root) throws Exception {
        byte[] clazz = new byte[200];
        clazz[0] = (byte) 0xCA;
        clazz[1] = (byte) 0xFE;
        clazz[2] = (byte) 0xBA;
        clazz[3] = (byte) 0xBE;
        Path blob = Files.write(root.resolve("c"), clazz);

        assertThat(StoreCasGc.classify(blob, clazz.length, 50L << 20)).isEqualTo(StoreCasGc.Kind.OTHER);
    }

    @Test
    void a_blob_used_on_many_days_outlives_one_used_once(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        String once = put(cas, zip(4096, 1), 30);
        String often = put(cas, zip(4096, 2), 30);
        Map<String, AccessLedger.Use> uses = Map.of(
                StoreCasGc.key(often),
                new AccessLedger.Use(NOW - 40 * DAY.toMillis(), NOW - 30 * DAY.toMillis(), 20, 16));

        StoreCasGc.run(cas, Set.of(), uses, StoreCasGc.Policy.defaults(1.0), NOW, false);

        assertThat(cas.contains(once)).as("21 days, used once").isFalse();
        assertThat(cas.contains(often))
                .as("21 days x 3 for sixteen days of use")
                .isTrue();
    }

    @Test
    void a_recent_use_restarts_the_countdown_whatever_the_files_mtime(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        String used = put(cas, zip(4096, 1), 100);
        Map<String, AccessLedger.Use> uses = Map.of(
                StoreCasGc.key(used), new AccessLedger.Use(NOW - 100 * DAY.toMillis(), NOW - DAY.toMillis(), 2, 2));

        StoreCasGc.run(cas, Set.of(), uses, StoreCasGc.Policy.defaults(1.0), NOW, false);

        assertThat(cas.contains(used)).isTrue();
    }

    @Test
    void over_budget_the_blob_with_the_least_life_left_goes_first(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        String nearlyDone = put(cas, zip(4096, 1), 18);
        String fresh = put(cas, zip(4096, 2), 2);

        StoreCasGc.Report report = StoreCasGc.run(
                cas, Set.of(), Map.of(), StoreCasGc.Policy.defaults(1.0).withBudget(5000), NOW, false);

        assertThat(report.deleted()).isEqualTo(1);
        assertThat(cas.contains(nearlyDone)).isFalse();
        assertThat(cas.contains(fresh)).isTrue();
    }
}
