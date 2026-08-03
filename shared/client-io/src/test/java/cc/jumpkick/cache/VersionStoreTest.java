// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionStoreTest {

    @Test
    void materialize_is_idempotent_crash_safe_and_cas_backed(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(Files.createDirectories(tmp.resolve("cache")));
        VersionStore store = new VersionStore(tmp.resolve("versions"));
        Path jar = Files.writeString(tmp.resolve("engine.jar"), "engine-bytes");
        Path bin = Files.writeString(tmp.resolve("jk"), "client-bytes");

        var m = store.materializeFromFiles("0.11.0", cas, jar, bin);
        assertThat(m.engineJar()).hasContent("engine-bytes");
        assertThat(m.clientBin()).isPresent();
        assertThat(m.clientBin().get()).hasContent("client-bytes");
        assertThat(m.root().resolve(VersionStore.MANIFEST)).exists();
        // The CAS never shares an inode with the materialized (mutable-adjacent) tree.
        assertThat(Files.isSameFile(m.engineJar(), cas.pathFor(cc.jumpkick.util.Hashing.sha256Hex(jar))))
                .isFalse();

        // Idempotent: a second materialization returns the existing dir untouched.
        var again = store.materializeFromFiles("0.11.0", cas, jar, bin);
        assertThat(again.root()).isEqualTo(m.root());

        // A manifest-less dir is an aborted materialization: invisible to readers.
        Path aborted = Files.createDirectories(store.versionsDir().resolve("0.12.0/lib"));
        Files.writeString(aborted.resolve("jk-engine.jar"), "half");
        assertThat(store.resolve("0.12.0")).isEmpty();
    }

    @Test
    void newest_orders_versions_with_qualifiers_below_releases(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(Files.createDirectories(tmp.resolve("cache")));
        VersionStore store = new VersionStore(tmp.resolve("versions"));
        Path jar = Files.writeString(tmp.resolve("engine.jar"), "e");
        for (String v : new String[] {"0.9.2", "0.10.0", "0.11.0-SNAPSHOT"}) {
            store.materializeFromFiles(v, cas, jar, null);
        }
        assertThat(store.newest().orElseThrow().version()).isEqualTo("0.11.0-SNAPSHOT");
        store.materializeFromFiles("0.11.0", cas, jar, null);
        assertThat(store.newest().orElseThrow().version()).isEqualTo("0.11.0");

        assertThat(VersionStore.compare("0.10.0", "0.9.9")).isPositive();
        assertThat(VersionStore.compare("1.0.0-SNAPSHOT", "1.0.0")).isNegative();
        assertThat(VersionStore.compare("1.0.0", "1.0")).isZero();
    }
    /**
     * Same version, different bytes — a dev -SNAPSHOT re-install — must REPLACE the tree.
     * The short-circuit once kept a stale engine jar in place while a freshly-built client
     * believed it had installed itself (new client speaking to an old-protocol engine).
     */
    @Test
    void rematerializing_the_same_version_with_new_bytes_replaces_the_tree(@TempDir Path dir) throws Exception {
        var cas = new cc.jumpkick.cache.Cas(dir.resolve("cache"));
        var store = new VersionStore(dir.resolve("versions"));
        Path jarV1 = dir.resolve("engine-v1.jar");
        Files.writeString(jarV1, "engine bytes v1");
        Path jarV2 = dir.resolve("engine-v2.jar");
        Files.writeString(jarV2, "engine bytes v2 (rebuilt snapshot)");

        var first = store.materializeFromFiles("1.0.0-SNAPSHOT", cas, jarV1, null);
        assertThat(first.engineJar()).hasContent("engine bytes v1");

        var second = store.materializeFromFiles("1.0.0-SNAPSHOT", cas, jarV2, null);
        assertThat(second.engineJar()).hasContent("engine bytes v2 (rebuilt snapshot)");

        // And identical bytes short-circuit (release immutability fast path).
        var third = store.materializeFromFiles("1.0.0-SNAPSHOT", cas, jarV2, null);
        assertThat(third.root()).isEqualTo(second.root());
    }

    @Test
    void rematerializing_snapshot_with_new_client_bytes_replaces_client(@TempDir Path dir) throws Exception {
        // engine jar unchanged, client binary rebuilt — versions/ tree must update.
        var cas = new Cas(dir.resolve("cache"));
        var store = new VersionStore(dir.resolve("versions"));
        Path jar = dir.resolve("engine.jar");
        Files.writeString(jar, "engine same");
        Path clientV1 = dir.resolve("jk-v1");
        Files.writeString(clientV1, "client bytes v1");
        Path clientV2 = dir.resolve("jk-v2");
        Files.writeString(clientV2, "client bytes v2 (new dist)");

        var first = store.materializeFromFiles("0.11.0", cas, jar, clientV1);
        assertThat(first.clientBin()).isPresent();
        assertThat(first.clientBin().get()).hasContent("client bytes v1");

        var second = store.materializeFromFiles("0.11.0", cas, jar, clientV2);
        assertThat(second.clientBin()).isPresent();
        assertThat(second.clientBin().get()).hasContent("client bytes v2 (new dist)");
    }

    @Test
    void prune_retires_a_stale_version_with_its_engine_aot_caches(@TempDir Path home) throws Exception {
        VersionStore store = new VersionStore(home.resolve("versions"));
        java.nio.file.Path stale =
                java.nio.file.Files.createDirectories(home.resolve("versions").resolve("0.9.0"));
        java.nio.file.Files.writeString(stale.resolve("manifest.toml"), "");
        java.nio.file.Path state = java.nio.file.Files.createDirectories(home.resolve("state"));
        java.nio.file.Path legacy =
                java.nio.file.Files.createDirectories(state.resolve("engine").resolve("0.9.0"));
        java.nio.file.Path aot = java.nio.file.Files.createDirectories(state.resolve("aot"));
        java.nio.file.Path staleCache =
                java.nio.file.Files.writeString(aot.resolve("engine-0.9.0-aaaaaaaaaaaaaaaa.aot"), "x");
        java.nio.file.Path staleMarker =
                java.nio.file.Files.writeString(aot.resolve("engine-0.9.0-aaaaaaaaaaaaaaaa.aot.noaot"), "");
        // A version whose name merely EXTENDS the pruned one must survive its sweep.
        java.nio.file.Path lookalike =
                java.nio.file.Files.writeString(aot.resolve("engine-0.9.0-SNAPSHOT-bbbbbbbbbbbbbbbb.aot"), "y");
        java.nio.file.Path workerCache =
                java.nio.file.Files.writeString(aot.resolve("javac-cccccccccccccccc.aot"), "z");

        java.util.List<String> pruned = store.prune("1.0.0", java.time.Duration.ofDays(30), key -> 0L, state);

        org.assertj.core.api.Assertions.assertThat(pruned).containsExactly("0.9.0");
        org.assertj.core.api.Assertions.assertThat(stale).doesNotExist();
        org.assertj.core.api.Assertions.assertThat(legacy).doesNotExist();
        org.assertj.core.api.Assertions.assertThat(staleCache).doesNotExist();
        org.assertj.core.api.Assertions.assertThat(staleMarker).doesNotExist();
        org.assertj.core.api.Assertions.assertThat(lookalike).exists();
        org.assertj.core.api.Assertions.assertThat(workerCache).exists();
    }

    /** JK-1452: primary version keeps its engine AOT; every other version prefix is reaped. */
    @Test
    void delete_superseded_engine_aot_keeps_only_the_live_version(@TempDir Path home) throws Exception {
        Path aot = Files.createDirectories(home.resolve("aot"));
        Path keep =
                Files.writeString(aot.resolve("engine-0.11.0-aaaaaaaaaaaaaaaa.aot"), "live");
        Path keepMarker = Files.writeString(aot.resolve("engine-0.11.0-aaaaaaaaaaaaaaaa.noaot"), "");
        Path old =
                Files.writeString(aot.resolve("engine-0.10.1-bbbbbbbbbbbbbbbb.aot"), "old");
        Path oldMarker =
                Files.writeString(aot.resolve("engine-0.10.1-bbbbbbbbbbbbbbbb.aot.noaot"), "");
        Path snapshot =
                Files.writeString(aot.resolve("engine-0.10.1-SNAPSHOT-cccccccccccccccc.aot"), "snap");
        // Prefix-adjacent: must not treat 0.10.0 as matching keep 0.10.
        Path other = Files.writeString(aot.resolve("engine-0.10.0-dddddddddddddddd.aot"), "x");
        Path worker = Files.writeString(aot.resolve("java-compiler-eeeeeeeeeeeeeeee.aot"), "w");

        cc.jumpkick.util.AotManifest.upsert(
                aot,
                cc.jumpkick.util.AotManifest.Entry.builder("engine-0.10.1-bbbbbbbbbbbbbbbb.aot")
                        .tool("engine")
                        .jkVersion("0.10.1")
                        .status("ready")
                        .build());
        cc.jumpkick.util.AotManifest.upsert(
                aot,
                cc.jumpkick.util.AotManifest.Entry.builder("engine-0.11.0-aaaaaaaaaaaaaaaa.aot")
                        .tool("engine")
                        .jkVersion("0.11.0")
                        .status("ready")
                        .build());

        int removed = VersionStore.deleteSupersededEngineAot(aot, "0.11.0");

        assertThat(removed).isEqualTo(3); // 0.10.1, 0.10.1-SNAPSHOT, 0.10.0
        assertThat(keep).exists();
        assertThat(keepMarker).exists();
        assertThat(old).doesNotExist();
        assertThat(oldMarker).doesNotExist();
        assertThat(snapshot).doesNotExist();
        assertThat(other).doesNotExist();
        assertThat(worker).exists();

        List<String> files = cc.jumpkick.util.AotManifest.load(aot).stream()
                .map(cc.jumpkick.util.AotManifest.Entry::file)
                .toList();
        assertThat(files).contains("engine-0.11.0-aaaaaaaaaaaaaaaa.aot");
        assertThat(files).doesNotContain("engine-0.10.1-bbbbbbbbbbbbbbbb.aot");
    }

    @Test
    void delete_superseded_engine_aot_is_noop_when_dir_missing(@TempDir Path home) {
        assertThat(VersionStore.deleteSupersededEngineAot(home.resolve("nope"), "0.11.0")).isZero();
        assertThat(VersionStore.deleteSupersededEngineAot(null, "0.11.0")).isZero();
        assertThat(VersionStore.deleteSupersededEngineAot(home, "")).isZero();
    }

    /** Worker AOT is content-keyed but classpath pins versioned first-party jars — reap on upgrade. */
    @Test
    void delete_superseded_reaps_worker_aot_for_old_first_party_jars(@TempDir Path home) throws Exception {
        Path aot = Files.createDirectories(home.resolve("aot"));
        Path lib = Files.createDirectories(home.resolve("store/lib/jk-java-compiler"));
        Path liveJar = Files.writeString(lib.resolve("jk-java-compiler-0.11.0.jar"), "live-worker");
        Path sdk = Files.writeString(lib.resolve("plugin-sdk-0.1.0.jar"), "sdk");
        // Old jar path from a prior install — file gone, but AOT + manifest remain.
        Path missingOld = home.resolve("store/lib/jk-java-compiler/jk-java-compiler-0.10.1.jar");

        Path liveCache = Files.writeString(aot.resolve("java-compiler-aaaaaaaaaaaaaaaa.aot"), "live-aot");
        Path staleCache = Files.writeString(aot.resolve("java-compiler-bbbbbbbbbbbbbbbb.aot"), "stale-aot");
        Path engineKeep = Files.writeString(aot.resolve("engine-0.11.0-cccccccccccccccc.aot"), "eng");

        cc.jumpkick.util.AotManifest.upsert(
                aot,
                cc.jumpkick.util.AotManifest.Entry.builder("java-compiler-aaaaaaaaaaaaaaaa.aot")
                        .tool("java-compiler")
                        .status("ready")
                        .classpath(List.of(liveJar.toString(), sdk.toString()))
                        .build());
        cc.jumpkick.util.AotManifest.upsert(
                aot,
                cc.jumpkick.util.AotManifest.Entry.builder("java-compiler-bbbbbbbbbbbbbbbb.aot")
                        .tool("java-compiler")
                        .status("ready")
                        .classpath(List.of(missingOld.toString(), sdk.toString()))
                        .build());

        int removed = VersionStore.deleteSupersededEngineAot(aot, "0.11.0");

        assertThat(removed).isEqualTo(1);
        assertThat(liveCache).exists();
        assertThat(staleCache).doesNotExist();
        assertThat(engineKeep).exists();
        assertThat(cc.jumpkick.util.AotManifest.load(aot).stream()
                        .map(cc.jumpkick.util.AotManifest.Entry::file)
                        .toList())
                .contains("java-compiler-aaaaaaaaaaaaaaaa.aot")
                .doesNotContain("java-compiler-bbbbbbbbbbbbbbbb.aot");
    }

    @Test
    void delete_superseded_reaps_worker_aot_when_jar_version_mismatches_even_if_file_exists(
            @TempDir Path home) throws Exception {
        Path aot = Files.createDirectories(home.resolve("aot"));
        Path lib = Files.createDirectories(home.resolve("lib"));
        // Both jars on disk (unusual but possible mid-installLocal) — still drop non-keep version.
        Path oldJar = Files.writeString(lib.resolve("jk-java-compiler-0.10.1.jar"), "old");
        Path newJar = Files.writeString(lib.resolve("jk-java-compiler-0.11.0.jar"), "new");
        Path stale = Files.writeString(aot.resolve("java-compiler-dddddddddddddddd.aot"), "x");
        Path keep = Files.writeString(aot.resolve("java-compiler-eeeeeeeeeeeeeeee.aot"), "y");
        cc.jumpkick.util.AotManifest.upsert(
                aot,
                cc.jumpkick.util.AotManifest.Entry.builder("java-compiler-dddddddddddddddd.aot")
                        .tool("java-compiler")
                        .classpath(List.of(oldJar.toString()))
                        .build());
        cc.jumpkick.util.AotManifest.upsert(
                aot,
                cc.jumpkick.util.AotManifest.Entry.builder("java-compiler-eeeeeeeeeeeeeeee.aot")
                        .tool("java-compiler")
                        .classpath(List.of(newJar.toString()))
                        .build());

        assertThat(VersionStore.deleteSupersededEngineAot(aot, "0.11.0")).isEqualTo(1);
        assertThat(stale).doesNotExist();
        assertThat(keep).exists();
    }

    @Test
    void worker_classpath_stale_detects_missing_and_version_mismatch() {
        assertThat(VersionStore.workerClasspathStale(List.of(), "0.11.0")).isFalse();
        assertThat(VersionStore.workerClasspathStale(
                        List.of("/no/such/jk-java-compiler-0.11.0.jar"), "0.11.0"))
                .isTrue();
    }
}
