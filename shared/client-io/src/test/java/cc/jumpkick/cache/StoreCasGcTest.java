// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Set;
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
                cas, Set.of(pinned), StoreCasGc.Policy.defaults(1.0).withKeepNative(0), NOW, false);

        assertThat(cas.contains(pinned)).isTrue();
        assertThat(cas.contains(loose)).isFalse();
        assertThat(report.deleted()).isEqualTo(1);
        assertThat(report.pinned()).isEqualTo(1);
    }

    @Test
    void the_two_newest_unpinned_natives_are_kept_whatever_their_age_and_the_older_ones_go(@TempDir Path root)
            throws Exception {
        Cas cas = new Cas(root);
        String fresh = put(cas, macho(1), 0.01);
        String newest = put(cas, macho(2), 10);
        String second = put(cas, macho(3), 20);
        String third = put(cas, macho(4), 30);

        StoreCasGc.Report report = StoreCasGc.run(cas, Set.of(), StoreCasGc.Policy.defaults(1.0), NOW, false);

        assertThat(cas.contains(fresh))
                .as("the newest, and younger than the minimum age")
                .isTrue();
        assertThat(cas.contains(newest))
                .as("the second newest: kept whatever its age")
                .isTrue();
        assertThat(cas.contains(second))
                .as("past its three days and not among the two newest")
                .isFalse();
        assertThat(cas.contains(third)).isFalse();
        assertThat(report.deleted()).isEqualTo(2);
        assertThat(report.freedBytes()).isEqualTo(2 * 2048);
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

        StoreCasGc.run(cas, Set.of(), policy, NOW, false);

        assertThat(cas.contains(bigOld)).isFalse();
        assertThat(cas.contains(bigYoung)).isTrue();
        assertThat(cas.contains(smallEightDays)).isTrue();
        assertThat(cas.contains(smallOld)).isFalse();
    }

    @Test
    void disk_pressure_shortens_every_window(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        String smallThreeDays = put(cas, zip(4096, 1), 3);

        StoreCasGc.run(cas, Set.of(), StoreCasGc.Policy.defaults(1.0), NOW, false);
        assertThat(cas.contains(smallThreeDays)).as("21 days of room").isTrue();

        StoreCasGc.run(cas, Set.of(), StoreCasGc.Policy.defaults(0.1), NOW, false);
        assertThat(cas.contains(smallThreeDays))
                .as("2.1 days of room at the emergency level")
                .isFalse();
    }

    @Test
    void even_under_emergency_pressure_a_blob_younger_than_the_minimum_age_stays(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        String fresh = put(cas, zip(4096, 1), 0.01);

        StoreCasGc.run(cas, Set.of(), StoreCasGc.Policy.defaults(0.01), NOW, false);

        assertThat(cas.contains(fresh)).isTrue();
    }

    @Test
    void a_dry_run_reports_and_removes_nothing(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        String old = put(cas, zip(4096, 1), 100);

        StoreCasGc.Report report = StoreCasGc.run(cas, Set.of(), StoreCasGc.Policy.defaults(1.0), NOW, true);

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
}
