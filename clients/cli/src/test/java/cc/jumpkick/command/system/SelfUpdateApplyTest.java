// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.PreparedUpdate;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.testing.PropertyRoots;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The install half of {@code jk self update} over a throwaway jk home: a prepared update moves the
 * engine pointer and the PATH client, then its record goes; an older one goes down only when the
 * update asked for that version by name.
 */
class SelfUpdateApplyTest {

    private final List<PropertyRoots.Redirect> roots = new ArrayList<>();

    @BeforeEach
    void isolate() throws IOException {
        for (String name : List.of("JK_HOME", "JK_STORE_DIR", "JK_STATE_DIR", "JK_CACHE_DIR")) {
            roots.add(PropertyRoots.redirect("jk.env." + name, "jk-self-apply"));
        }
    }

    @AfterEach
    void restore() {
        roots.forEach(PropertyRoots::restore);
    }

    private static PreparedUpdate prepared(Cas cas, String version) throws IOException {
        return prepared(cas, version, PreparedUpdate.Client.NATIVE);
    }

    private static PreparedUpdate prepared(Cas cas, String version, PreparedUpdate.Client kind) throws IOException {
        byte[] engine = ("engine " + version).getBytes(StandardCharsets.UTF_8);
        byte[] client = ("client " + version).getBytes(StandardCharsets.UTF_8);
        cas.put(engine, Hashing.sha256Hex(engine));
        cas.put(client, Hashing.sha256Hex(client));
        PreparedUpdate p = new PreparedUpdate(
                version,
                "0.16.2",
                Hashing.sha256Hex(engine),
                kind,
                Hashing.sha256Hex(client),
                Instant.parse("2026-10-30T12:00:00Z"));
        p.write(EngineInstall.current().engineHome());
        return p;
    }

    @Test
    void a_prepared_update_moves_the_engine_and_the_client_then_drops_its_record() throws Exception {
        EngineInstall install = EngineInstall.current();
        Cas cas = JkStores.storeCas();

        SelfCommand.UpdateSub.apply(prepared(cas, "0.17.0"), install, cas, false);

        assertThat(install.currentInstall().orElseThrow().version()).isEqualTo("0.17.0");
        assertThat(Files.readString(EngineInstall.pathClient(JkDirs.binDir()))).isEqualTo("client 0.17.0");
        assertThat(PreparedUpdate.read(install.engineHome())).isEmpty();
    }

    @Test
    void an_older_release_goes_down_only_when_it_was_asked_for() throws Exception {
        EngineInstall install = EngineInstall.current();
        Cas cas = JkStores.storeCas();
        SelfCommand.UpdateSub.apply(prepared(cas, "0.17.0"), install, cas, false);

        PreparedUpdate older = prepared(cas, "0.16.2");
        assertThatThrownBy(() -> SelfCommand.UpdateSub.apply(older, install, cas, false))
                .hasMessageContaining("refusing to replace");
        assertThat(Files.readString(EngineInstall.pathClient(JkDirs.binDir())))
                .as("a refused update moves nothing")
                .isEqualTo("client 0.17.0");
        SelfCommand.UpdateSub.apply(older, install, cas, true);

        assertThat(install.currentInstall().orElseThrow().version()).isEqualTo("0.16.2");
        assertThat(Files.readString(EngineInstall.pathClient(JkDirs.binDir()))).isEqualTo("client 0.16.2");
    }

    /**
     * An old client cannot start against a pointer moved to a newer engine, so a client step that
     * fails (a full disk, a locked binary, Ctrl-C) must find the pointer where it was.
     */
    @Test
    void a_client_that_cannot_be_replaced_leaves_the_engine_pointer_where_it_was() throws Exception {
        EngineInstall install = EngineInstall.current();
        Cas cas = JkStores.storeCas();
        SelfCommand.UpdateSub.apply(prepared(cas, "0.17.0"), install, cas, false);
        PreparedUpdate next = prepared(cas, "0.18.0");
        Path bin = JkDirs.binDir();
        PathUtil.deleteRecursively(bin);
        Files.writeString(bin, "a file where the bin directory goes");

        assertThatThrownBy(() -> SelfCommand.UpdateSub.apply(next, install, cas, false))
                .isInstanceOf(IOException.class);

        assertThat(install.currentInstall().orElseThrow().version()).isEqualTo("0.17.0");
    }

