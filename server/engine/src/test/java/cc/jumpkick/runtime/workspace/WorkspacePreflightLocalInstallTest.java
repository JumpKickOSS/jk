// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.ReleaseVerb;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lock row this machine's installs served ({@code source = "local-install"}) is a developer's
 * inner loop: the build takes it and says so in one note. {@code jk release} and {@code jk publish}
 * stop on it before anything builds, saying to release or publish the dependency first; on the base
 * a release shipped a POM naming a snapshot only this machine holds, and a build said nothing.
 */
class WorkspacePreflightLocalInstallTest {

    @TempDir
    Path tmp;

    @Test
    void a_developers_build_takes_a_local_install_with_one_note_and_release_and_publish_refuse_it() throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), "group = \"ex\"\nname = \"app\"\nversion = \"1.0.0\"\n");
        lock(
                row("zz.lib:liba:jar:", "0.1.0-SNAPSHOT", RepositorySpec.LOCAL_INSTALL),
                row("org.slf4j:slf4j-api:jar:", "2.0.16", "central+https://repo1.maven.org/maven2/"));
        List<String> notes = new ArrayList<>();
        WorkspaceBuildListener listener = new WorkspaceBuildListener() {
            @Override
            public void onNote(String text) {
                notes.add(text);
            }
        };

        assertThat(WorkspacePreflightPhase.localInstalls(request(), listener, ReleaseVerb.NONE))
                .isEmpty();
        assertThat(notes)
                .containsExactly("depends on a local install of zz.lib:liba:0.1.0-SNAPSHOT (jk install on this"
                        + " machine); jk release and jk publish refuse until it is a released version");

        notes.clear();
        Optional<WorkspacePreflightPhase.Completed> released =
                WorkspacePreflightPhase.localInstalls(request(), listener, ReleaseVerb.RELEASE);
        assertThat(released).isPresent();
        assertThat(released.orElseThrow().result().exitCode()).isEqualTo(Exit.CONFIG);
        assertThat(released.orElseThrow().result().errors())
                .containsExactly("jk release refuses zz.lib:liba:0.1.0-SNAPSHOT: it comes from a local install (jk"
                        + " install on this machine), which no other machine has; release or publish it first and"
                        + " depend on that version");
        assertThat(WorkspacePreflightPhase.localInstalls(request(), listener, ReleaseVerb.PUBLISH)
                        .orElseThrow()
                        .result()
                        .errors())
                .singleElement()
                .asString()
                .startsWith("jk publish refuses zz.lib:liba:0.1.0-SNAPSHOT");
        assertThat(notes).as("a refusal is not also a note").isEmpty();

        lock(row("org.slf4j:slf4j-api:jar:", "2.0.16", "central+https://repo1.maven.org/maven2/"));
        assertThat(WorkspacePreflightPhase.localInstalls(request(), listener, ReleaseVerb.RELEASE))
                .as("a lock with no install row ships")
                .isEmpty();
        assertThat(notes).isEmpty();
    }

    private void lock(Lockfile.Artifact... rows) throws Exception {
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "test",
                Lockfile.RESOLUTION_ALGORITHM,
                null,
                null,
                null,
                null,
                List.of(rows),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of());
        LockfileWriter.write(lock, tmp.resolve("jk-lock.toml"));
    }

    private static Lockfile.Artifact row(String name, String version, String source) {
        return new Lockfile.Artifact(
                name, version, source, "sha256:" + "a".repeat(64), null, List.of(Scope.MAIN), List.of(), null);
    }

    private WorkspaceRequest request() {
        return new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 0, null, true, false, 0, null, true, true);
    }
}
