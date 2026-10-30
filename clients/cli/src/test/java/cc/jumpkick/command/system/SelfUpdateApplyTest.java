// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.PreparedUpdate;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.testing.PropertyRoots;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        byte[] engine = ("engine " + version).getBytes(StandardCharsets.UTF_8);
        byte[] client = ("client " + version).getBytes(StandardCharsets.UTF_8);
        cas.put(engine, Hashing.sha256Hex(engine));
        cas.put(client, Hashing.sha256Hex(client));
        PreparedUpdate p = new PreparedUpdate(
                version,
                "0.16.2",
                Hashing.sha256Hex(engine),
                PreparedUpdate.Client.NATIVE,
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
        SelfCommand.UpdateSub.apply(older, install, cas, true);

        assertThat(install.currentInstall().orElseThrow().version()).isEqualTo("0.16.2");
        assertThat(Files.readString(EngineInstall.pathClient(JkDirs.binDir()))).isEqualTo("client 0.16.2");
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
