// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineInstallTest {

    @Test
    void materialize_is_idempotent_crash_safe_and_cas_backed(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(Files.createDirectories(tmp.resolve("cache")));
        EngineInstall store = new EngineInstall(tmp.resolve("lib"));
        Path jar = Files.writeString(tmp.resolve("engine.jar"), "engine-bytes");

        var m = store.materializeFromFiles("0.12.0", cas, jar);
        assertThat(m.engineJar()).hasContent("engine-bytes");
        assertThat(m.version()).isEqualTo("0.12.0");
        assertThat(m.root().resolve(EngineInstall.MANIFEST)).exists();
        assertThat(m.engineJar()).isEqualTo(store.engineJarPath());
        // The CAS never shares an inode with the launchable copy.
        assertThat(Files.isSameFile(m.engineJar(), cas.pathFor(cc.jumpkick.util.Hashing.sha256Hex(jar))))
                .isFalse();

        var again = store.materializeFromFiles("0.12.0", cas, jar);
        assertThat(again.engineJar()).isEqualTo(m.engineJar());
        assertThat(store.engineJarOldPath()).doesNotExist();

        Files.createDirectories(store.libDir());
        Files.writeString(store.libDir().resolve("jk-engine.jar"), "half");
        Files.deleteIfExists(store.libDir().resolve(EngineInstall.MANIFEST));
        // A jar without toml is incomplete: invisible as a live install of a different version.
        assertThat(store.resolve("0.13.0")).isEmpty();
    }

    @Test
    void newest_is_the_live_install(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(Files.createDirectories(tmp.resolve("cache")));
        EngineInstall store = new EngineInstall(tmp.resolve("lib"));
        for (String v : new String[] {"0.9.2", "0.10.0", "0.12.0-SNAPSHOT"}) {
            Path jar = Files.writeString(tmp.resolve("engine-" + v + ".jar"), "e-" + v);
            store.materializeFromFiles(v, cas, jar);
        }
        assertThat(store.newest().orElseThrow().version()).isEqualTo("0.12.0-SNAPSHOT");
        Path release = Files.writeString(tmp.resolve("engine-release.jar"), "e-release");
        store.materializeFromFiles("0.12.0", cas, release);
        assertThat(store.newest().orElseThrow().version()).isEqualTo("0.12.0");

        assertThat(EngineInstall.compare("0.10.0", "0.9.9")).isPositive();
        assertThat(EngineInstall.compare("1.0.0-SNAPSHOT", "1.0.0")).isNegative();
        assertThat(EngineInstall.compare("1.0.0", "1.0")).isZero();
    }

    @Test
    void rematerializing_the_same_version_with_new_bytes_parks_the_previous_jar(@TempDir Path dir) throws Exception {
        var cas = new Cas(dir.resolve("cache"));
        var store = new EngineInstall(dir.resolve("lib"));
        Path jarV1 = dir.resolve("engine-v1.jar");
        Files.writeString(jarV1, "engine bytes v1");
        Path jarV2 = dir.resolve("engine-v2.jar");
        Files.writeString(jarV2, "engine bytes v2 (rebuilt snapshot)");

        var first = store.materializeFromFiles("1.0.0-SNAPSHOT", cas, jarV1);
        assertThat(first.engineJar()).hasContent("engine bytes v1");

        var second = store.materializeFromFiles("1.0.0-SNAPSHOT", cas, jarV2);
        assertThat(second.engineJar()).hasContent("engine bytes v2 (rebuilt snapshot)");
        assertThat(store.engineJarOldPath()).hasContent("engine bytes v1");
        assertThat(store.resolve("1.0.0-SNAPSHOT").orElseThrow().engineJar()).isEqualTo(second.engineJar());

        var third = store.materializeFromFiles("1.0.0-SNAPSHOT", cas, jarV2);
        assertThat(third.engineJar()).isEqualTo(second.engineJar());
    }

    @Test
    void materializing_over_a_torn_install_replaces_it(@TempDir Path dir) throws Exception {
        var cas = new Cas(dir.resolve("cache"));
        var store = new EngineInstall(dir.resolve("lib"));
        Path jar = dir.resolve("engine.jar");
        Files.writeString(jar, "engine bytes");

        Files.createDirectories(store.libDir());
        Files.writeString(store.libDir().resolve(EngineInstall.MANIFEST), "version = \"1.0.0\"\n");
        assertThat(store.currentInstall()).isEmpty();

        var healed = store.materializeFromFiles("1.0.0", cas, jar);
        assertThat(healed.engineJar()).hasContent("engine bytes");
        assertThat(store.resolve("1.0.0")).isPresent();
    }

    @Test
    void resolve_pairs_the_client_version_with_live_or_parked_jar(@TempDir Path dir) throws Exception {
        var cas = new Cas(dir.resolve("cache"));
        var store = new EngineInstall(dir.resolve("lib"));
        Path oldJar = Files.writeString(dir.resolve("old.jar"), "old-engine");
        Path newJar = Files.writeString(dir.resolve("new.jar"), "new-engine");
        store.materializeFromFiles("0.12.0", cas, oldJar);
        store.materializeFromFiles("0.13.0", cas, newJar);

        assertThat(store.resolve("0.13.0").orElseThrow().engineJar()).isEqualTo(store.engineJarPath());
        assertThat(store.resolve("0.12.0").orElseThrow().engineJar()).isEqualTo(store.engineJarOldPath());
        assertThat(store.resolve("0.11.0")).isEmpty();
    }

    @Test
    void refuses_to_replace_a_newer_live_engine_with_an_older_one(@TempDir Path dir) throws Exception {
        var cas = new Cas(dir.resolve("cache"));
        var store = new EngineInstall(dir.resolve("lib"));
        Path newer = Files.writeString(dir.resolve("new.jar"), "newer");
        Path older = Files.writeString(dir.resolve("old.jar"), "older");
        store.materializeFromFiles("0.13.0", cas, newer);
        assertThatThrownBy(() -> store.materializeFromFiles("0.12.0", cas, older))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("refusing to replace")
                .hasMessageContaining("0.13.0");
        assertThat(store.currentInstall().orElseThrow().version()).isEqualTo("0.13.0");
    }

    @Test
    void gc_deletes_parked_engine_legacy_versions_and_parked_clients(@TempDir Path home) throws Exception {
        EngineInstall store = new EngineInstall(home.resolve("lib"));
        Cas cas = new Cas(Files.createDirectories(home.resolve("cache")));
        Path liveJar = Files.writeString(home.resolve("live.jar"), "live");
        store.materializeFromFiles("1.0.0", cas, liveJar);

        Files.writeString(store.engineJarOldPath(), "previous");
        Files.writeString(store.libDir().resolve(EngineInstall.MANIFEST_OLD), "version = \"0.9.0\"\n");
        Path versioned = Files.writeString(store.libDir().resolve("jk-engine-0.9.0.jar"), "leftover");

        Path versions = Files.createDirectories(home.resolve("versions").resolve("0.9.0"));
        Files.writeString(versions.resolve("manifest.toml"), "");
        Path state = Files.createDirectories(home.resolve("state"));
        Path legacy = Files.createDirectories(state.resolve("engine").resolve("0.9.0"));
        Path aot = Files.createDirectories(state.resolve("aot"));
        Path staleCache = Files.writeString(aot.resolve("engine-0.9.0-aaaaaaaaaaaaaaaa.aot"), "x");
        Path staleMarker = Files.writeString(aot.resolve("engine-0.9.0-aaaaaaaaaaaaaaaa.aot.noaot"), "");
        Path staleWorker = Files.writeString(aot.resolve("java-compiler-0.9.0-dddddddddddddddd.aot"), "w");
        Path lookalike = Files.writeString(aot.resolve("engine-0.9.0-SNAPSHOT-bbbbbbbbbbbbbbbb.aot"), "y");
        Path unversioned = Files.writeString(aot.resolve("javac-cccccccccccccccc.aot"), "z");
        Path liveEng = Files.writeString(aot.resolve("engine-1.0.0-eeeeeeeeeeeeeeee.aot"), "live-aot");
        Path liveWorker = Files.writeString(aot.resolve("java-compiler-1.0.0-ffffffffffffffff.aot"), "live-w");

        Path bin = Files.createDirectories(home.resolve("bin"));
        Path jkOld = Files.writeString(bin.resolve("jk.old"), "old-client");
        Path jkExeOld = Files.writeString(bin.resolve("jk.exe.old"), "old-win");
        Path liveJk = Files.writeString(bin.resolve("jk"), "live-client");

        List<Path> pruned = store.gc(bin, home.resolve("versions"), state);

        assertThat(pruned).isNotEmpty();
        assertThat(store.engineJarOldPath()).doesNotExist();
        assertThat(versioned).doesNotExist();
        assertThat(home.resolve("versions")).doesNotExist();
        assertThat(legacy).doesNotExist();
        assertThat(staleCache).doesNotExist();
        assertThat(staleMarker).doesNotExist();
        assertThat(staleWorker).doesNotExist();
        assertThat(lookalike).doesNotExist();
        assertThat(unversioned).doesNotExist();
        assertThat(liveEng).exists();
        assertThat(liveWorker).exists();
        assertThat(jkOld).doesNotExist();
        assertThat(jkExeOld).doesNotExist();
        assertThat(liveJk).exists();
        assertThat(store.engineJarPath()).hasContent("live");
    }

    @Test
    void gc_reaps_old_aot_when_only_the_parked_jar_remains(@TempDir Path home) throws Exception {
        EngineInstall install = new EngineInstall(home.resolve("lib"));
        Cas cas = new Cas(Files.createDirectories(home.resolve("cache")));
        install.materializeFromFiles("1.0.0", cas, Files.writeString(home.resolve("live.jar"), "live"));
        Files.writeString(install.engineJarOldPath(), "previous");
        Files.writeString(install.libDir().resolve(EngineInstall.MANIFEST_OLD), "version = \"0.9.0\"\n");

        Path aot = Files.createDirectories(home.resolve("state/aot"));
        Path oldEng = Files.writeString(aot.resolve("engine-0.9.0-aaaaaaaaaaaaaaaa.aot"), "old");
        Path oldWorker = Files.writeString(aot.resolve("java-compiler-0.9.0-bbbbbbbbbbbbbbbb.aot"), "old-w");
        Path liveEng = Files.writeString(aot.resolve("engine-1.0.0-cccccccccccccccc.aot"), "live");

        install.gc(home.resolve("bin"), home.resolve("versions"), home.resolve("state"));

        assertThat(install.engineJarOldPath()).doesNotExist();
        assertThat(oldEng).doesNotExist();
        assertThat(oldWorker).doesNotExist();
        assertThat(liveEng).exists();
    }

    @Test
    void migrate_copies_newest_legacy_versions_tree_into_product_lib(@TempDir Path home) throws Exception {
        Path versions = home.resolve("versions");
        Path old = Files.createDirectories(versions.resolve("0.9.0/lib"));
        Files.writeString(old.resolve("jk-engine.jar"), "old");
        Files.writeString(versions.resolve("0.9.0/manifest.toml"), "version = \"0.9.0\"\nengine-sha256 = \"aa\"\n");
        Path newer = Files.createDirectories(versions.resolve("0.12.0/lib"));
        Files.writeString(newer.resolve("jk-engine.jar"), "new-engine");
        Files.writeString(versions.resolve("0.12.0/manifest.toml"), "version = \"0.12.0\"\nengine-sha256 = \"bb\"\n");

        EngineInstall store = new EngineInstall(home.resolve("lib"));
        store.tryMigrate(versions);
        assertThat(store.currentInstall().orElseThrow().version()).isEqualTo("0.12.0");
        assertThat(store.engineJarPath()).hasContent("new-engine");
        assertThat(newer.resolve("jk-engine.jar")).exists(); // drain copy stays until gc
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
        assertThat(EngineInstall.isEngineCruftName("jk-engine-0.12.0.jar")).isTrue();
        assertThat(EngineInstall.isEngineCruftName("jk-engine.jar")).isFalse();
        assertThat(EngineInstall.isParkedClientName("jk.old")).isTrue();
        assertThat(EngineInstall.isParkedClientName("jk.exe.old")).isTrue();
        assertThat(EngineInstall.isParkedClientName("jk")).isFalse();
    }
}