    /** The home the installers leave: the engine materialized, the client copied in and named nowhere. */
    private static void installedByTheInstaller(EngineInstall install, Cas cas, String version) throws IOException {
        byte[] engine = ("engine " + version).getBytes(StandardCharsets.UTF_8);
        cas.put(engine, Hashing.sha256Hex(engine));
        install.materialize(version, cas, Hashing.sha256Hex(engine));
        Path bin = Files.createDirectories(JkDirs.binDir());
        Files.deleteIfExists(bin.resolve("jk"));
        Files.writeString(bin.resolve("jk"), "client " + version);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rolling_back_the_first_update_after_an_install_puts_back_the_installed_client() throws Exception {
        EngineInstall install = EngineInstall.current();
        Cas cas = JkStores.storeCas();
        installedByTheInstaller(install, cas, "0.16.2");

        SelfCommand.UpdateSub.apply(prepared(cas, "0.17.0"), install, cas, false);
        var back = install.rollback(cas, ClientPlacement.at(JkDirs.binDir()));

        assertThat(back.version()).isEqualTo("0.16.2");
        assertThat(Files.readString(EngineInstall.pathClient(JkDirs.binDir())))
                .as("the client paired with the engine put back")
                .isEqualTo("client 0.16.2");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_rollback_after_an_installer_reinstall_never_pairs_an_engine_with_another_releases_client()
            throws Exception {
        EngineInstall install = EngineInstall.current();
        Cas cas = JkStores.storeCas();
        SelfCommand.UpdateSub.apply(prepared(cas, "0.17.0"), install, cas, false);
        SelfCommand.UpdateSub.apply(prepared(cas, "0.18.0"), install, cas, false);
        installedByTheInstaller(install, cas, "0.19.0");

        var back = install.rollback(cas, ClientPlacement.at(JkDirs.binDir()));

        assertThat(back.version()).isEqualTo("0.18.0");
        assertThat(Files.readString(EngineInstall.pathClient(JkDirs.binDir())))
                .as("the client of the engine put back, not the one before it")
                .isEqualTo("client 0.18.0");
    }

    /**
     * A downgrade cut short after the client step (here the engine blob is gone, as a Ctrl-C or a
     * full disk would stop it) must not leave an older client than the engine the pointer names:
     * that client refuses to replace the newer engine and hands every command to itself.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_downgrade_cut_short_leaves_no_client_older_than_the_engine_pointer() throws Exception {
        EngineInstall install = EngineInstall.current();
        Cas cas = JkStores.storeCas();
        SelfCommand.UpdateSub.apply(prepared(cas, "0.17.0"), install, cas, false);
        PreparedUpdate older = prepared(cas, "0.16.2");
        Files.delete(cas.pathFor(older.engineSha256()));

        assertThatThrownBy(() -> SelfCommand.UpdateSub.apply(older, install, cas, true))
                .isInstanceOf(IOException.class);

        assertThat(install.currentInstall().orElseThrow().version()).isEqualTo("0.17.0");
        assertThat(Files.readString(EngineInstall.pathClient(JkDirs.binDir())))
                .as("the client beside a 0.17.0 pointer")
                .isEqualTo("client 0.17.0");
    }

    /** A JVM client is recorded beside its engine like a native one, and a rollback puts its jar back. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rolling_back_a_jvm_client_update_puts_back_the_jar_the_engine_ran_with() throws Exception {
        EngineInstall install = EngineInstall.current();
        Cas cas = JkStores.storeCas();
        SelfCommand.UpdateSub.apply(prepared(cas, "0.17.0", PreparedUpdate.Client.JVM), install, cas, false);
        SelfCommand.UpdateSub.apply(prepared(cas, "0.18.0", PreparedUpdate.Client.JVM), install, cas, false);

        var back = install.rollback(cas, ClientPlacement.at(JkDirs.binDir()));

        Path lib = JvmClientInstall.libDir();
        assertThat(back.version()).isEqualTo("0.17.0");
        assertThat(lib.resolve("jk-0.17.0.jar")).hasContent("client 0.17.0");
        assertThat(lib.resolve("jk-0.18.0.jar")).as("the later client's jar is retired").doesNotExist();
        assertThat(Files.readString(JvmClientInstall.launcher(JkDirs.binDir(), false))).contains("jk-0.17.0.jar");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void an_engine_that_does_not_start_fails_the_update(@TempDir Path tmp) throws Exception {
        Path jk = Files.writeString(tmp.resolve("jk"), "#!/bin/sh\nexit 3\n");
        assertThat(jk.toFile().setExecutable(true)).isTrue();

        assertThat(SelfCommand.UpdateSub.handOver(jk, "0.18.0", false)).isEqualTo(3);
    }

    @Test
    void a_record_for_another_release_or_with_lost_blobs_is_dropped() throws Exception {
        EngineInstall install = EngineInstall.current();
        Cas cas = JkStores.storeCas();

        prepared(cas, "0.18.0");
        assertThat(SelfCommand.UpdateSub.preparedFor(install, cas, "0.17.0")).isEmpty();
        assertThat(PreparedUpdate.read(install.engineHome())).isEmpty();

        PreparedUpdate lost = prepared(cas, "0.17.0");
        Files.delete(cas.pathFor(lost.clientSha256()));
        assertThat(SelfCommand.UpdateSub.preparedFor(install, cas, "0.17.0")).isEmpty();

        PreparedUpdate kept = prepared(cas, "0.17.0");
        assertThat(SelfCommand.UpdateSub.preparedFor(install, cas, "0.17.0")).contains(kept);
    }
}
