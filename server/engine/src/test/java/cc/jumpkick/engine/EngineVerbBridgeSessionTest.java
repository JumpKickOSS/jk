// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.protocol.ProtoReads;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The session envelope every verb is handed. A read-only request may legitimately carry no cache
 * path, and that has to mean "the engine's own" rather than a null one.
 */
class EngineVerbBridgeSessionTest {

    @Test
    void a_request_without_a_cache_field_falls_back_to_the_engine_cache() {
        // project-info stopped sending `cache` when nothing read it; then gave
        // the verb a session, and resolving one dereferenced the field that was no longer there.
        String request = ProtoReads.projectInfoRequest("/tmp/whatever", null, null, false);
        assertThat(request).doesNotContain("\"cache\"");

        Session session = EngineVerbBridge.resolve(request, Session.CancelToken.live(), false);
        assertThat(session.cacheDir()).isEqualTo(JkDirs.cache());
    }

    @Test
    void a_request_that_carries_a_cache_still_wins() {
        String request = ProtoReads.outdatedRequest("/tmp/whatever", "/tmp/cachedir", null, false, false);
        Session session = EngineVerbBridge.resolve(request, Session.CancelToken.live(), false);
        assertThat(session.cacheDir()).isEqualTo(Path.of("/tmp/cachedir"));
    }
}
