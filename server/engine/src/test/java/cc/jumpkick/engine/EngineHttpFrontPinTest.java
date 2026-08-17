// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.lock.LockfileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HTTP/MCP job submission honors the same version pin the wire path delegates on: an
 * artifact-producing job for a pinned project is refused, never silently built with the resident
 * engine's version.
 */
class EngineHttpFrontPinTest {

    @Test
    void pinned_project_is_refused_for_artifact_producing_kinds(@TempDir Path dir) throws IOException {
        writeLockWithPin(dir, "0.0.1");
        assertThatThrownBy(() -> EngineHttpFront.refuseIfPinned(dir, EngineProtocol.BUILD_REQUEST, "1.0.0"))
                .isInstanceOf(PinnedProjectRefused.class)
                .hasMessageContaining("0.0.1")
                .hasMessageContaining("1.0.0");
        // Newer pins are refused too: the browser cannot spawn the pinned engine either way.
        LockfileReader.clearCache();
        writeLockWithPin(dir, "9.9.9");
        assertThatThrownBy(() -> EngineHttpFront.refuseIfPinned(dir, EngineProtocol.NATIVE_REQUEST, "1.0.0"))
                .isInstanceOf(PinnedProjectRefused.class);
    }

    @Test
    void non_artifact_kinds_and_unpinned_projects_pass(@TempDir Path dir) throws IOException {
        // No lockfile at all — nothing to pin.
        assertThatCode(() -> EngineHttpFront.refuseIfPinned(dir, EngineProtocol.BUILD_REQUEST, "1.0.0"))
                .doesNotThrowAnyException();
        writeLockWithPin(dir, "0.0.1");
        // lock/format/clean are not delegatable — the resident engine serves them regardless.
        assertThatCode(() -> EngineHttpFront.refuseIfPinned(dir, EngineProtocol.LOCK_REQUEST, "1.0.0"))
                .doesNotThrowAnyException();
        // Same version → serve locally.
        assertThatCode(() -> EngineHttpFront.refuseIfPinned(dir, EngineProtocol.BUILD_REQUEST, "0.0.1"))
                .doesNotThrowAnyException();
    }

    private static void writeLockWithPin(Path dir, String version) throws IOException {
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "jk %s"
                resolution-algorithm = "pubgrub-v1"
                jk = { version = "%s", sha256 = "" }
                """.formatted(version, version));
        LockfileReader.clearCache();
    }
}
