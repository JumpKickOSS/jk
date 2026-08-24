// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.resolver.Versions;
import cc.jumpkick.util.AppInstallConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineInstallTest {

    private static EngineInstall install(Path home) {
        return new EngineInstall(home.resolve("lib"));
    }

    private static EngineInstall install(Path home, AtomicLong clock) {
        return new EngineInstall(home.resolve("lib"), clock::get);
    }

    @Test
    void materialize_is_idempotent_crash_safe_and_cas_backed(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Cas cas = new Cas(Files.createDirectories(home.resolve("cache")));
        EngineInstall store = install(home);
        Path jar = Files.writeString(tmp.resolve("jk-engine-0.12.0.jar"), "engine-bytes");

        var m = store.materializeFromFiles("0.12.0", cas, jar);
        assertThat(m.engineJar()).hasContent("engine-bytes");
        assertThat(m.version()).isEqualTo("0.12.0");
        assertThat(m.engineJar().getFileName().toString()).isEqualTo("jk-engine-0.12.0.jar");
        assertThat(m.root()).isEqualTo(store.engineHome());
        assertThat(store.configFile()).exists();
        assertThat(AppInstallConfig.parse(Files.readString(store.configFile())))
                .containsEntry("jar", "jk-engine-0.12.0.jar")
                .containsEntry("version", "0.12.0");
        assertThat(m.engineJar()).isEqualTo(store.engineJarPath());
        assertThat(Files.isSameFile(m.engineJar(), cas.pathFor(cc.jumpkick.util.Hashing.sha256Hex(jar))))
                .isFalse();

        var again = store.materializeFromFiles("0.12.0", cas, jar);
        assertThat(again.engineJar()).isEqualTo(m.engineJar());
        try (var jars = Files.list(store.engineHome())) {
            assertThat(jars.filter(p -> p.getFileName().toString().endsWith(".jar"))
                            .toList())
                    .containsExactly(m.engineJar());
        }
    }

    @Test
    void survives_pointer_deletion_by_recovering_version_from_the_jar(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Cas cas = new Cas(Files.createDirectories(home.resolve("cache")));
        EngineInstall store = install(home);
        Path jar = Files.writeString(tmp.resolve("jk-engine-0.12.0.jar"), "engine-bytes");
        store.materializeFromFiles("0.12.0", cas, jar);
        assertThat(store.currentInstall()).isPresent();

        Files.delete(store.configFile());
        assertThat(store.configFile()).doesNotExist();

        var recovered = store.currentInstall();
        assertThat(recovered).as("engine still resolves after pointer deletion").isPresent();
        assertThat(recovered.orElseThrow().version()).isEqualTo("0.12.0");
        assertThat(recovered.orElseThrow().engineJar()).hasContent("engine-bytes");

        var healed = store.materializeFromFiles("0.12.0", cas, jar);
        assertThat(healed.engineJar()).isEqualTo(recovered.orElseThrow().engineJar());
        assertThat(store.configFile()).exists();
        assertThat(AppInstallConfig.parse(Files.readString(store.configFile())))
                .containsEntry("jar", "jk-engine-0.12.0.jar");
    }

    @Test
    void a_jk_config_property_cannot_override_the_computed_engine_digest(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Cas cas = new Cas(Files.createDirectories(home.resolve("cache")));
        EngineInstall store = install(home);
        Path jar = Files.writeString(tmp.resolve("jk-engine-0.12.0.jar"), "engine-bytes");
        String realSha = cc.jumpkick.util.Hashing.sha256Hex(jar);
        System.setProperty("jk-config.engine-sha256", "deadbeef");
        try {
            store.materializeFromFiles("0.12.0", cas, jar);
            assertThat(AppInstallConfig.parse(Files.readString(store.configFile())))
                    .containsEntry("engine-sha256", realSha);
        } finally {
            System.clearProperty("jk-config.engine-sha256");
        }
    }

    @Test
    void newest_is_the_live_install(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Cas cas = new Cas(Files.createDirectories(home.resolve("cache")));
        EngineInstall store = install(home);
        for (String v : new String[] {"0.9.2", "0.10.0", "0.12.0-SNAPSHOT"}) {
            Path jar = Files.writeString(tmp.resolve("engine-" + v + ".jar"), "e-" + v);
            store.materializeFromFiles(v, cas, jar);
        }
        assertThat(store.newest().orElseThrow().version()).isEqualTo("0.12.0-SNAPSHOT");
        Path release = Files.writeString(tmp.resolve("engine-release.jar"), "e-release");
        store.materializeFromFiles("0.12.0", cas, release);
        assertThat(store.newest().orElseThrow().version()).isEqualTo("0.12.0");
    }

    @Test
    void rematerializing_the_same_version_with_new_bytes_publishes_an_epoch_sibling(@TempDir Path dir)
            throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        var cas = new Cas(home.resolve("cache"));
        AtomicLong clock = new AtomicLong(1_724_400_000_000L);
        var store = install(home, clock);
        Path jarV1 = dir.resolve("engine-v1.jar");
        Files.writeString(jarV1, "engine bytes v1");
        Path jarV2 = dir.resolve("engine-v2.jar");
        Files.writeString(jarV2, "engine bytes v2 (rebuilt snapshot)");

        var first = store.materializeFromFiles("1.0.0-SNAPSHOT", cas, jarV1);
        assertThat(first.engineJar().getFileName().toString()).isEqualTo("jk-engine-1.0.0-SNAPSHOT.jar");
        assertThat(first.engineJar()).hasContent("engine bytes v1");

        var second = store.materializeFromFiles("1.0.0-SNAPSHOT", cas, jarV2);
        assertThat(second.engineJar().getFileName().toString()).isEqualTo("jk-engine-1.0.0-SNAPSHOT.1724400000000.jar");
        assertThat(second.engineJar()).hasContent("engine bytes v2 (rebuilt snapshot)");
        assertThat(first.engineJar()).hasContent("engine bytes v1");
        assertThat(store.resolve("1.0.0-SNAPSHOT").orElseThrow().engineJar()).isEqualTo(second.engineJar());

        var third = store.materializeFromFiles("1.0.0-SNAPSHOT", cas, jarV2);
        assertThat(third.engineJar()).isEqualTo(second.engineJar());
    }

    @Test
    void epoch_allocation_skips_names_that_already_exist(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        var cas = new Cas(home.resolve("cache"));
        AtomicLong clock = new AtomicLong(1_724_400_000_000L);
        var store = install(home, clock);
        store.materializeFromFiles("0.12.0", cas, Files.writeString(dir.resolve("v1.jar"), "first"));
        Files.writeString(store.engineHome().resolve("jk-engine-0.12.0.1724400000000.jar"), "occupied");

        var second = store.materializeFromFiles("0.12.0", cas, Files.writeString(dir.resolve("v2.jar"), "second"));
        assertThat(second.engineJar().getFileName().toString()).isEqualTo("jk-engine-0.12.0.1724400000001.jar");
        assertThat(second.engineJar()).hasContent("second");
    }

    @Test
    void materializing_over_a_torn_install_replaces_it(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        var cas = new Cas(home.resolve("cache"));
        var store = install(home);
        Path jar = dir.resolve("engine.jar");
        Files.writeString(jar, "engine bytes");

        Files.createDirectories(store.engineHome());
        Files.writeString(store.configFile(), "version = \"1.0.0\"\n");
        assertThat(store.currentInstall()).isEmpty();

        var healed = store.materializeFromFiles("1.0.0", cas, jar);
        assertThat(healed.engineJar()).hasContent("engine bytes");
        assertThat(store.resolve("1.0.0")).isPresent();
    }

    @Test
    void resolve_finds_a_leftover_jar_of_the_previous_version(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        var cas = new Cas(home.resolve("cache"));
        var store = install(home);
        Path oldJar = Files.writeString(dir.resolve("old.jar"), "old-engine");
        Path newJar = Files.writeString(dir.resolve("new.jar"), "new-engine");
        var v12 = store.materializeFromFiles("0.12.0", cas, oldJar);
        var v13 = store.materializeFromFiles("0.13.0", cas, newJar);

        assertThat(store.resolve("0.13.0").orElseThrow().engineJar()).isEqualTo(v13.engineJar());
        assertThat(store.resolve("0.12.0").orElseThrow().engineJar()).isEqualTo(v12.engineJar());
        assertThat(store.resolve("0.11.0")).isEmpty();
        assertThat(v12.engineJar()).hasContent("old-engine");
    }

    @Test
    void refuses_to_replace_a_newer_live_engine_with_an_older_one(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        var cas = new Cas(home.resolve("cache"));
        var store = install(home);
        Path newer = Files.writeString(dir.resolve("new.jar"), "newer");
        Path older = Files.writeString(dir.resolve("old.jar"), "older");
        store.materializeFromFiles("0.13.0", cas, newer);
        assertThatThrownBy(() -> store.materializeFromFiles("0.12.0", cas, older))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("refusing to replace")
                .hasMessageContaining("0.13.0");
        assertThat(store.currentInstall().orElseThrow().version()).isEqualTo("0.13.0");
    }

    /**
     * Every engine-install version shape, ascending. Ordering is {@link Versions#compare} — the
     * product's one version comparator — so this table is the contract the downgrade guard and the
     * lock's {@code jk-min} floor both read. Maven order: alpha &lt; beta &lt; milestone &lt; rc
     * &lt; snapshot &lt; release.
     */
    private static final String[] ASCENDING = {
        "0.9.2",
        "0.10.1",
        "0.11.0",
        "0.12.0-alpha1",
        "0.12.0-beta1",
        "0.12.0-M1",
        "0.12.0-rc2",
        "0.12.0-rc9",
        "0.12.0-rc10",
        "0.12.0-SNAPSHOT",
        "0.12.0",
        "0.12.1",
        "0.13.0",
        "1.0.0",
    };

    @Test
    void engine_install_versions_are_a_total_order_under_the_one_comparator() {
        for (int i = 0; i < ASCENDING.length; i++) {
            for (int j = 0; j < ASCENDING.length; j++) {
                String a = ASCENDING[i];
                String b = ASCENDING[j];
                int expected = Integer.compare(i, j);
                assertThat(Integer.signum(Versions.compare(a, b)))
                        .as("%s vs %s", a, b)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void release_synonyms_and_qualifier_case_are_the_same_engine_version() {
        for (String same : new String[] {"0.12", "0.12.0", "0.12.0-final", "0.12.0-ga", "0.12.0-release"}) {
            assertThat(Versions.compare(same, "0.12.0")).as(same).isZero();
        }
        assertThat(Versions.compare("0.12.0-RC2", "0.12.0-rc2")).isZero();
        assertThat(Versions.compare("0.12.0-SNAPSHOT", "0.12.0-snapshot")).isZero();
    }

    @Test
    void the_downgrade_guard_reads_rc_numbers_numerically(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        var cas = new Cas(home.resolve("cache"));
        var store = install(home);
        Path rc9 = Files.writeString(dir.resolve("rc9.jar"), "rc9");
        Path rc10 = Files.writeString(dir.resolve("rc10.jar"), "rc10");

        store.materializeFromFiles("0.12.0-rc9", cas, rc9);
        store.materializeFromFiles("0.12.0-rc10", cas, rc10);
        assertThat(store.currentInstall().orElseThrow().version()).isEqualTo("0.12.0-rc10");

        assertThatThrownBy(() -> store.materializeFromFiles("0.12.0-rc9", cas, rc9))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("refusing to replace")
                .hasMessageContaining("0.12.0-rc10");
        assertThat(store.currentInstall().orElseThrow().version()).isEqualTo("0.12.0-rc10");
    }

    @Test
    void a_final_tag_is_not_a_downgrade_from_its_own_release(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        var cas = new Cas(home.resolve("cache"));
        var store = install(home);
        store.materializeFromFiles("0.12.0", cas, Files.writeString(dir.resolve("ga.jar"), "ga"));

        var relabelled = store.materializeFromFiles("0.12.0-final", cas, Files.writeString(dir.resolve("f.jar"), "f"));
        assertThat(relabelled.version()).isEqualTo("0.12.0-final");
        assertThat(store.currentInstall().orElseThrow().version()).isEqualTo("0.12.0-final");
    }

    @Test
    void gc_deletes_retired_engine_jars_and_parked_clients(@TempDir Path homeRoot) throws Exception {
        Path home = Files.createDirectories(homeRoot.resolve("home"));
        EngineInstall store = install(home);
        Cas cas = new Cas(Files.createDirectories(home.resolve("cache")));
        Path liveJar = Files.writeString(homeRoot.resolve("live.jar"), "live");
        var live = store.materializeFromFiles("1.0.0", cas, liveJar);
        Files.writeString(store.engineHome().resolve("jk-engine-0.9.0.jar"), "previous");
        Files.writeString(store.engineHome().resolve("jk-engine-1.0.0.jar.old"), "legacy-park");

        Path state = Files.createDirectories(home.resolve("state"));
        Path aot = Files.createDirectories(state.resolve("aot"));
        Path staleCache = Files.writeString(aot.resolve("engine-0.9.0-aaaaaaaaaaaaaaaa.aot"), "x");
        Path liveEng = Files.writeString(aot.resolve("engine-1.0.0-eeeeeeeeeeeeeeee.aot"), "live-aot");
        Path liveWorker = Files.writeString(aot.resolve("java-compiler-1.0.0-ffffffffffffffff.aot"), "live-w");

        Path bin = Files.createDirectories(home.resolve("bin"));
        Path jkOld = Files.writeString(bin.resolve("jk.old"), "old-client");
        Path liveJk = Files.writeString(bin.resolve("jk"), "live-client");

        List<Path> pruned = store.gc(bin, state);

        assertThat(pruned).isNotEmpty();
        assertThat(store.engineHome().resolve("jk-engine-0.9.0.jar")).doesNotExist();
        assertThat(store.engineHome().resolve("jk-engine-1.0.0.jar.old")).doesNotExist();
        assertThat(staleCache).doesNotExist();
        assertThat(liveEng).exists();
        assertThat(liveWorker).exists();
        assertThat(jkOld).doesNotExist();
        assertThat(liveJk).exists();
        assertThat(live.engineJar()).hasContent("live");
        assertThat(store.configFile()).exists();
    }

    @Test
    void gc_after_same_version_rebuild_keeps_only_the_live_sibling(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        var cas = new Cas(home.resolve("cache"));
        AtomicLong clock = new AtomicLong(1_724_400_000_000L);
        var store = install(home, clock);
        var first = store.materializeFromFiles("0.12.0", cas, Files.writeString(dir.resolve("v1.jar"), "v1"));
        var second = store.materializeFromFiles("0.12.0", cas, Files.writeString(dir.resolve("v2.jar"), "v2"));
        assertThat(first.engineJar()).exists();
        assertThat(second.engineJar()).isNotEqualTo(first.engineJar());

        store.gc(null, null);

        assertThat(first.engineJar()).doesNotExist();
        assertThat(second.engineJar()).hasContent("v2");
        assertThat(store.currentInstall().orElseThrow().engineJar()).isEqualTo(second.engineJar());
    }

    @Test
    void gc_deletes_abandoned_config_dir_pointer(@TempDir Path tmp) throws Exception {
        Path cfg = Files.createDirectories(tmp.resolve("config/jk-engine"));
        Path stale = Files.writeString(cfg.resolve("config.toml"), "jar = \"jk-engine-0.12.0.jar\"\n");
        List<Path> removed = new ArrayList<>();
        EngineInstall.sweepAbandonedConfig(tmp.resolve("config"), removed);
        assertThat(stale).doesNotExist();
        assertThat(cfg).doesNotExist();
        assertThat(removed).contains(stale);
    }

    @Test
    void install_binaries_parks_previous_and_writes_jk_plus_jkx(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Files.writeString(bin.resolve("jk"), "old-jk");
        Files.writeString(bin.resolve("jkx"), "old-jkx");
        Path src = Files.writeString(tmp.resolve("new-jk"), "new-jk");

        EngineInstall.installBinaries(src, bin, false);

        assertThat(bin.resolve("jk")).hasContent("new-jk");
        assertThat(bin.resolve("jkx")).hasContent("new-jk");
        assertThat(bin.resolve("jk.old")).hasContent("old-jk");
        assertThat(bin.resolve("jkx.old")).hasContent("old-jkx");
    }

    @Test
    void parked_name_appends_old_after_the_full_filename() {
        assertThat(EngineInstall.parkedName(Path.of("jk")).getFileName().toString())
                .isEqualTo("jk.old");
        assertThat(EngineInstall.parkedName(Path.of("jk.exe")).getFileName().toString())
                .isEqualTo("jk.exe.old");
    }

    @Test
    void version_from_jar_name_strips_an_epoch_suffix() {
        assertThat(EngineInstall.versionFromJarName("jk-engine-0.12.0.jar")).contains("0.12.0");
        assertThat(EngineInstall.versionFromJarName("jk-engine-0.12.0.1724400000000.jar"))
                .contains("0.12.0");
        assertThat(EngineInstall.versionFromJarName("jk-engine-0.12.0-SNAPSHOT.jar"))
                .contains("0.12.0-SNAPSHOT");
        assertThat(EngineInstall.versionFromJarName("jk-engine-0.12.0-SNAPSHOT.1724400000000.jar"))
                .contains("0.12.0-SNAPSHOT");
        assertThat(EngineInstall.versionFromJarName("jk-engine-0.12.0.jar.old")).isEmpty();
        assertThat(EngineInstall.isEngineJarName("jk-engine-0.12.0.1724400000000.jar"))
                .isTrue();
        assertThat(EngineInstall.isEngineJarName("widget-1.0.0.jar")).isFalse();
    }

    @Test
    void wipe_aot_directory_keeps_live_version_only(@TempDir Path home) throws Exception {
        Path aot = Files.createDirectories(home.resolve("aot"));
        Path liveEng = Files.writeString(aot.resolve("engine-0.12.0-aaaaaaaaaaaaaaaa.aot"), "e");
        Path liveWorker = Files.writeString(aot.resolve("java-compiler-0.12.0-bbbbbbbbbbbbbbbb.aot"), "w");
        Path oldEng = Files.writeString(aot.resolve("engine-0.10.1-cccccccccccccccc.aot"), "old");
        Path legacyWorker = Files.writeString(aot.resolve("java-compiler-dddddddddddddddd.aot"), "legacy");
        Path snap = Files.writeString(aot.resolve("engine-0.12.0-SNAPSHOT-eeeeeeeeeeeeeeee.aot"), "snap");
        Path lock = Files.writeString(aot.resolve("aot.toml.lock"), "");
        cc.jumpkick.util.AotManifest.upsert(
                aot,
                cc.jumpkick.util.AotManifest.Entry.builder("engine-0.10.1-cccccccccccccccc.aot")
                        .tool("engine")
                        .status("ready")
                        .build());

        int removed = EngineInstall.wipeAotDirectory(aot, "0.12.0");

        assertThat(removed).isEqualTo(3);
        assertThat(liveEng).exists();
        assertThat(liveWorker).exists();
        assertThat(oldEng).doesNotExist();
        assertThat(legacyWorker).doesNotExist();
        assertThat(snap).doesNotExist();
        assertThat(lock).exists();
    }

    @Test
    void wipe_aot_without_keep_version_removes_everything(@TempDir Path home) throws Exception {
        Path aot = Files.createDirectories(home.resolve("aot"));
        Path eng = Files.writeString(aot.resolve("engine-0.12.0-aaaaaaaaaaaaaaaa.aot"), "e");
        Path worker = Files.writeString(aot.resolve("java-compiler-0.12.0-bbbbbbbbbbbbbbbb.aot"), "w");
        assertThat(EngineInstall.wipeAotDirectory(aot)).isEqualTo(2);
        assertThat(eng).doesNotExist();
        assertThat(worker).doesNotExist();
    }

    @Test
    void wipe_aot_is_noop_when_dir_missing(@TempDir Path home) {
        assertThat(EngineInstall.wipeAotDirectory(home.resolve("nope"))).isZero();
        assertThat(EngineInstall.wipeAotDirectory(null)).isZero();
        assertThat(EngineInstall.deleteSupersededEngineAot(home.resolve("nope"), "0.12.0"))
                .isZero();
    }

    @Test
    void belongs_to_product_version_requires_hex_key_after_version() {
        assertThat(EngineInstall.belongsToProductVersion("java-compiler-0.12.0-7aa4b5ac124595f3.aot", "0.12.0"))
                .isTrue();
        assertThat(EngineInstall.belongsToProductVersion("engine-0.12.0-e7e6bff34867f44e.aot", "0.12.0"))
                .isTrue();
        assertThat(EngineInstall.belongsToProductVersion("engine-0.12.0-SNAPSHOT-aaaaaaaaaaaaaaaa.aot", "0.12.0"))
                .isFalse();
        assertThat(EngineInstall.belongsToProductVersion("java-compiler-7aa4b5ac124595f3.aot", "0.12.0"))
                .isFalse();
        assertThat(EngineInstall.isPrimaryAotCacheName("java-compiler-0.12.0-abc.aot"))
                .isTrue();
        assertThat(EngineInstall.isPrimaryAotCacheName("java-compiler-abc.aot.noaot"))
                .isFalse();
        assertThat(EngineInstall.isParkedClientName("jk.old")).isTrue();
        assertThat(EngineInstall.isParkedClientName("jk.exe.old")).isTrue();
        assertThat(EngineInstall.isParkedClientName("jk")).isFalse();
    }
}
